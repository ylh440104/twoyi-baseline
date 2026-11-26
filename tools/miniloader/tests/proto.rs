/* SPDX-License-Identifier: MPL-2.0 */
//! Tests for the two loader/host messages. Byte layouts are taken from
//! `docs/LOADER_PROTOCOL.md`; these tests pin them so a future edit cannot drift
//! silently from the reconstructed contract.

use miniloader::proto::{
    ProtoError, B1F2, B1F2_KIND, B1F2_LEN, B1F2_MAGIC, B1F2_STATUS, B1F2_VERSION, TBD1_MAGIC,
    TBD1_VERSION,
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
fn tbd1_layout_matches_the_disassembly() {
    use miniloader::proto::{
        Tbd1, TBD1_FRAME_LEN, TBD1_MAGIC, TBD1_PATH_LEN_OFFSET, TBD1_PATH_OFFSET, TBD1_TAG,
        TBD1_VERSION,
    };

    let block = Tbd1::for_path(0x1234, "/system/bin/init").expect("block");
    let bytes = block.encode();
    assert_eq!(bytes.len(), TBD1_FRAME_LEN, "send size is 3192 bytes");
    assert_eq!(&bytes[0..4], b"TBD1");
    assert_eq!(
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
        TBD1_MAGIC
    );
    assert_eq!(u16::from_le_bytes([bytes[4], bytes[5]]), TBD1_VERSION);
    assert_eq!(
        u16::from_le_bytes([bytes[6], bytes[7]]),
        TBD1_TAG,
        "offset 6 holds the constant tag 0x28"
    );
    assert_eq!(
        u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
        0x1234
    );
    assert_eq!(
        u32::from_le_bytes([
            bytes[TBD1_PATH_LEN_OFFSET],
            bytes[TBD1_PATH_LEN_OFFSET + 1],
            bytes[TBD1_PATH_LEN_OFFSET + 2],
            bytes[TBD1_PATH_LEN_OFFSET + 3],
        ]) as usize,
        "/system/bin/init".len()
    );
    assert_eq!(
        &bytes[TBD1_PATH_OFFSET..TBD1_PATH_OFFSET + "/system/bin/init".len()],
        b"/system/bin/init"
    );
    // Everything the loader does not fill stays zero (it memsets 0xC70 before use).
    assert!(bytes[TBD1_PATH_OFFSET + "/system/bin/init".len()..]
        .iter()
        .all(|byte| *byte == 0));

    assert_eq!(Tbd1::decode(&bytes).expect("decode"), block);
}

#[test]
fn tbd1_rejects_bad_frames() {
    use miniloader::proto::{Tbd1, TBD1_FRAME_LEN, TBD1_PATH_LEN_OFFSET, TBD1_PATH_OFFSET};

    let good = Tbd1::for_path(1, "/init").expect("block").encode();

    let mut bad_magic = good.clone();
    bad_magic[0] = 0;
    assert!(matches!(Tbd1::decode(&bad_magic), Err(ProtoError::BadMagic(_))));

    let mut bad_version = good.clone();
    bad_version[4..6].copy_from_slice(&9u16.to_le_bytes());
    assert!(matches!(
        Tbd1::decode(&bad_version),
        Err(ProtoError::BadVersion(9))
    ));

    let mut bad_tag = good.clone();
    bad_tag[6..8].copy_from_slice(&1u16.to_le_bytes());
    assert!(matches!(Tbd1::decode(&bad_tag), Err(ProtoError::BadTag(1))));

    let mut empty = good.clone();
    empty[TBD1_PATH_LEN_OFFSET..TBD1_PATH_LEN_OFFSET + 4].copy_from_slice(&0u32.to_le_bytes());
    assert!(matches!(Tbd1::decode(&empty), Err(ProtoError::EmptyPath)));

    let mut oversize = good.clone();
    oversize[TBD1_PATH_LEN_OFFSET..TBD1_PATH_LEN_OFFSET + 4]
        .copy_from_slice(&0x800u32.to_le_bytes());
    assert!(matches!(
        Tbd1::decode(&oversize),
        Err(ProtoError::PayloadTooLarge(_))
    ));

    assert!(matches!(
        Tbd1::decode(&good[..TBD1_FRAME_LEN - 1]),
        Err(ProtoError::TooShort { .. })
    ));
    let mut long = good.clone();
    long.push(0);
    assert!(matches!(
        Tbd1::decode(&long),
        Err(ProtoError::TooLong { max: TBD1_FRAME_LEN, .. })
    ));

    // The path must be absolute and fit the loader's 1024-byte bound.
    use miniloader::proto::{PathError, TBD1_PATH_MAX};
    assert!(matches!(
        Tbd1::for_path(1, "system/bin/init"),
        Err(PathError::NotAbsolute)
    ));
    let long_path = format!("/{}", "a".repeat(TBD1_PATH_MAX));
    assert!(matches!(
        Tbd1::for_path(1, &long_path),
        Err(PathError::TooLong(_))
    ));
    // A path that starts correctly but would run past the frame is refused there too.
    assert!(TBD1_PATH_OFFSET < TBD1_FRAME_LEN);
}

#[test]
fn tbd1_reply_echoes_tag_and_sequence() {
    use miniloader::proto::{Tbd1Reply, TBD1_ERRNO_PROTO, TBD1_REPLY_LEN, TBD1_TAG};

    let reply = Tbd1Reply::success(TBD1_TAG, 0x99);
    assert!(reply.is_success());
    let bytes = reply.encode();
    assert_eq!(bytes.len(), TBD1_REPLY_LEN, "receive size is 0x878 bytes");
    assert_eq!(&bytes[0..4], b"TBD1");
    assert_eq!(u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]), 0);
    assert_eq!(u32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]), 1);
    assert_eq!(Tbd1Reply::decode(&bytes).expect("decode"), reply);

    let mut failed = reply.clone();
    failed.status = TBD1_ERRNO_PROTO;
    assert!(!failed.is_success());
    assert_eq!(
        Tbd1Reply::decode(&failed.encode()).expect("decode").status,
        TBD1_ERRNO_PROTO
    );
    assert_eq!(TBD1_ERRNO_PROTO, 0x47, "EPROTO is what the loader reports");
}

#[test]
fn tbd1_reply_rejects_bad_envelopes() {
    use miniloader::proto::{Tbd1Reply, TBD1_REPLY_LEN, TBD1_TAG};

    let good = Tbd1Reply::success(TBD1_TAG, 1).encode();
    let mut bad_magic = good.clone();
    bad_magic[0] = 0;
    assert!(matches!(Tbd1Reply::decode(&bad_magic), Err(ProtoError::BadMagic(_))));

    let mut bad_version = good.clone();
    bad_version[4..6].copy_from_slice(&7u16.to_le_bytes());
    assert!(matches!(
        Tbd1Reply::decode(&bad_version),
        Err(ProtoError::BadVersion(7))
    ));

    let mut reserved = good.clone();
    reserved[20..24].copy_from_slice(&9u32.to_le_bytes());
    assert!(matches!(
        Tbd1Reply::decode(&reserved),
        Err(ProtoError::ReservedNotZero { value: 9, .. })
    ));

    assert!(matches!(
        Tbd1Reply::decode(&good[..TBD1_REPLY_LEN - 1]),
        Err(ProtoError::TooShort { .. })
    ));
}
