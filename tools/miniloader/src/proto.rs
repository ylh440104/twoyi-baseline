/* SPDX-License-Identifier: MPL-2.0 */
//! The two loader/host messages, encoded from the instruction stream rather than
//! from pseudo-C.
//!
//! `B1F2` comes from `FUN_00116520`; `TBD1` from `FUN_00116df4`. Offsets below were
//! read out of `aarch64-linux-gnu-objdump` (`docs/LOADER_PROTOCOL.md` §2 and §8),
//! which is why they are literal: the pseudo-C mistyped these stack slots.

pub const B1F2_MAGIC: u32 = 0x3246_3142; // "B1F2" little endian
pub const B1F2_VERSION: u16 = 2;
pub const B1F2_KIND: u16 = 1;
pub const B1F2_STATUS: u32 = 3;
pub const B1F2_LEN: usize = 32;

pub const TBD1_MAGIC: u32 = 0x3144_4254; // "TBD1" little endian
pub const TBD1_VERSION: u16 = 1;
/// Constant tag the loader writes at offset 6 and the broker echoes back.
pub const TBD1_TAG: u16 = 0x28;
pub const TBD1_PATH_LEN_OFFSET: usize = 68;
pub const TBD1_PATH_OFFSET: usize = 120;
/// Exact send size: `mov w9, #0xc78`.
pub const TBD1_FRAME_LEN: usize = 0xC78;
/// Exact receive size: `add x9, x8, #0x878; eor x2, x9, x8`.
pub const TBD1_REPLY_LEN: usize = 0x878;
/// `strnlen(path, 0x401)` bound in the loader.
pub const TBD1_PATH_MAX: usize = 0x400;
pub const TBD1_FLAG_ATTACH: u32 = 1;
/// `errno` the loader reports when the reply is inconsistent.
pub const TBD1_ERRNO_PROTO: u32 = 0x47;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtoError {
    TooShort { len: usize, need: usize },
    TooLong { len: usize, max: usize },
    BadMagic(u32),
    BadVersion(u16),
    BadKind(u16),
    BadStatus(u32),
    BadTag(u16),
    BadFlags(u32),
    EmptyPath,
    ReservedNotZero { offset: usize, value: u32 },
    PayloadTooLarge(usize),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PathError {
    NotAbsolute,
    TooLong(usize),
}

/// The `B1F2` broker handshake: one 32-byte request, one 32-byte reply.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct B1F2 {
    pub version: u16,
    pub kind: u16,
    pub status: u32,
    /// Two words the loader echoes between request and reply.
    pub echo_a: u32,
    pub echo_b: u32,
    /// Remaining words; the loader checks that both handles are non-zero.
    pub handle_hi: u32,
    pub handle_lo: u32,
}

impl B1F2 {
    pub fn request(echo_a: u32, echo_b: u32) -> B1F2 {
        B1F2 {
            version: B1F2_VERSION,
            kind: B1F2_KIND,
            status: B1F2_STATUS,
            echo_a,
            echo_b,
            handle_hi: 0,
            handle_lo: 0,
        }
    }

    pub fn encode(&self) -> [u8; B1F2_LEN] {
        let mut out = [0u8; B1F2_LEN];
        out[0..4].copy_from_slice(&B1F2_MAGIC.to_le_bytes());
        out[4..6].copy_from_slice(&self.version.to_le_bytes());
        out[6..8].copy_from_slice(&self.kind.to_le_bytes());
        out[8..12].copy_from_slice(&self.status.to_le_bytes());
        out[12..16].copy_from_slice(&self.echo_a.to_le_bytes());
        out[16..20].copy_from_slice(&self.echo_b.to_le_bytes());
        out[20..24].copy_from_slice(&self.handle_hi.to_le_bytes());
        out[24..28].copy_from_slice(&self.handle_lo.to_le_bytes());
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<B1F2, ProtoError> {
        if bytes.len() < B1F2_LEN {
            return Err(ProtoError::TooShort {
                len: bytes.len(),
                need: B1F2_LEN,
            });
        }
        if bytes.len() > B1F2_LEN {
            return Err(ProtoError::TooLong {
                len: bytes.len(),
                max: B1F2_LEN,
            });
        }
        let magic = read_u32(bytes, 0);
        if magic != B1F2_MAGIC {
            return Err(ProtoError::BadMagic(magic));
        }
        let version = read_u16(bytes, 4);
        if version != B1F2_VERSION {
            return Err(ProtoError::BadVersion(version));
        }
        let kind = read_u16(bytes, 6);
        if kind != B1F2_KIND {
            return Err(ProtoError::BadKind(kind));
        }
        let status = read_u32(bytes, 8);
        if status != B1F2_STATUS {
            return Err(ProtoError::BadStatus(status));
        }
        Ok(B1F2 {
            version,
            kind,
            status,
            echo_a: read_u32(bytes, 12),
            echo_b: read_u32(bytes, 16),
            handle_hi: read_u32(bytes, 20),
            handle_lo: read_u32(bytes, 24),
        })
    }

    /// The loader requires both handles to be set once the reply arrived.
    pub fn handles_present(&self) -> bool {
        self.handle_hi != 0 && self.handle_lo != 0
    }
}

/// The `TBD1` pre-exec request: a fixed 3192-byte frame carrying a guest path.
///
/// ```text
/// str  w10, [x24]        w10 = 0x31444254     offset   0  magic
/// strh w12, [x24, #4]    w12 = 1              offset   4  version
/// strh w8,  [x24, #6]    w8  = 0x28           offset   6  tag (echoed)
/// str  w8,  [x24, #8]    w8  = sequence       offset   8  sequence
/// str  w26, [x24, #68]   w26 = path length    offset  68  path length
/// memcpy(x24 + 0x78, path, len)               offset 120  path bytes
/// ```
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tbd1 {
    pub sequence: u32,
    pub path: Vec<u8>,
}

impl Tbd1 {
    /// Build a request for an absolute guest path.
    pub fn for_path(sequence: u32, path: &str) -> Result<Tbd1, PathError> {
        Tbd1::from_raw(sequence, path.as_bytes().to_vec())
    }

    /// Build a request from bytes the caller already holds.
    pub fn from_raw(sequence: u32, path: Vec<u8>) -> Result<Tbd1, PathError> {
        if path.first() != Some(&b'/') {
            return Err(PathError::NotAbsolute);
        }
        if path.len() > TBD1_PATH_MAX {
            return Err(PathError::TooLong(path.len()));
        }
        Ok(Tbd1 { sequence, path })
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = vec![0u8; TBD1_FRAME_LEN];
        out[0..4].copy_from_slice(&TBD1_MAGIC.to_le_bytes());
        out[4..6].copy_from_slice(&TBD1_VERSION.to_le_bytes());
        out[6..8].copy_from_slice(&TBD1_TAG.to_le_bytes());
        out[8..12].copy_from_slice(&self.sequence.to_le_bytes());
        out[TBD1_PATH_LEN_OFFSET..TBD1_PATH_LEN_OFFSET + 4]
            .copy_from_slice(&(self.path.len() as u32).to_le_bytes());
        let end = TBD1_PATH_OFFSET + self.path.len();
        out[TBD1_PATH_OFFSET..end].copy_from_slice(&self.path);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Tbd1, ProtoError> {
        if bytes.len() < TBD1_FRAME_LEN {
            return Err(ProtoError::TooShort {
                len: bytes.len(),
                need: TBD1_FRAME_LEN,
            });
        }
        if bytes.len() > TBD1_FRAME_LEN {
            return Err(ProtoError::TooLong {
                len: bytes.len(),
                max: TBD1_FRAME_LEN,
            });
        }
        let magic = read_u32(bytes, 0);
        if magic != TBD1_MAGIC {
            return Err(ProtoError::BadMagic(magic));
        }
        let version = read_u16(bytes, 4);
        if version != TBD1_VERSION {
            return Err(ProtoError::BadVersion(version));
        }
        let tag = read_u16(bytes, 6);
        if tag != TBD1_TAG {
            return Err(ProtoError::BadTag(tag));
        }
        let path_len = read_u32(bytes, TBD1_PATH_LEN_OFFSET) as usize;
        if path_len == 0 {
            return Err(ProtoError::EmptyPath);
        }
        if path_len > TBD1_PATH_MAX || TBD1_PATH_OFFSET + path_len > TBD1_FRAME_LEN {
            return Err(ProtoError::PayloadTooLarge(path_len));
        }
        Ok(Tbd1 {
            sequence: read_u32(bytes, 8),
            path: bytes[TBD1_PATH_OFFSET..TBD1_PATH_OFFSET + path_len].to_vec(),
        })
    }
}

/// The broker's answer: same envelope, echoing tag and sequence.
///
/// Layout read from the instruction stream of `FUN_00116df4` (see
/// `docs/LOADER_PROTOCOL.md` section 8):
///
/// | offset | field |
/// |---:|---|
/// | 0 | magic 0x31444254 |
/// | 4 | version 1 |
/// | 6 | tag echo (must equal the request tag) |
/// | 8 | sequence echo (must equal the request sequence) |
/// | 12 | descriptor when >= 0; -1 is the failure value |
/// | 16 | flags: bit 0 required to succeed; bit 0x200000 and the low 2 bits carry backend info |
/// | 20 | backend bitmap: 0xf is special, and its bit 0 must be clear |
/// | 24, 28 | reserved, must be zero |
///
/// The descriptor is carried in slot 12; the payload size is not on the wire
/// because the loader fstats the received fd. This is a **static inference**
/// from the instruction stream, not yet confirmed on a device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tbd1Reply {
    pub tag: u16,
    pub sequence: u32,
    /// Descriptor received over SCM_RIGHTS; negative means failure.
    pub fd: i64,
    pub flags: u32,
    /// Backend bitmap; 0xf marks the special backend.
    pub backend: u32,
}

/// Backend bitmap value that marks the special backend.
pub const TBD1_BACKEND_SPECIAL: u32 = 0x0f;
/// The success path requires this flag bit.
pub const TBD1_FLAG_REQUIRED: u32 = 0x01;
/// Bit the loader inspects in the flags word.
pub const TBD1_FLAG_BACKEND: u32 = 0x20_0000;

impl Tbd1Reply {
    pub fn success(tag: u16, sequence: u32, fd: i64) -> Tbd1Reply {
        Tbd1Reply {
            tag,
            sequence,
            fd,
            flags: TBD1_FLAG_REQUIRED,
            backend: 0,
        }
    }

    pub fn is_success(&self) -> bool {
        self.fd >= 0 && self.flags & TBD1_FLAG_REQUIRED != 0
    }

    /// Backend bitmap rule from the loader: 0xf is allowed only with bit 0 clear.
    pub fn backend_is_acceptable(&self) -> bool {
        self.backend != TBD1_BACKEND_SPECIAL || self.flags & TBD1_FLAG_BACKEND == 0
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = vec![0u8; TBD1_REPLY_LEN];
        out[0..4].copy_from_slice(&TBD1_MAGIC.to_le_bytes());
        out[4..6].copy_from_slice(&TBD1_VERSION.to_le_bytes());
        out[6..8].copy_from_slice(&self.tag.to_le_bytes());
        out[8..12].copy_from_slice(&self.sequence.to_le_bytes());
        let fd = self.fd as i32;
        out[12..16].copy_from_slice(&fd.to_le_bytes());
        out[16..20].copy_from_slice(&self.flags.to_le_bytes());
        out[20..24].copy_from_slice(&self.backend.to_le_bytes());
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Tbd1Reply, ProtoError> {
        if bytes.len() < TBD1_REPLY_LEN {
            return Err(ProtoError::TooShort {
                len: bytes.len(),
                need: TBD1_REPLY_LEN,
            });
        }
        let magic = read_u32(bytes, 0);
        if magic != TBD1_MAGIC {
            return Err(ProtoError::BadMagic(magic));
        }
        let version = read_u16(bytes, 4);
        if version != TBD1_VERSION {
            return Err(ProtoError::BadVersion(version));
        }
        for (index, offset) in [24usize, 28].iter().enumerate() {
            let value = read_u32(bytes, *offset);
            if value != 0 {
                return Err(ProtoError::ReservedNotZero {
                    offset: index,
                    value,
                });
            }
        }
        Ok(Tbd1Reply {
            tag: read_u16(bytes, 6),
            sequence: read_u32(bytes, 8),
            fd: read_u32(bytes, 12) as i32 as i64,
            flags: read_u32(bytes, 16),
            backend: read_u32(bytes, 20),
        })
    }
}

fn read_u16(data: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([data[offset], data[offset + 1]])
}

fn read_u32(data: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        data[offset],
        data[offset + 1],
        data[offset + 2],
        data[offset + 3],
    ])
}