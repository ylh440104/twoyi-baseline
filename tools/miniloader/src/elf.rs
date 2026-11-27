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

/// The thread-local storage an image asks for (`PT_TLS`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TlsImage {
    /// Link-time address of the initialisation image.
    pub vaddr: u64,
    /// Bytes to copy from the file.
    pub filesz: u64,
    /// Bytes to reserve; the tail past `filesz` starts zeroed.
    pub memsz: u64,
    pub align: u64,
}

/// The two ELF object types the loader distinguishes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ElfType {
    /// Position independent: any base works, because every pointer is relocated.
    Dyn,
    /// Fixed addresses: the image must be mapped exactly where it was linked.
    Exec,
    Other(u16),
}

impl ElfType {
    fn from_raw(value: u16) -> ElfType {
        match value {
            2 => ElfType::Exec,
            3 => ElfType::Dyn,
            other => ElfType::Other(other),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Elf<'a> {
    pub entry: u64,
    pub segments: Vec<Segment>,
    pub data: &'a [u8],
    /// Virtual address of the program header table, when it is inside a mapped
    /// segment. The guest linker is handed this as `AT_PHDR`.
    pub phdr_vaddr: Option<u64>,
    pub phdr_entry_size: u16,
    pub phdr_count: u16,
    /// `PT_TLS`, when the image uses thread-local storage.
    pub tls: Option<TlsImage>,
    pub elf_type: ElfType,
    /// Relocation table kinds the dynamic section declares.
    ///
    /// Android packs relocations (`DT_ANDROID_REL`), and newer links use `DT_RELR`;
    /// neither is a plain `DT_RELA` table, so knowing which one is present decides
    /// whether the loader can relocate the image at all.
    pub relocation_tables: RelocTables,
}

/// Which relocation tables the dynamic section names.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RelocTables {
    /// Plain `DT_RELA`/`DT_RELASZ`.
    pub rela: bool,
    /// `DT_RELR`/`DT_RELRSZ` (relative-only, compressed).
    pub relr: bool,
    /// `DT_ANDROID_REL`/`DT_ANDROID_RELSZ` (Android packed format).
    pub android_packed: bool,
}

impl RelocTables {
    /// A plain table the loader can walk, without any packed format.
    pub fn has_readable_table(&self) -> bool {
        self.rela && !self.android_packed
    }
}

const DT_RELA: u64 = 7;
const DT_RELASZ: u64 = 8;
const DT_RELR: u64 = 36;
const DT_RELRSZ: u64 = 35;
const DT_ANDROID_REL: u64 = 0x6000_0001;
const DT_ANDROID_RELSZ: u64 = 0x6000_0002;

fn read_relocation_tables(data: &[u8]) -> RelocTables {
    let mut tables = RelocTables::default();
    let phoff = read_u64(data, 0x20).unwrap_or(0) as usize;
    let phentsize = read_u16(data, 0x36).unwrap_or(0) as usize;
    let phnum = read_u16(data, 0x38).unwrap_or(0) as usize;
    if phentsize != 56 || phoff + phnum * phentsize > data.len() {
        return tables;
    }
    for index in 0..phnum {
        let base = phoff + index * phentsize;
        if read_u32(data, base) != Some(2) {
            continue;
        }
        let offset = read_u64(data, base + 0x08).unwrap_or(0) as usize;
        let size = read_u64(data, base + 0x20).unwrap_or(0) as usize;
        if offset + size > data.len() {
            return tables;
        }
        let mut rela_size = 0u64;
        let mut relr_size = 0u64;
        let mut android_size = 0u64;
        for entry in 0..size / 16 {
            let at = offset + entry * 16;
            let Some(tag) = read_u64(data, at) else { break };
            let value = read_u64(data, at + 8).unwrap_or(0);
            match tag {
                0 => break,
                DT_RELA => tables.rela = true,
                DT_RELASZ => rela_size = value,
                DT_RELR => tables.relr = true,
                DT_RELRSZ => relr_size = value,
                DT_ANDROID_REL => tables.android_packed = true,
                DT_ANDROID_RELSZ => android_size = value,
                _ => {}
            }
        }
        tables.rela = tables.rela && rela_size > 0;
        tables.relr = tables.relr && relr_size > 0;
        tables.android_packed = tables.android_packed && android_size > 0;
        break;
    }
    tables
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
        let phnum_raw = read_u16(data, 0x38).ok_or(ElfError::Truncated)?;
        let phnum = phnum_raw as usize;
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
        // `e_phoff` is a file offset; the program headers only have a virtual address
        // if some PT_LOAD covers that file range (normally the first segment does).
        let phdr_offset = read_u64(data, 0x20).unwrap_or(0);
        let phdr_vaddr = segments.iter().find_map(|segment| {
            let start = segment.offset;
            let end = segment.offset.checked_add(segment.filesz).unwrap_or(u64::MAX);
            if phdr_offset >= start && phdr_offset < end {
                Some(segment.vaddr + (phdr_offset - start))
            } else {
                None
            }
        });
        // PT_TLS (type 7) is not a loadable segment, so it is read separately.
        let mut tls = None;
        {
            let phoff = read_u64(data, 0x20).unwrap_or(0) as usize;
            for index in 0..phnum {
                let base = phoff + index * phentsize as usize;
                let Some(kind) = read_u32(data, base) else { break };
                if kind != 7 {
                    continue;
                }
                tls = Some(TlsImage {
                    vaddr: read_u64(data, base + 0x10).unwrap_or(0),
                    filesz: read_u64(data, base + 0x20).unwrap_or(0),
                    memsz: read_u64(data, base + 0x28).unwrap_or(0),
                    align: read_u64(data, base + 0x30).unwrap_or(0),
                });
                break;
            }
        }
        let relocation_tables = read_relocation_tables(data);
        Ok(Elf {
            entry,
            segments,
            data,
            tls,
            elf_type: ElfType::from_raw(read_u16(data, 0x10).unwrap_or(0)),
            relocation_tables,
            phdr_vaddr,
            phdr_entry_size: phentsize,
            phdr_count: phnum_raw,
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