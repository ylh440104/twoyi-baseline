/* SPDX-License-Identifier: MPL-2.0 */
//! End-to-end handshake test over a real socket pair.
//!
//! The two ends run in separate threads and exchange the exact bytes documented
//! in `docs/LOADER_PROTOCOL.md`. This is the closest verification available
//! without a device: the same code paths that will run on Android, over the same
//! socket type the loader uses (`socketpair` -> UNIX stream here, since the
//! message framing is already length-defined by the protocol).

#![cfg(unix)]

use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::thread;

use miniloader::broker::{Broker, ChannelError, HandshakeClient};
use miniloader::proto::{Tbd1, TBD1_MAX_PAYLOAD};

fn pair() -> (UnixStream, UnixStream) {
    UnixStream::pair().expect("socketpair")
}

#[test]
fn handshake_then_exec_succeeds() {
    let (loader_side, host_side) = pair();

    let host = thread::spawn(move || {
        let mut broker = Broker::new(host_side, 3);
        // Handles are two words; the loader only requires both to be non-zero.
        let echo = broker.handshake(0x11, 0x22).expect("handshake");
        let served = broker.serve_exec().expect("exec");
        (echo, served)
    });

    let mut client = HandshakeClient::new(loader_side, 2);
    let handles = client.attach(0xdead, 0xbeef).expect("attach");
    assert_eq!(handles, (0x11, 0x22));

    let payload = b"/system/bin/init --attach".to_vec();
    let block = Tbd1::new(1, 0x40, payload.clone()).expect("block");
    let accepted = client.exec(&block).expect("exec ack");
    assert_eq!(accepted as usize, payload.len());

    let (echo, served) = host.join().expect("host thread");
    assert_eq!(echo, (0xdead, 0xbeef));
    assert_eq!(served.sequence, 1);
    assert_eq!(served.role, 0x40);
    assert_eq!(served.payload_len, payload.len());
    assert_eq!(served.accepted as usize, payload.len());
}

#[test]
fn exec_before_attach_is_refused_locally() {
    let (loader_side, host_side) = pair();
    drop(host_side);
    let mut client = HandshakeClient::new(loader_side, 2);
    let block = Tbd1::new(1, 1, Vec::new()).expect("block");
    match client.exec(&block) {
        Err(ChannelError::Unexpected(message)) => assert!(message.contains("before attach")),
        other => panic!("expected a local refusal, got {other:?}"),
    }
}

#[test]
fn attach_version_mismatch_is_detected() {
    let (loader_side, host_side) = pair();
    let host = thread::spawn(move || {
        let mut broker = Broker::new(host_side, 3);
        // The host answers with version 2 (protocol constant); the client is told
        // to expect 3 so the check trips.
        let _ = broker.handshake(1, 2);
    });
    let mut client = HandshakeClient::new(loader_side, 3);
    match client.attach(1, 1) {
        Err(ChannelError::Unexpected(message)) => assert!(message.contains("attach version")),
        other => panic!("expected a version mismatch, got {other:?}"),
    }
    host.join().expect("host thread");
}

#[test]
fn zero_handle_reply_is_rejected() {
    let (loader_side, host_side) = pair();
    let host = thread::spawn(move || {
        let mut broker = Broker::new(host_side, 3);
        let _ = broker.handshake(0, 5); // handle_hi == 0 must be refused by the client
    });
    let mut client = HandshakeClient::new(loader_side, 2);
    match client.attach(1, 1) {
        Err(ChannelError::Unexpected(message)) => assert!(message.contains("zero handle")),
        other => panic!("expected a zero handle rejection, got {other:?}"),
    }
    host.join().expect("host thread");
}

#[test]
fn oversized_declared_payload_is_refused_by_the_broker() {
    let (loader_side, host_side) = pair();
    let host = thread::spawn(move || {
        let mut broker = Broker::new(host_side, 3);
        let _ = broker.handshake(1, 1);
        broker.serve_exec()
    });

    // Craft a frame whose declared payload exceeds the protocol bound.
    let mut client = loader_side;
    let mut request = [0u8; 32];
    let magic = 0x3246_3142u32.to_le_bytes();
    request[0..4].copy_from_slice(&magic);
    request[4..6].copy_from_slice(&2u16.to_le_bytes());
    request[6..8].copy_from_slice(&1u16.to_le_bytes());
    request[8..12].copy_from_slice(&3u32.to_le_bytes());
    request[12..16].copy_from_slice(&1u32.to_le_bytes());
    request[16..20].copy_from_slice(&1u32.to_le_bytes());
    request[20..24].copy_from_slice(&7u32.to_le_bytes());
    request[24..28].copy_from_slice(&8u32.to_le_bytes());
    client.write_all(&request).unwrap();
    client.flush().unwrap();
    let mut reply = [0u8; 32];
    client.read_exact(&mut reply).unwrap();

    let mut header = [0u8; 32];
    let tbd1 = 0x3144_4254u32.to_le_bytes();
    header[0..4].copy_from_slice(&tbd1);
    header[4..6].copy_from_slice(&1u16.to_le_bytes());
    header[6..8].copy_from_slice(&1u16.to_le_bytes());
    header[12..16].copy_from_slice(&((TBD1_MAX_PAYLOAD + 1) as u32).to_le_bytes());
    header[16..20].copy_from_slice(&1u32.to_le_bytes());
    client.write_all(&header).unwrap();
    client.flush().unwrap();

    let result = host.join().expect("host thread");
    match result {
        Err(ChannelError::OversizedFrame(size)) => assert_eq!(size, TBD1_MAX_PAYLOAD + 1),
        other => panic!("expected an oversized frame rejection, got {other:?}"),
    }
}