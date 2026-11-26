/* SPDX-License-Identifier: MPL-2.0 */
//! The two loader/host messages, encoded exactly as documented.
//!
//! Field tables come from `docs/LOADER_PROTOCOL.md`, which was reconstructed from
//! the official loader's pseudo-C (`FUN_00116520` for `B1F2`, `FUN_00116df4` for
//! `TBD1`). Encoding lives here so both ends can be tested without a device.

pub const B1F2_MAGIC: u32 = 0x3246_3142; // "B1F2" little endian
pub const B1F2_VERSION: u16 = 2;
pub const B1F2_KIND: u16 = 1;
pub const B1F2_STATUS: u32 = 3;
pub const B1F2_LEN: usize = 32;

pub const TBD1_MAGIC: u32 = 0x3144_4254; // "TBD1" little endian
pub const TBD1_VERSION: u16 = 1;
pub const TBD1_HEADER_LEN: usize = 32;
pub const TBD1_MAX_PAYLOAD: usize = 0xC00; // memcpy bound observed in the loader
pub const TBD1_MAX_LEN: usize = TBD1_HEADER_LEN + TBD1_MAX_PAYLOAD;
pub const TBD1_FLAG_ATTACH: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtoError {
    TooShort { len: usize, need: usize },
    TooLong { len: usize, max: usize },
    BadMagic(u32),
    BadVersion(u16),
    BadKind(u16),
    BadStatus(u32),
    BadFlags(u32),
    ReservedNotZero { offset: usize, value: u32 },
    PayloadTooLarge(usize),
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

/// The `TBD1` pre-exec parameter block: 32-byte header plus a bounded payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tbd1 {
    pub sequence: u16,
    pub role: u32,
    pub flags: u32,
    pub payload: Vec<u8>,
}

impl Tbd1 {
    pub fn new(sequence: u16, role: u32, payload: Vec<u8>) -> Result<Tbd1, ProtoError> {
        if payload.len() > TBD1_MAX_PAYLOAD {
            return Err(ProtoError::PayloadTooLarge(payload.len()));
        }
        Ok(Tbd1 {
            sequence,
            role,
            flags: TBD1_FLAG_ATTACH,
            payload,
        })
    }

    pub fn len(&self) -> usize {
        TBD1_HEADER_LEN + self.payload.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.len());
        out.extend_from_slice(&TBD1_MAGIC.to_le_bytes());
        out.extend_from_slice(&TBD1_VERSION.to_le_bytes());
        out.extend_from_slice(&self.sequence.to_le_bytes());
        out.extend_from_slice(&self.role.to_le_bytes());
        out.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes()); // reserved
        out.extend_from_slice(&0u32.to_le_bytes()); // reserved
        out.extend_from_slice(&0u32.to_le_bytes()); // reserved
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Tbd1, ProtoError> {
        if bytes.len() < TBD1_HEADER_LEN {
            return Err(ProtoError::TooShort {
                len: bytes.len(),
                need: TBD1_HEADER_LEN,
            });
        }
        if bytes.len() > TBD1_MAX_LEN {
            return Err(ProtoError::TooLong {
                len: bytes.len(),
                max: TBD1_MAX_LEN,
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
        let sequence = read_u16(bytes, 6);
        let role = read_u32(bytes, 8);
        let payload_len = read_u32(bytes, 12) as usize;
        let flags = read_u32(bytes, 16);
        if payload_len > TBD1_MAX_PAYLOAD {
            return Err(ProtoError::PayloadTooLarge(payload_len));
        }
        for (index, offset) in [20usize, 24, 28].iter().enumerate() {
            let value = read_u32(bytes, *offset);
            if value != 0 {
                return Err(ProtoError::ReservedNotZero {
                    offset: index,
                    value,
                });
            }
        }
        let expected = TBD1_HEADER_LEN + payload_len;
        if bytes.len() < expected {
            return Err(ProtoError::TooShort {
                len: bytes.len(),
                need: expected,
            });
        }
        if bytes.len() > expected {
            return Err(ProtoError::TooLong {
                len: bytes.len(),
                max: expected,
            });
        }
        Ok(Tbd1 {
            sequence,
            role,
            flags,
            payload: bytes[TBD1_HEADER_LEN..expected].to_vec(),
        })
    }
}

/// `TBD1` reply: the broker answers with a status plus (on success) an fd that
/// arrives over `SCM_RIGHTS` and its size. Field table: `docs/LOADER_PROTOCOL.md` §5.
pub const TBD1_REPLY_LEN: usize = 0x888;
/// `EPROTO` — the errno the loader uses when the reply is inconsistent.
pub const TBD1_ERRNO_PROTO: u32 = 0x47;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tbd1Reply {
    pub sequence: u16,
    pub role: u32,
    /// Zero means success and an fd was attached; non-zero is an errno.
    pub status: u32,
    pub flags: u32,
    /// Size of the file behind the attached fd, as reported by the broker.
    pub size: u64,
}

impl Tbd1Reply {
    pub fn success(sequence: u16, role: u32, size: u64) -> Tbd1Reply {
        Tbd1Reply {
            sequence,
            role,
            status: 0,
            flags: TBD1_FLAG_ATTACH,
            size,
        }
    }

    pub fn is_success(&self) -> bool {
        self.status == 0 && self.flags == TBD1_FLAG_ATTACH
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = vec![0u8; TBD1_REPLY_LEN];
        out[0..4].copy_from_slice(&TBD1_MAGIC.to_le_bytes());
        out[4..6].copy_from_slice(&TBD1_VERSION.to_le_bytes());
        out[6..8].copy_from_slice(&self.sequence.to_le_bytes());
        out[8..12].copy_from_slice(&self.role.to_le_bytes());
        out[12..16].copy_from_slice(&self.status.to_le_bytes());
        out[16..20].copy_from_slice(&self.flags.to_le_bytes());
        // `size` never travels on the wire: the loader fstats the fd it received.
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Tbd1Reply, ProtoError> {
        if bytes.len() < TBD1_HEADER_LEN {
            return Err(ProtoError::TooShort {
                len: bytes.len(),
                need: TBD1_HEADER_LEN,
            });
        }
        if bytes.len() > TBD1_REPLY_LEN {
            return Err(ProtoError::TooLong {
                len: bytes.len(),
                max: TBD1_REPLY_LEN,
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
        let status = read_u32(bytes, 12);
        let flags = read_u32(bytes, 16);
        for (index, offset) in [20usize, 24, 28].iter().enumerate() {
            let value = read_u32(bytes, *offset);
            if value != 0 {
                return Err(ProtoError::ReservedNotZero {
                    offset: index,
                    value,
                });
            }
        }
        Ok(Tbd1Reply {
            sequence: read_u16(bytes, 6),
            role: read_u32(bytes, 8),
            status,
            flags,
            // Filled in by the caller from fstat on the attached fd.
            size: 0,
        })
    }
}

/// Build a `TBD1` request for an absolute guest path.
///
/// Confirmed by `docs/LOADER_PROTOCOL.md` §8: the payload carries an absolute path
/// of at most 1024 bytes, the frame is exactly 3192 bytes and it is zero-filled
/// before use. Where the path starts inside the frame is **not** confirmed, so the
/// path is placed at the beginning of the payload and the layout is marked
/// provisional by this constructor's name.
pub const TBD1_PATH_MAX: usize = 0x400;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PathError {
    NotAbsolute,
    TooLong(usize),
}

pub fn tbd1_request_for_path(
    sequence: u16,
    role: u32,
    path: &str,
) -> Result<Tbd1, PathError> {
    if !path.starts_with('/') {
        return Err(PathError::NotAbsolute);
    }
    if path.len() > TBD1_PATH_MAX {
        return Err(PathError::TooLong(path.len()));
    }
    let length = path.len() as u32;
    let mut payload = Vec::with_capacity(4 + path.len());
    payload.extend_from_slice(&length.to_le_bytes());
    payload.extend_from_slice(path.as_bytes());
    Tbd1::new(sequence, role, payload).map_err(|_| PathError::TooLong(path.len()))
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