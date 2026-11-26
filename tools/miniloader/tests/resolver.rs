/* SPDX-License-Identifier: MPL-2.0 */
//! Resolver envelope tests: 360 bytes in, exactly 988 bytes out, one exchange.
//!
//! The sizes are Java-side facts (`docs/HAL_PROTOCOL.md` §5). What the bytes mean
//! is unknown, so only the envelope is asserted here.

#![cfg(unix)]

use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::thread;

use miniloader::resolver::{
    request_once, serve_once, Resolver, ResolverError, RESOLVER_REQUEST_LEN, RESOLVER_RESPONSE_LEN,
};

struct Echo;

impl Resolver for Echo {
    fn resolve(&mut self, request: &[u8; RESOLVER_REQUEST_LEN]) -> Vec<u8> {
        // Reply is 988 bytes: the request repeated and padded.
        let mut response = vec![0u8; RESOLVER_RESPONSE_LEN];
        for (index, byte) in request.iter().enumerate() {
            if index < RESOLVER_RESPONSE_LEN {
                response[index] = *byte;
            }
        }
        response
    }
}

struct WrongSize(usize);

impl Resolver for WrongSize {
    fn resolve(&mut self, _request: &[u8; RESOLVER_REQUEST_LEN]) -> Vec<u8> {
        vec![0u8; self.0]
    }
}

fn pair() -> (UnixStream, UnixStream) {
    UnixStream::pair().expect("socketpair")
}

#[test]
fn one_exchange_delivers_the_full_response() {
    let (mut host, mut guest) = pair();
    let server = thread::spawn(move || serve_once(&mut host, &mut Echo));

    let mut request = [0u8; RESOLVER_REQUEST_LEN];
    for (index, slot) in request.iter_mut().enumerate() {
        *slot = (index % 251) as u8;
    }
    let response = request_once(&mut guest, &request).expect("exchange");
    assert_eq!(response.len(), RESOLVER_RESPONSE_LEN);
    assert_eq!(&response[..RESOLVER_REQUEST_LEN], &request[..]);

    server.join().expect("server thread").expect("serve");
}

#[test]
fn a_short_request_is_reported_not_answered() {
    let (mut host, mut guest) = pair();
    let server = thread::spawn(move || serve_once(&mut host, &mut Echo));

    // Send 100 of the 360 bytes, then close.
    guest.write_all(&[7u8; 100]).expect("write");
    guest.flush().expect("flush");
    drop(guest);

    match server.join().expect("server thread") {
        Err(ResolverError::ShortRequest { got }) => assert_eq!(got, 100),
        other => panic!("expected a short request error, got {other:?}"),
    }
}

#[test]
fn a_wrong_sized_response_is_refused() {
    let (mut host, mut guest) = pair();
    let server = thread::spawn(move || serve_once(&mut host, &mut WrongSize(987)));

    let request = [0u8; RESOLVER_REQUEST_LEN];
    // The host refuses before writing, so the guest sees EOF instead of 988 bytes.
    let result = request_once(&mut guest, &request);
    assert!(
        matches!(result, Err(ResolverError::BadResponseLen { .. })),
        "expected the guest to fail on a short response, got {result:?}"
    );

    match server.join().expect("server thread") {
        Err(ResolverError::BadResponseLen { got }) => assert_eq!(got, 987),
        other => panic!("expected a bad response length, got {other:?}"),
    }
}

#[test]
fn an_oversized_response_is_also_refused() {
    let (mut host, mut guest) = pair();
    let server = thread::spawn(move || serve_once(&mut host, &mut WrongSize(989)));

    let request = [0u8; RESOLVER_REQUEST_LEN];
    let _ = request_once(&mut guest, &request);

    match server.join().expect("server thread") {
        Err(ResolverError::BadResponseLen { got }) => assert_eq!(got, 989),
        other => panic!("expected a bad response length, got {other:?}"),
    }
}

#[test]
fn lengths_match_the_java_layer() {
    // Pinned so a future edit cannot silently drift from TwoyiResolverHal.
    assert_eq!(RESOLVER_REQUEST_LEN, 360);
    assert_eq!(RESOLVER_RESPONSE_LEN, 988);
    assert!(RESOLVER_RESPONSE_LEN > RESOLVER_REQUEST_LEN);
}