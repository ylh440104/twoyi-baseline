/* SPDX-License-Identifier: MPL-2.0 */
//! The transport the official loader actually uses: a Unix socket pair where the
//! broker attaches a file descriptor to each reply via `SCM_RIGHTS`.
//!
//! `docs/LOADER_PROTOCOL.md` records this from the instruction stream: the first
//! handshake creates `socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC)`, and the
//! descriptor the loader later reads is not in the payload at all — it arrives as
//! ancillary data on the same call. Modelling that here means the channel can be
//! exercised with a real descriptor on any Linux host, which is the one part of the
//! handshake a plain byte stream cannot prove.

use std::io;
use std::os::unix::io::RawFd;

/// A pair of connected sockets, plus the raw descriptors for fd passing.
pub struct SocketPair {
    pub a: RawFd,
    pub b: RawFd,
}

impl SocketPair {
    /// `socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC)`.
    pub fn new() -> io::Result<SocketPair> {
        let mut fds = [0 as RawFd; 2];
        // CLOEXEC keeps the pair from leaking into an exec, matching the official
        // `socketpair(1, 0x80005, ...)` (SOCK_SEQPACKET | SOCK_CLOEXEC).
        let kind = libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC;
        let result = unsafe { libc::socketpair(libc::AF_UNIX, kind, 0, fds.as_mut_ptr()) };
        if result != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(SocketPair { a: fds[0], b: fds[1] })
    }

    /// Duplicate a descriptor from this process, so a guest can be handed one.
    pub fn dup_here(fd: RawFd) -> io::Result<RawFd> {
        let duplicate = unsafe { libc::dup(fd) };
        if duplicate < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(duplicate)
    }
}

impl Drop for SocketPair {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.a);
            libc::close(self.b);
        }
    }
}

/// Send `payload` on `fd` with `attach` handed over as ancillary data.
///
/// The receiver must call [`recv_frame`] to take the descriptor; a descriptor that is
/// sent but never received stays open in the receiver until the socket closes.
pub fn send_frame(fd: RawFd, payload: &[u8], attach: Option<RawFd>) -> io::Result<()> {
    let mut iov = libc::iovec {
        iov_base: payload.as_ptr() as *mut libc::c_void,
        iov_len: payload.len(),
    };
    let mut control = vec![0u8; control_len(attach.is_some())];
    let mut header = libc::msghdr {
        msg_name: std::ptr::null_mut(),
        msg_namelen: 0,
        msg_iov: &mut iov,
        msg_iovlen: 1,
        msg_control: if attach.is_some() {
            control.as_mut_ptr() as *mut libc::c_void
        } else {
            std::ptr::null_mut()
        },
        msg_controllen: if attach.is_some() { control.len() } else { 0 },
        msg_flags: 0,
    };

    if let Some(descriptor) = attach {
        unsafe {
            let cmsg = control.as_mut_ptr() as *mut libc::cmsghdr;
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            (*cmsg).cmsg_len =
                libc::CMSG_LEN(std::mem::size_of::<RawFd>() as u32) as usize;
            let data = control.as_mut_ptr().add(cmsg_data_offset()) as *mut RawFd;
            *data = descriptor;
        }
    }

    let sent = unsafe { libc::sendmsg(fd, &header, 0) };
    if sent < 0 {
        return Err(io::Error::last_os_error());
    }
    if sent as usize != payload.len() {
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!("short send: {sent} of {}", payload.len()),
        ));
    }
    Ok(())
}

/// Receive one frame of at most `max_len` bytes, taking any attached descriptor.
pub fn recv_frame(fd: RawFd, max_len: usize) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut data = vec![0u8; max_len];
    let mut control = vec![0u8; control_len(true)];
    let mut iov = libc::iovec {
        iov_base: data.as_mut_ptr() as *mut libc::c_void,
        iov_len: data.len(),
    };
    let mut header = libc::msghdr {
        msg_name: std::ptr::null_mut(),
        msg_namelen: 0,
        msg_iov: &mut iov,
        msg_iovlen: 1,
        msg_control: control.as_mut_ptr() as *mut libc::c_void,
        msg_controllen: control.len(),
        msg_flags: 0,
    };

    let received = unsafe { libc::recvmsg(fd, &mut header, 0) };
    if received < 0 {
        return Err(io::Error::last_os_error());
    }
    let length = received as usize;
    data.truncate(length);

    let mut attached = None;
    if header.msg_controllen > 0 {
        let cmsg = control.as_ptr() as *const libc::cmsghdr;
        if unsafe { (*cmsg).cmsg_level } == libc::SOL_SOCKET
            && unsafe { (*cmsg).cmsg_type } == libc::SCM_RIGHTS
        {
            let space = (unsafe { (*cmsg).cmsg_len }) as usize - cmsg_data_offset();
            if space >= std::mem::size_of::<RawFd>() {
                attached = Some(unsafe { *(control.as_ptr().add(cmsg_data_offset()) as *const RawFd) });
            }
        }
    }
    Ok((data, attached))
}

/// Close a descriptor received from [`recv_frame`].
pub fn close_fd(fd: RawFd) {
    unsafe {
        libc::close(fd);
    }
}

fn cmsg_data_offset() -> usize {
    // CMSG_LEN(0) is exactly the size of the header including trailing padding.
    unsafe { libc::CMSG_LEN(0) as usize }
}

fn control_len(with_fd: bool) -> usize {
    if !with_fd {
        return 0;
    }
    unsafe { libc::CMSG_SPACE(std::mem::size_of::<RawFd>() as u32) as usize }
}