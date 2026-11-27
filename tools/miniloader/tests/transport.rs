/* SPDX-License-Identifier: MPL-2.0 */
//! Descriptor passing tests. These exercise the one part of the handshake a plain
//! byte stream cannot represent: the descriptor carried as ancillary data.
//!
//! The wire layout is unchanged (`B1F2`, then `TBD1`, both fixed size); what is new
//! is that a real fd travels with each reply, exactly as the official loader's
//! `SCM_RIGHTS` copy does.

#![cfg(target_os = "linux")]

use std::fs::File;
use std::io::Write;
use std::os::unix::io::AsRawFd;

use miniloader::transport::{close_fd, dup_here, recv_frame, send_frame, SocketPair};

/// A descriptor that reads back a known byte, so passing it can be verified.
fn descriptor_with_marker(path: &std::path::Path) -> i32 {
    let mut file = File::create(path).expect("create");
    file.write_all(b"twoyi").expect("write");
    drop(file);
    let file = File::open(path).expect("open");
    let fd = file.as_raw_fd();
    let duplicate = dup_here(fd).expect("dup");
    std::mem::forget(file);
    duplicate
}

#[test]
fn a_frame_without_a_descriptor_round_trips() {
    let pair = SocketPair::new().expect("socketpair");
    let payload = b"B1F2 handshake";
    send_frame(pair.a, payload, None).expect("send");

    let (received, attached) = recv_frame(pair.b, 64).expect("recv");
    assert_eq!(&received, payload);
    assert_eq!(attached, None);
}

#[test]
fn a_descriptor_travels_with_the_frame() {
    let work = std::env::temp_dir().join(format!("twoyi-fd-{}", std::process::id()));
    let source = descriptor_with_marker(&work);
    let pair = SocketPair::new().expect("socketpair");

    send_frame(pair.a, b"TBD1", Some(source.clone())).expect("send");
    let (received, attached) = recv_frame(pair.b, 64).expect("recv");
    assert_eq!(&received, b"TBD1");
    let received_fd = attached.expect("a descriptor must arrive");

    // The received descriptor must refer to the same file: read it back.
    let text = unsafe {
        let mut buffer = [0u8; 5];
        let count = libc::pread(received_fd, buffer.as_mut_ptr() as *mut libc::c_void, 5, 0);
        assert_eq!(count, 5, "the descriptor is readable");
        String::from_utf8(buffer.to_vec()).expect("utf8")
    };
    assert_eq!(text, "twoyi", "the received descriptor points at the marker file");

    close_fd(received_fd);
    close_fd(source);
    let _ = std::fs::remove_file(&work);
}

#[test]
fn two_frames_with_descriptors_are_independent() {
    let pair = SocketPair::new().expect("socketpair");
    let first = std::env::temp_dir().join(format!("twoyi-fd-a-{}", std::process::id()));
    let second = std::env::temp_dir().join(format!("twoyi-fd-b-{}", std::process::id()));
    let fd_a = descriptor_with_marker(&first);
    let fd_b = descriptor_with_marker(&second);

    send_frame(pair.a, b"one", Some(fd_a.clone())).expect("send one");
    send_frame(pair.a, b"two", Some(fd_b.clone())).expect("send two");

    let (payload_a, fd_one) = recv_frame(pair.b, 64).expect("recv one");
    let (payload_b, fd_two) = recv_frame(pair.b, 64).expect("recv two");
    assert_eq!(&payload_a, b"one");
    assert_eq!(&payload_b, b"two");
    let one = fd_one.expect("first descriptor");
    let two = fd_two.expect("second descriptor");
    assert_ne!(one, two, "each frame carries its own descriptor");

    close_fd(one);
    close_fd(two);
    close_fd(fd_a);
    close_fd(fd_b);
    let _ = std::fs::remove_file(&first);
    let _ = std::fs::remove_file(&second);
}

#[test]
fn a_dup_is_a_separate_descriptor() {
    let work = std::env::temp_dir().join(format!("twoyi-fd-dup-{}", std::process::id()));
    let source = descriptor_with_marker(&work);
    let duplicate = dup_here(source).expect("dup again");
    assert_ne!(source, duplicate, "dup returns a different descriptor number");
    close_fd(source);
    // Closing the original must not affect the duplicate.
    let text = unsafe {
        let mut buffer = [0u8; 5];
        let count = libc::pread(duplicate, buffer.as_mut_ptr() as *mut libc::c_void, 5, 0);
        assert_eq!(count, 5, "the duplicate stays usable");
        String::from_utf8(buffer.to_vec()).expect("utf8")
    };
    assert_eq!(text, "twoyi");
    close_fd(duplicate);
    let _ = std::fs::remove_file(&work);
}