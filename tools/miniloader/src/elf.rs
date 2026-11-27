/* SPDX-License-Identifier: MPL-2.0 */
//! ELF64 parsing for the guest payload.
//!
//! The official loader validates `\x7fELF`, `EI_CLASS=2`, `EI_DATA=1`,
//! `e_machine=0xB7` and `e_phentsize=0x38` before touching any segment (see
//! `docs/LOADER_RE.md`, functions `0x113a70` / `0x114330`). This module mirrors
//! those checks and produces a segment list that the mapping planner consumes.

pub const EM_AARCH64: u16 = 0xb7;
pub const PHENT_SIZE: u16 = 0x38;

const EI_CLASS: usize = 4;
const EI_DATA: usize = 5;
const EI_CLASS_64: u8 = 2;
const EI_DATA_LSB: u8 = 1;
const PT_LOAD: u32 = 1;

pub const PT_F_X: u32 = 1;
pub const PT_F_W: u32 = 2;
pub const PT_F_R: u32 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Segment {
    pub offset: u64,
    pub vaddr: u64,
    pub filesz: u64,
    pub memsz: u64,
    pub flags: u32,
    pub align: u64,
}

impl Segment {
    pub fn is_executable(&self) -> bool {
        self.flags & PT_F_X != 0
    }

    pub fn bss_len(&self) -> u64 {
        self.memsz - self.filesz
    }

    pub fn vaddr_end(&self) -> u64 {
        self.vaddr + self.memsz
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ElfError {
    Truncated,
    BadMagic,
    NotElf64,
    NotLittleEndian,
    NotAarch64(u16),
    BadPhentsize(u16),
    NoLoadSegments,
    SegmentOutOfFile { index: usize },
    MemorySmallerThanFile { index: usize },
    AddressOverflow,
}

#[derive(Debug, Clone)]
pub struct Elf<'a> {
    pub entry: u64,
    pub segments: Vec<Segment>,
    pub data: &'a [u8],
}

fn read_u16(data: &[u8], offset: usize) -> Option<u16> {
    let slice = data.get(offset..offset + 2)?;
    Some(u16::from_le_bytes([slice[0], slice[1]]))
}

fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    let slice = data.get(offset..offset + 4)?;
    Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

fn read_u64(data: &[u8], offset: usize) -> Option<u64> {
    let slice = data.get(offset..offset + 8)?;
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(slice);
    Some(u64::from_le_bytes(bytes))
}

impl<'a> Elf<'a> {
    /// Parse and validate a guest ELF image held in memory.
    pub fn parse(data: &'a [u8]) -> Result<Elf<'a>, ElfError> {
        if data.len() < 64 {
            return Err(ElfError::Truncated);
        }
        if &data[0..4] != b"\x7fELF" {
            return Err(ElfError::BadMagic);
        }
        if data[EI_CLASS] != EI_CLASS_64 {
            return Err(ElfError::NotElf64);
        }
        if data[EI_DATA] != EI_DATA_LSB {
            return Err(ElfError::NotLittleEndian);
        }
        let machine = read_u16(data, 0x12).ok_or(ElfError::Truncated)?;
        if machine != EM_AARCH64 {
            return Err(ElfError::NotAarch64(machine));
        }
        let entry = read_u64(data, 0x18).ok_or(ElfError::Truncated)?;
        let phoff = read_u64(data, 0x20).ok_or(ElfError::Truncated)? as usize;
        let phentsize = read_u16(data, 0x36).ok_or(ElfError::Truncated)?;
        let phnum = read_u16(data, 0x38).ok_or(ElfError::Truncated)? as usize;
        if phentsize != PHENT_SIZE {
            return Err(ElfError::BadPhentsize(phentsize));
        }

        let mut segments = Vec::new();
        for index in 0..phnum {
            let base = phoff
                .checked_add(index.checked_mul(PHENT_SIZE as usize).ok_or(ElfError::AddressOverflow)?)
                .ok_or(ElfError::AddressOverflow)?;
            if base + PHENT_SIZE as usize > data.len() {
                return Err(ElfError::Truncated);
            }
            let p_type = read_u32(data, base).ok_or(ElfError::Truncated)?;
            if p_type != PT_LOAD {
                continue;
            }
            let offset = read_u64(data, base + 0x08).ok_or(ElfError::Truncated)?;
            let vaddr = read_u64(data, base + 0x10).ok_or(ElfError::Truncated)?;
            let filesz = read_u64(data, base + 0x20).ok_or(ElfError::Truncated)?;
            let memsz = read_u64(data, base + 0x28).ok_or(ElfError::Truncated)?;
            let flags = read_u32(data, base + 0x04).ok_or(ElfError::Truncated)?;
            let align = read_u64(data, base + 0x30).ok_or(ElfError::Truncated)?;
            if memsz < filesz {
                return Err(ElfError::MemorySmallerThanFile { index });
            }
            let file_end = offset
                .checked_add(filesz)
                .ok_or(ElfError::AddressOverflow)?;
            if file_end > data.len() as u64 {
                return Err(ElfError::SegmentOutOfFile { index });
            }
            if vaddr.checked_add(memsz).is_none() {
                return Err(ElfError::AddressOverflow);
            }
            segments.push(Segment {
                offset,
                vaddr,
                filesz,
                memsz,
                flags,
                align,
            });
        }
        if segments.is_empty() {
            return Err(ElfError::NoLoadSegments);
        }
        Ok(Elf {
            entry,
            segments,
            data,
        })
    }

    /// Bytes of a segment that must be copied from the file.
    pub fn segment_bytes(&self, segment: &Segment) -> &'a [u8] {
        let start = segment.offset as usize;
        let end = start + segment.filesz as usize;
        &self.data[start..end]
    }

    /// Same as [`Elf::segment_bytes`] but tolerant of a bad range.
    pub fn segment_bytes_at(&self, segment: &Segment) -> Option<&'a [u8]> {
        segment.file_bytes(self.data)
    }

    /// True when `entry` lands inside an executable segment.
    pub fn entry_is_executable(&self) -> bool {
        self.segments
            .iter()
            .any(|segment| segment.is_executable()
                && self.entry >= segment.vaddr
                && self.entry < segment.vaddr + segment.filesz)
    }
}