/* SPDX-License-Identifier: MPL-2.0 */
//! Tests for the two loader/host messages. Byte layouts are taken from
//! `docs/LOADER_PROTOCOL.md`; these tests pin them so a future edit cannot drift
//! silently from the reconstructed contract.

use miniloader::proto::{
    ProtoError, Tbd1, B1F2, B1F2_KIND, B1F2_LEN, B1F2_MAGIC, B1F2_STATUS, B1F2_VERSION,
    TBD1_HEADER_LEN, TBD1_MAGIC, TBD1_MAX_PAYLOAD, TBD1_VERSION,
};

#[test]
fn b1f2_request_has_the_documented_layout() {
    let request = B1F2::request(0x1122_3344, 0x5566_7788);
    let bytes = request.encode();
    assert_eq!(bytes.len(), B1F2_LEN);
    assert_eq!(&bytes[0..4], b"B1F2");
    assert_eq!(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]), B1F2_MAGIC);
    assert_eq!(u16::from_le_bytes([bytes[4], bytes[5]]), B1F2_VERSION);
    assert_eq!(u16::from_le_bytes([bytes[6], bytes[7]]), B1F2_KIND);
    assert_eq!(
        u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
        B1F2_STATUS
    );
    assert_eq!(&bytes[12..16], &0x1122_3344u32.to_le_bytes());
    assert_eq!(&bytes[16..20], &0x5566_7788u32.to_le_bytes());
    assert_eq!(&bytes[20..24], &0u32.to_le_bytes());
    assert_eq!(&bytes[24..28], &0u32.to_le_bytes());
    assert_eq!(&bytes[28..32], &[0u8; 4]);
}

#[test]
fn b1f2_round_trips_and_requires_both_handles() {
    let mut reply = B1F2::request(7, 9);
    reply.handle_hi = 0x10;
    reply.handle_lo = 0x20;
    let decoded = B1F2::decode(&reply.encode()).expect("decode");
    assert_eq!(decoded, reply);
    assert!(decoded.handles_present());

    let mut missing = reply;
    missing.handle_lo = 0;
    assert!(!missing.handles_present());
}

#[test]
fn b1f2_rejects_wrong_magic_version_kind_status_and_size() {
    let good = B1F2::request(1, 2).encode();

    let mut bad_magic = good;
    bad_magic[0] = b'X';
    assert!(matches!(B1F2::decode(&bad_magic), Err(ProtoError::BadMagic(_))));

    let mut bad_version = good;
    bad_version[4..6].copy_from_slice(&1u16.to_le_bytes());
    assert!(matches!(
        B1F2::decode(&bad_version),
        Err(ProtoError::BadVersion(1))
    ));

    let mut bad_kind = good;
    bad_kind[6..8].copy_from_slice(&9u16.to_le_bytes());
    assert!(matches!(B1F2::decode(&bad_kind), Err(ProtoError::BadKind(9))));

    let mut bad_status = good;
    bad_status[8..12].copy_from_slice(&0u32.to_le_bytes());
    assert!(matches!(
        B1F2::decode(&bad_status),
        Err(ProtoError::BadStatus(0))
    ));

    assert!(matches!(
        B1F2::decode(&good[..8]),
        Err(ProtoError::TooShort { need: B1F2_LEN, .. })
    ));
    let mut long = good.to_vec();
    long.push(0);
    assert!(matches!(
        B1F2::decode(&long),
        Err(ProtoError::TooLong { max: B1F2_LEN, .. })
    ));
}

#[test]
fn tbd1_encodes_header_and_payload() {
    let payload = vec![0xaa; 16];
    let block = Tbd1::new(0x1234, 0x5678_9abc, payload.clone()).expect("block");
    let bytes = block.encode();
    assert_eq!(bytes.len(), TBD1_HEADER_LEN + payload.len());
    assert_eq!(&bytes[0..4], b"TBD1");
    assert_eq!(
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
        TBD1_MAGIC
    );
    assert_eq!(u16::from_le_bytes([bytes[4], bytes[5]]), TBD1_VERSION);
    assert_eq!(u16::from_le_bytes([bytes[6], bytes[7]]), 0x1234);
    assert_eq!(&bytes[8..12], &0x5678_9abcu32.to_le_bytes());
    assert_eq!(
        u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]) as usize,
        payload.len()
    );
    assert_eq!(
        u32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
        1,
        "flags must be the attach value"
    );
    assert_eq!(&bytes[20..32], &[0u8; 12], "reserved words stay zero");
    assert_eq!(&bytes[32..], payload.as_slice());

    let decoded = Tbd1::decode(&bytes).expect("decode");
    assert_eq!(decoded, block);
}

#[test]
fn tbd1_rejects_bad_inputs() {
    let good = Tbd1::new(1, 2, vec![0u8; 4]).expect("block").encode();

    let mut bad_magic = good.clone();
    bad_magic[0] = 0;
    assert!(matches!(Tbd1::decode(&bad_magic), Err(ProtoError::BadMagic(_))));

    let mut bad_version = good.clone();
    bad_version[4..6].copy_from_slice(&9u16.to_le_bytes());
    assert!(matches!(
        Tbd1::decode(&bad_version),
        Err(ProtoError::BadVersion(9))
    ));

    let mut reserved_set = good.clone();
    reserved_set[20..24].copy_from_slice(&5u32.to_le_bytes());
    assert!(matches!(
        Tbd1::decode(&reserved_set),
        Err(ProtoError::ReservedNotZero { value: 5, .. })
    ));

    // Declared payload larger than the bounded maximum.
    let mut oversize = good.clone();
    oversize[12..16].copy_from_slice(&((TBD1_MAX_PAYLOAD + 1) as u32).to_le_bytes());
    assert!(matches!(
        Tbd1::decode(&oversize),
        Err(ProtoError::PayloadTooLarge(_))
    ));

    // Declared payload not matching the buffer length.
    let mut short = good.clone();
    short[12..16].copy_from_slice(&8u32.to_le_bytes());
    assert!(matches!(
        Tbd1::decode(&short),
        Err(ProtoError::TooShort { .. })
    ));

    assert!(matches!(
        Tbd1::decode(&good[..16]),
        Err(ProtoError::TooShort {
            need: TBD1_HEADER_LEN,
            ..
        })
    ));

    // The constructor refuses an oversized payload before anything is encoded.
    assert!(matches!(
        Tbd1::new(1, 1, vec![0u8; TBD1_MAX_PAYLOAD + 1]),
        Err(ProtoError::PayloadTooLarge(_))
    ));
}

#[test]
fn tbd1_limits_match_the_documented_bounds() {
    // 0xC00 payload bound and a < 0x1000 length check in the loader.
    assert_eq!(TBD1_MAX_PAYLOAD, 0xC00);
    assert!(TBD1_HEADER_LEN + TBD1_MAX_PAYLOAD < 0x1000);
    let full = Tbd1::new(0, 1, vec![0u8; TBD1_MAX_PAYLOAD]).expect("max payload");
    assert_eq!(full.encode().len(), TBD1_HEADER_LEN + TBD1_MAX_PAYLOAD);
    assert_eq!(Tbd1::decode(&full.encode()).expect("decode"), full);
}
#[test]
fn tbd1_reply_carries_status_size_and_echoes() {
    use miniloader::proto::{Tbd1Reply, TBD1_ERRNO_PROTO, TBD1_REPLY_LEN};

    let reply = Tbd1Reply::success(0x1234, 0x40, 0x1_0000);
    assert!(reply.is_success());
    let bytes = reply.encode();
    assert_eq!(bytes.len(), TBD1_REPLY_LEN);
    assert_eq!(&bytes[0..4], b"TBD1");
    assert_eq!(u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]), 0);
    assert_eq!(u32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]), 1);

    let decoded = Tbd1Reply::decode(&bytes).expect("decode");
    assert_eq!(decoded, reply);
    assert_eq!(decoded.size, 0x1_0000);

    // An error reply keeps the same envelope but a non-zero status.
    let mut failed = reply.clone();
    failed.status = TBD1_ERRNO_PROTO;
    assert!(!failed.is_success());
    let decoded = Tbd1Reply::decode(&failed.encode()).expect("decode error reply");
    assert_eq!(decoded.status, TBD1_ERRNO_PROTO);
    assert_eq!(TBD1_ERRNO_PROTO, 0x47, "the loader uses EPROTO (71) on failure");
}

#[test]
fn tbd1_reply_rejects_bad_envelopes() {
    use miniloader::proto::{Tbd1Reply, TBD1_REPLY_LEN};

    let good = Tbd1Reply::success(1, 2, 3).encode();
    let mut bad_magic = good.clone();
    bad_magic[0] = 0;
    assert!(matches!(Tbd1Reply::decode(&bad_magic), Err(ProtoError::BadMagic(_))));

    let mut bad_version = good.clone();
    bad_version[4..6].copy_from_slice(&7u16.to_le_bytes());
    assert!(matches!(Tbd1Reply::decode(&bad_version), Err(ProtoError::BadVersion(7))));

    let mut reserved = good.clone();
    reserved[20..24].copy_from_slice(&9u32.to_le_bytes());
    assert!(matches!(
        Tbd1Reply::decode(&reserved),
        Err(ProtoError::ReservedNotZero { value: 9, .. })
    ));

    assert!(matches!(Tbd1Reply::decode(&good[..16]), Err(ProtoError::TooShort { .. })));
    let mut long = good.clone();
    long.extend_from_slice(&[0u8; 8]);
    assert!(matches!(
        Tbd1Reply::decode(&long),
        Err(ProtoError::TooLong { max: TBD1_REPLY_LEN, .. })
    ));
}
