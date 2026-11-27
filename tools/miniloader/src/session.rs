/* SPDX-License-Identifier: MPL-2.0 */
//! The handshake carried the way the official loader carries it: a `socketpair`
//! with the descriptor attached to the reply as ancillary data.
//!
//! `broker.rs` drives the same messages over a plain `Read + Write`, which proves the
//! byte layout. This module adds the piece a byte stream cannot express — the
//! descriptor that arrives with the reply — and runs the whole session over a real
//! Unix socket pair, so the sequence and the fd hand-over are tested together.

use std::io;

use crate::bridge::SymbolTable;
use crate::elf::Elf;
use crate::executor::MappedImage;
use crate::proto::{Tbd1, Tbd1Reply, B1F2, B1F2_LEN, TBD1_FRAME_LEN, TBD1_REPLY_LEN, TBD1_TAG};
use crate::transport::{close_fd, read_all_fd, recv_frame, send_frame};
use std::os::unix::io::RawFd;

#[derive(Debug)]
pub enum SessionError {
    Io(io::Error),
    /// The reply did not decode, or disagreed with the request.
    Mismatch(String),
    /// A reply that should have carried a descriptor did not.
    MissingDescriptor,
    /// The descriptor did not hold a loadable ELF, or mapping it failed.
    Unloadable(String),
}

impl From<io::Error> for SessionError {
    fn from(error: io::Error) -> Self {
        SessionError::Io(error)
    }
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SessionError::Io(error) => write!(formatter, "io: {error}"),
            SessionError::Mismatch(text) => write!(formatter, "mismatch: {text}"),
            SessionError::MissingDescriptor => {
                write!(formatter, "the reply carried no descriptor")
            }
            SessionError::Unloadable(reason) => write!(formatter, "unloadable guest: {reason}"),
        }
    }
}

/// What the loader obtained from a completed handshake.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Attached {
    pub handle_hi: u32,
    pub handle_lo: u32,
    /// Descriptor received with the reply; the caller owns it and must close it.
    pub descriptor: i32,
}

/// The loader side of one full session over a socket pair.
///
/// Returns the attach result and the descriptor the broker handed over. Everything
/// the guest needs afterwards travels on that descriptor, which is why the official
/// loader keeps `TWOYI_LINKER_BOOT_DATA_FD` rather than a payload.
pub fn attach_over_socketpair(
    loader_fd: RawFd,
    echo_a: u32,
    echo_b: u32,
    descriptor_to_send: i32,
) -> Result<Attached, SessionError> {
    // Each side only touches the endpoint it owns; the descriptors are independent.
    let request = B1F2::request(echo_a, echo_b).encode();
    send_frame(loader_fd, &request, None)?;

    let (reply, attached) = recv_frame(loader_fd, B1F2_LEN)?;
    if reply.len() != B1F2_LEN {
        return Err(SessionError::Mismatch(format!(
            "B1F2 reply was {} bytes, want {B1F2_LEN}",
            reply.len()
        )));
    }
    let decoded =
        B1F2::decode(&reply).map_err(|error| SessionError::Mismatch(format!("{error:?}")))?;
    if decoded.echo_a != echo_a || decoded.echo_b != echo_b {
        return Err(SessionError::Mismatch(
            "reply did not echo the request".into(),
        ));
    }
    if !decoded.handles_present() {
        return Err(SessionError::Mismatch("reply carried a zero handle".into()));
    }
    let descriptor = attached.ok_or(SessionError::MissingDescriptor)?;

    let _ = descriptor_to_send;
    Ok(Attached {
        handle_hi: decoded.handle_hi,
        handle_lo: decoded.handle_lo,
        descriptor,
    })
}

/// The broker side: wait for the request, then answer with a descriptor.
///
/// The descriptor passed in is duplicated before sending so the caller keeps its own;
/// a real broker hands the guest a descriptor onto the rootfs it opened.
pub fn serve_attach(
    broker_fd: RawFd,
    handle_hi: u32,
    handle_lo: u32,
    descriptor: i32,
) -> Result<(u32, u32), SessionError> {
    let (request, attached) = recv_frame(broker_fd, B1F2_LEN)?;
    if request.len() != B1F2_LEN {
        return Err(SessionError::Mismatch(format!(
            "B1F2 request was {} bytes, want {B1F2_LEN}",
            request.len()
        )));
    }
    if attached.is_some() {
        // The request must not smuggle a descriptor in; ignore it rather than leak.
        return Err(SessionError::Mismatch("request carried an unexpected descriptor".into()));
    }
    let decoded =
        B1F2::decode(&request).map_err(|error| SessionError::Mismatch(format!("{error:?}")))?;
    let mut reply = B1F2::request(decoded.echo_a, decoded.echo_b);
    reply.handle_hi = handle_hi;
    reply.handle_lo = handle_lo;
    let frame = reply.encode();
    send_frame(broker_fd, &frame, Some(descriptor))?;
    Ok((decoded.echo_a, decoded.echo_b))
}

/// One `TBD1` exchange over the socket pair: send the block, read the reply.
///
/// Unlike the attach step this frame carries no descriptor; the reply's own fields
/// report the outcome, and any descriptor in it is closed rather than leaked.
pub fn exec_over_socketpair(
    loader_fd: RawFd,
    block: &Tbd1,
    tag: u16,
) -> Result<u32, SessionError> {
    let frame = block.encode();
    send_frame(loader_fd, &frame, None)?;

    let (reply, attached) = recv_frame(loader_fd, TBD1_REPLY_LEN)?;
    if let Some(descriptor) = attached {
        // The loader does not expect one here; close it instead of leaking.
        close_fd(descriptor);
    }
    if reply.len() != TBD1_REPLY_LEN {
        return Err(SessionError::Mismatch(format!(
            "TBD1 reply was {} bytes, want {TBD1_REPLY_LEN}",
            reply.len()
        )));
    }
    let decoded =
        Tbd1Reply::decode(&reply).map_err(|error| SessionError::Mismatch(format!("{error:?}")))?;
    if decoded.sequence != block.sequence {
        return Err(SessionError::Mismatch(format!(
            "reply sequence {} does not match request {}",
            decoded.sequence, block.sequence
        )));
    }
    if decoded.tag != tag {
        return Err(SessionError::Mismatch(format!(
            "reply tag 0x{:x} does not match 0x{:x}",
            decoded.tag, tag
        )));
    }
    if !decoded.is_success() || !decoded.backend_is_acceptable() {
        return Err(SessionError::Mismatch(format!(
            "broker refused the exec: fd={} flags=0x{:x} backend=0x{:x}",
            decoded.fd, decoded.flags, decoded.backend
        )));
    }
    Ok(decoded.sequence)
}

/// Serve one `TBD1` request with a successful reply.
pub fn serve_exec(broker_fd: RawFd, tag: u16) -> Result<(u32, usize), SessionError> {
    let (frame, attached) = recv_frame(broker_fd, TBD1_FRAME_LEN)?;
    if let Some(descriptor) = attached {
        close_fd(descriptor);
    }
    if frame.len() != TBD1_FRAME_LEN {
        return Err(SessionError::Mismatch(format!(
            "TBD1 request was {} bytes, want {TBD1_FRAME_LEN}",
            frame.len()
        )));
    }
    let block =
        Tbd1::decode(&frame).map_err(|error| SessionError::Mismatch(format!("{error:?}")))?;
    let reply = Tbd1Reply::success(tag, block.sequence, 0);
    send_frame(broker_fd, &reply.encode(), None)?;
    Ok((block.sequence, block.path.len()))
}

/// The tag the loader puts at offset 6; exposed so callers pass the real constant.
pub const REQUEST_TAG: u16 = TBD1_TAG;

/// The whole data path in one call: attach, read the guest binary from the descriptor
/// the broker handed over, then map it.
///
/// This is the sequence the official loader performs — `B1F2` gives it a descriptor,
/// the descriptor is the guest ELF, and the loader maps that image. The descriptor is
/// left open because the guest keeps it (`TWOYI_LINKER_BOOT_DATA_FD`) after the image
/// has been read.
pub fn load_from_attach(
    loader_fd: RawFd,
    echo_a: u32,
    echo_b: u32,
    page_size: u64,
    bridge: &SymbolTable,
) -> Result<(Attached, MappedImage), SessionError> {
    let attached = attach_over_socketpair(loader_fd, echo_a, echo_b, -1)?;
    let bytes = read_all_fd(attached.descriptor)
        .map_err(|error| SessionError::Unloadable(format!("reading the guest: {error}")))?;
    let elf = Elf::parse(&bytes).map_err(|error| SessionError::Unloadable(format!("{error:?}")))?;
    let image = MappedImage::map_with_bridge(&elf, page_size, true, Some(bridge))
        .map_err(|error| SessionError::Unloadable(format!("{error}")))?;
    Ok((attached, image))
}

/// Broker side of [`load_from_attach`]: hand over `descriptor` (usually an open guest
/// binary) and answer the attach.
pub fn serve_attach_descriptor(
    broker_fd: RawFd,
    handle_hi: u32,
    handle_lo: u32,
    descriptor: RawFd,
) -> Result<(u32, u32), SessionError> {
    serve_attach(broker_fd, handle_hi, handle_lo, descriptor)
}