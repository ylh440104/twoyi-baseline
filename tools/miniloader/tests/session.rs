/* SPDX-License-Identifier: MPL-2.0 */
//! The full session over a real socket pair, with the descriptor travelling as
//! ancillary data — the way the official loader does it.
//!
//! `handshake.rs` proves the byte layout over a plain stream; this proves the same
//! session works when the broker has to hand the guest a live descriptor. Each side
//! owns one endpoint, so the broker runs in a thread and the loader stays here.

#![cfg(unix)]

use std::fs::File;
use std::io::Write;
use std::os::unix::io::AsRawFd;

use miniloader::proto::{Tbd1, B1F2_LEN};
use miniloader::session::{
    attach_over_socketpair, exec_over_socketpair, serve_attach, serve_exec, SessionError,
    REQUEST_TAG,
};
use miniloader::transport::{close_fd, dup_here, SocketPair};

/// A descriptor whose content can be read back, proving what was handed over.
fn marker_descriptor(path: &std::path::Path, text: &str) -> i32 {
    let mut file = File::create(path).expect("create");
    file.write_all(text.as_bytes()).expect("write");
    drop(file);
    let reader = File::open(path).expect("open");
    let duplicate = dup_here(reader.as_raw_fd()).expect("dup");
    std::mem::forget(reader);
    duplicate
}

fn read_marker(fd: i32, length: usize) -> String {
    let mut buffer = [0u8; 16];
    let count = unsafe { libc::pread(fd, buffer.as_mut_ptr() as *mut libc::c_void, length, 0) };
    assert_eq!(count as usize, length, "the handed-over descriptor must be readable");
    String::from_utf8(buffer[..length].to_vec()).expect("utf8")
}

#[test]
fn attach_hands_over_a_usable_descriptor() {
    let work = std::env::temp_dir().join(format!("twoyi-attach-{}", std::process::id()));
    let rootfs = marker_descriptor(&work, "rootfs");
    let pair = SocketPair::new().expect("socketpair");

    // The broker owns endpoint a; the loader keeps endpoint b and drives it here.
    let broker_fd = pair.a;
    let loader_fd = pair.b;
    let broker = std::thread::spawn(move || serve_attach(broker_fd, 0x11, 0x22, rootfs));

    let attached = attach_over_socketpair(loader_fd, 0xdead, 0xbeef, rootfs).expect("attach");
    assert_eq!(attached.handle_hi, 0x11, "the broker's first handle arrives");
    assert_eq!(attached.handle_lo, 0x22, "the broker's second handle arrives");
    // The descriptor the loader received is a copy of the broker's descriptor: it must
    // read back the same bytes, which is the point of sending it at all.
    assert_eq!(read_marker(attached.descriptor, 6), "rootfs");

    let served = broker.join().expect("broker thread").expect("serve");
    assert_eq!(served, (0xdead, 0xbeef), "the broker reports the echoed words");
    close_fd(attached.descriptor);
    close_fd(rootfs);
    let _ = std::fs::remove_file(&work);
}

#[test]
fn a_broker_that_withholds_the_descriptor_is_detected() {
    let pair = SocketPair::new().expect("socketpair");
    let broker_fd = pair.a;
    let loader_fd = pair.b;
    let broker = std::thread::spawn(move || {
        // Reply without attaching a descriptor on purpose.
        let (frame, attached) = miniloader::transport::recv_frame(broker_fd, B1F2_LEN).expect("recv");
        assert!(attached.is_none(), "the request carries no descriptor");
        let decoded = miniloader::proto::B1F2::decode(&frame).expect("decode");
        let mut reply = miniloader::proto::B1F2::request(decoded.echo_a, decoded.echo_b);
        reply.handle_hi = 1;
        reply.handle_lo = 2;
        miniloader::transport::send_frame(broker_fd, &reply.encode(), None).expect("send");
    });

    match attach_over_socketpair(loader_fd, 1, 2, -1) {
        Err(SessionError::MissingDescriptor) => {}
        other => panic!("expected a missing descriptor, got {:?}", other.map(|_| ())),
    }
    broker.join().expect("broker thread");
}

#[test]
fn a_session_runs_attach_then_exec() {
    let work = std::env::temp_dir().join(format!("twoyi-session-{}", std::process::id()));
    let rootfs = marker_descriptor(&work, "rootfs");
    let pair = SocketPair::new().expect("socketpair");
    let broker_fd = pair.a;
    let loader_fd = pair.b;

    let broker = std::thread::spawn(move || {
        let attach = serve_attach(broker_fd, 7, 9, rootfs).expect("attach");
        let exec = serve_exec(broker_fd, REQUEST_TAG).expect("exec");
        (attach, exec)
    });

    let attached = attach_over_socketpair(loader_fd, 0x1234, 0x5678, rootfs).expect("attach");
    assert_eq!(read_marker(attached.descriptor, 6), "rootfs");
    close_fd(attached.descriptor);

    let block = Tbd1::for_path(3, "/system/bin/init").expect("block");
    let sequence = exec_over_socketpair(loader_fd, &block, REQUEST_TAG).expect("exec");
    assert_eq!(sequence, 3, "the reply echoes the request sequence");

    let (attach, exec) = broker.join().expect("broker thread");
    assert_eq!(attach, (0x1234, 0x5678));
    assert_eq!(exec.0, 3);
    assert_eq!(exec.1, "/system/bin/init".len(), "the broker saw the path length");
    close_fd(rootfs);
    let _ = std::fs::remove_file(&work);
}

#[test]
fn a_sequence_and_tag_mismatch_is_reported() {
    let work = std::env::temp_dir().join(format!("twoyi-mismatch-{}", std::process::id()));
    let rootfs = marker_descriptor(&work, "rootfs");
    let pair = SocketPair::new().expect("socketpair");
    let broker_fd = pair.a;
    let loader_fd = pair.b;

    let broker = std::thread::spawn(move || {
        serve_attach(broker_fd, 1, 2, rootfs).expect("attach");
        // Serve with the wrong tag so the loader must reject the reply.
        serve_exec(broker_fd, 0x99).expect("exec")
    });

    let attached = attach_over_socketpair(loader_fd, 1, 2, rootfs).expect("attach");
    close_fd(attached.descriptor);
    let block = Tbd1::for_path(1, "/init").expect("block");
    match exec_over_socketpair(loader_fd, &block, REQUEST_TAG) {
        Err(SessionError::Mismatch(text)) => assert!(text.contains("tag"), "message: {text}"),
        other => panic!("expected a tag mismatch, got {:?}", other.map(|_| ())),
    }
    broker.join().expect("broker thread");
    close_fd(rootfs);
    let _ = std::fs::remove_file(&work);
}
/// Build a minimal aarch64 ELF with one executable PT_LOAD holding `payload`.
fn guest_elf(payload: &[u8]) -> Vec<u8> {
    let phoff = 64usize;
    let payload_offset = phoff + 56;
    let total = payload_offset + payload.len();
    let mut out = vec![0u8; total];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    // entry inside the segment
    out[0x18..0x20].copy_from_slice(&0x400000u64.to_le_bytes());
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes());
    let ph = phoff;
    out[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
    out[ph + 4..ph + 8].copy_from_slice(&5u32.to_le_bytes()); // R|X
    out[ph + 8..ph + 16].copy_from_slice(&(payload_offset as u64).to_le_bytes());
    out[ph + 16..ph + 24].copy_from_slice(&0x400000u64.to_le_bytes());
    out[ph + 32..ph + 40].copy_from_slice(&(payload.len() as u64).to_le_bytes());
    out[ph + 40..ph + 48].copy_from_slice(&(payload.len() as u64).to_le_bytes());
    out[ph + 48..ph + 56].copy_from_slice(&4096u64.to_le_bytes());
    out[payload_offset..].copy_from_slice(payload);
    out
}

#[test]
fn the_loader_reads_the_guest_from_the_descriptor_and_maps_it() {
    // The data path the official loader uses: attach gives a descriptor, the
    // descriptor is the guest binary, and the loader maps that image.
    let payload = [0xffu8, 0x43, 0x00, 0xd1, 0xc0, 0x03, 0x5f, 0xd6];
    let bytes = guest_elf(&payload);
    let work = std::env::temp_dir().join(format!("twoyi-guest-{}", std::process::id()));
    {
        let mut file = File::create(&work).expect("create elf");
        file.write_all(&bytes).expect("write elf");
    }
    let guest_fd = {
        let reader = File::open(&work).expect("open elf");
        let duplicate = dup_here(reader.as_raw_fd()).expect("dup");
        std::mem::forget(reader);
        duplicate
    };

    let pair = SocketPair::new().expect("socketpair");
    let broker_fd = pair.a;
    let loader_fd = pair.b;
    let broker = std::thread::spawn(move || serve_attach(broker_fd, 0xaa, 0xbb, guest_fd));

    let bridge = miniloader::SymbolTable::new();
    let (attached, image) =
        miniloader::session::load_from_attach(loader_fd, 1, 2, 4096, &bridge).expect("load");

    assert_eq!(attached.handle_hi, 0xaa);
    assert_eq!(attached.handle_lo, 0xbb);
    // The mapped image must hold the payload at its entry point.
    let entry = image.entry();
    let mapped = image.read(entry, payload.len()).expect("read entry bytes");
    assert_eq!(mapped, &payload[..], "the guest payload was mapped from the descriptor");

    broker.join().expect("broker thread").expect("serve");
    close_fd(attached.descriptor);
    close_fd(guest_fd);
    let _ = std::fs::remove_file(&work);
}
