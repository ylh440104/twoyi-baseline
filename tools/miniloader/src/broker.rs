/* SPDX-License-Identifier: MPL-2.0 */
//! The two ends of the loader/broker channel, driven over any `Read + Write`.
//!
//! Tests pair them over `UnixStream::pair()`, which exercises the real wire
//! format: the loader sends `B1F2`, receives handles, then sends `TBD1` before an
//! exec. The transport is generic so the same code can later run over the
//! `socketpair` + `SCM_RIGHTS` channel the official loader uses.

use std::io::{Read, Write};

use crate::proto::{
    ProtoError, Tbd1, Tbd1Reply, B1F2, B1F2_LEN, TBD1_FRAME_LEN, TBD1_PATH_LEN_OFFSET,
    TBD1_REPLY_LEN, TBD1_TAG,
};

#[derive(Debug)]
pub enum ChannelError {
    Io(std::io::Error),
    Proto(ProtoError),
    /// The peer sent a frame larger than the protocol allows.
    OversizedFrame(usize),
    /// The peer replied with a message the loader would reject.
    Unexpected(String),
}

impl From<std::io::Error> for ChannelError {
    fn from(error: std::io::Error) -> Self {
        ChannelError::Io(error)
    }
}

impl From<ProtoError> for ChannelError {
    fn from(error: ProtoError) -> Self {
        ChannelError::Proto(error)
    }
}

impl std::fmt::Display for ChannelError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ChannelError::Io(error) => write!(formatter, "io: {error}"),
            ChannelError::Proto(error) => write!(formatter, "protocol: {error:?}"),
            ChannelError::OversizedFrame(size) => write!(formatter, "frame too large: {size}"),
            ChannelError::Unexpected(text) => write!(formatter, "unexpected: {text}"),
        }
    }
}

fn read_exact(stream: &mut impl Read, buffer: &mut [u8]) -> Result<(), ChannelError> {
    let mut filled = 0;
    while filled < buffer.len() {
        let count = stream.read(&mut buffer[filled..])?;
        if count == 0 {
            return Err(ChannelError::Unexpected("peer closed mid frame".to_string()));
        }
        filled += count;
    }
    Ok(())
}

/// Loader side: performs the handshake and sends the pre-exec block.
pub struct HandshakeClient<S: Read + Write> {
    stream: S,
    attach_version: u16,
    handles: Option<(u32, u32)>,
}

impl<S: Read + Write> HandshakeClient<S> {
    pub fn new(stream: S, attach_version: u16) -> Self {
        Self {
            stream,
            attach_version,
            handles: None,
        }
    }

    /// Send `B1F2` and validate the reply, keeping the two handles it carries.
    pub fn attach(&mut self, echo_a: u32, echo_b: u32) -> Result<(u32, u32), ChannelError> {
        let request = B1F2::request(echo_a, echo_b).encode();
        self.stream.write_all(&request)?;
        self.stream.flush()?;

        let mut reply = [0u8; B1F2_LEN];
        read_exact(&mut self.stream, &mut reply)?;
        let decoded = B1F2::decode(&reply)?;
        if decoded.version != self.attach_version {
            return Err(ChannelError::Unexpected(format!(
                "attach version {} but the host expects {}",
                self.attach_version, decoded.version
            )));
        }
        if decoded.echo_a != echo_a || decoded.echo_b != echo_b {
            return Err(ChannelError::Unexpected("reply did not echo the request".to_string()));
        }
        if !decoded.handles_present() {
            return Err(ChannelError::Unexpected("reply carried a zero handle".to_string()));
        }
        self.handles = Some((decoded.handle_hi, decoded.handle_lo));
        Ok((decoded.handle_hi, decoded.handle_lo))
    }

    /// Send the `TBD1` pre-exec block and read the host's acknowledgement, which
    /// is the accepted payload length.
    pub fn exec(&mut self, block: &Tbd1) -> Result<u32, ChannelError> {
        if self.handles.is_none() {
            return Err(ChannelError::Unexpected("exec before attach".to_string()));
        }
        let frame = block.encode();
        self.stream.write_all(&frame)?;
        self.stream.flush()?;
        let mut ack = vec![0u8; TBD1_REPLY_LEN];
        read_exact(&mut self.stream, &mut ack)?;
        let reply = Tbd1Reply::decode(&ack)?;
        if reply.sequence != block.sequence {
            return Err(ChannelError::Unexpected(format!(
                "reply sequence {} does not match request {}",
                reply.sequence, block.sequence
            )));
        }
        if !reply.is_success() || !reply.backend_is_acceptable() {
            return Err(ChannelError::Unexpected(format!(
                "broker refused the exec: fd={} flags=0x{:x} backend=0x{:x}",
                reply.fd, reply.flags, reply.backend
            )));
        }
        Ok(reply.sequence)
    }
}

/// Broker side: answers one `B1F2`, then serves one `TBD1`.
pub struct Broker<S: Read + Write> {
    stream: S,
    status: u32,
    next_sequence: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServedExec {
    pub sequence: u32,
    pub path_len: usize,
}

impl<S: Read + Write> Broker<S> {
    pub fn new(stream: S, status: u32) -> Self {
        Self {
            stream,
            status,
            next_sequence: 0,
        }
    }

    /// Read one `B1F2`, validate it, and reply with two handles.
    pub fn handshake(&mut self, handle_hi: u32, handle_lo: u32) -> Result<(u32, u32), ChannelError> {
        let mut frame = [0u8; B1F2_LEN];
        read_exact(&mut self.stream, &mut frame)?;
        let request = B1F2::decode(&frame)?;
        let mut reply = B1F2::request(request.echo_a, request.echo_b);
        reply.status = self.status;
        reply.handle_hi = handle_hi;
        reply.handle_lo = handle_lo;
        self.stream.write_all(&reply.encode())?;
        self.stream.flush()?;
        Ok((request.echo_a, request.echo_b))
    }

    /// Read one `TBD1`, validate it, and acknowledge with its payload length.
    pub fn serve_exec(&mut self) -> Result<ServedExec, ChannelError> {
        // The frame is a fixed 3192 bytes; read it whole.
        let mut frame = vec![0u8; TBD1_FRAME_LEN];
        read_exact(&mut self.stream, &mut frame)?;
        let declared =
            u32::from_le_bytes(frame[TBD1_PATH_LEN_OFFSET..TBD1_PATH_LEN_OFFSET + 4].try_into().unwrap())
                as usize;
        if declared > TBD1_FRAME_LEN {
            return Err(ChannelError::OversizedFrame(declared));
        }
        let block = Tbd1::decode(&frame)?;
        if self.next_sequence != 0 && block.sequence < self.next_sequence {
            return Err(ChannelError::Unexpected(format!(
                "sequence went backwards: {} after {}",
                block.sequence, self.next_sequence
            )));
        }
        self.next_sequence = block.sequence.wrapping_add(1);
        let reply = Tbd1Reply::success(TBD1_TAG, block.sequence, 0);
        self.stream.write_all(&reply.encode())?;
        self.stream.flush()?;
        Ok(ServedExec {
            sequence: block.sequence,
            path_len: block.path.len(),
        })
    }
}