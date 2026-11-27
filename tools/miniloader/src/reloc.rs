/* SPDX-License-Identifier: MPL-2.0 */
//! `R_AARCH64_RELATIVE` relocations.
//!
//! A position-independent guest ELF stores link-time addresses and lists the slots
//! that must be rebased as `R_AARCH64_RELATIVE`: at `load_bias + r_offset` the
//! loader writes `load_bias + r_addend`. Doing that is the first relocation a
//! minimal loader must get right, and it needs no AArch64 CPU — only the mapping
//! and a write — so it can be tested on the CI host.
//!
//! The entries live in `SHT_RELA` sections (`.rela.dyn`, `.rela.plt`). The journal
//! of which sections those are is the section header table, which is optional in a
//! stripped executable; a guest image without it simply has no relocations here.

use crate::elf::Elf;

/// `R_AARCH64_RELATIVE`.
pub const R_AARCH64_RELATIVE: u32 = 1027;

const SHT_RELA: u32 = 4;
const SHT_NOBITS: u32 = 8;
const ELF64_HEADER: usize = 64;
const SECTION_HEADER: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Relative {
    /// Virtual address of the slot to rebase.
    pub offset: u64,
    /// Value the slot holds, relative to the load bias.
    pub addend: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RelocError {
    BadSectionTable,
    BadRelaSection { index: usize },
}

/// Every `R_AARCH64_RELATIVE` in the image, in file order.
pub fn relative_relocations(elf: &Elf<'_>) -> Result<Vec<Relative>, RelocError> {
    let data = elf.data;
    if data.len() < ELF64_HEADER {
        return Err(RelocError::BadSectionTable);
    }
    let shoff = u64_at(data, 0x28).ok_or(RelocError::BadSectionTable)? as usize;
    let shentsize = u16_at(data, 0x3a).ok_or(RelocError::BadSectionTable)? as usize;
    let shnum = u16_at(data, 0x3c).ok_or(RelocError::BadSectionTable)? as usize;
    if shoff == 0 || shnum == 0 {
        return Ok(Vec::new());
    }
    if shentsize != SECTION_HEADER || shoff + shnum * SECTION_HEADER > data.len() {
        return Err(RelocError::BadSectionTable);
    }

    let mut out = Vec::new();
    for index in 0..shnum {
        let base = shoff + index * SECTION_HEADER;
        let sh_type = u32_at(data, base + 4).ok_or(RelocError::BadSectionTable)?;
        if sh_type != SHT_RELA {
            continue;
        }
        let sh_offset = u64_at(data, base + 0x18).ok_or(RelocError::BadSectionTable)? as usize;
        let sh_size = u64_at(data, base + 0x20).ok_or(RelocError::BadSectionTable)? as usize;
        let sh_entsize = u64_at(data, base + 0x38).ok_or(RelocError::BadSectionTable)? as usize;
        if sh_entsize != 24 || sh_offset + sh_size > data.len() {
            return Err(RelocError::BadRelaSection { index });
        }
        let count = sh_size / 24;
        for entry in 0..count {
            let at = sh_offset + entry * 24;
            let r_offset = u64_at(data, at).ok_or(RelocError::BadRelaSection { index })?;
            let r_info = u64_at(data, at + 8).ok_or(RelocError::BadRelaSection { index })?;
            let r_addend = u64_at(data, at + 16).ok_or(RelocError::BadRelaSection { index })? as i64;
            // The type occupies the low 32 bits of r_info for ELF64.
            if (r_info & 0xffff_ffff) as u32 != R_AARCH64_RELATIVE {
                continue;
            }
            out.push(Relative {
                offset: r_offset,
                addend: r_addend,
            });
        }
    }
    Ok(out)
}

/// Apply the relocations to an already-mapped image.
///
/// `bias` is the difference between where the image actually landed and the
/// addresses it was linked at; `write` stores a 64-bit value at a guest address.
pub fn apply(
    relocations: &[Relative],
    bias: i64,
    mut write: impl FnMut(u64, u64) -> bool,
) -> Result<usize, RelocError> {
    let mut applied = 0;
    for relocation in relocations {
        let value = (bias as i128 + relocation.addend as i128) as u64;
        if !write(relocation.offset, value) {
            return Err(RelocError::BadRelaSection { index: 0 });
        }
        applied += 1;
    }
    Ok(applied)
}

/// True when a section header table is present at all.
pub fn has_section_table(elf: &Elf<'_>) -> bool {
    if elf.data.len() < ELF64_HEADER {
        return false;
    }
    let shoff = match u64_at(elf.data, 0x28) {
        Some(value) => value,
        None => return false,
    };
    let shnum = match u16_at(elf.data, 0x3c) {
        Some(value) => value,
        None => return false,
    };
    shoff != 0 && shnum != 0
}

fn u16_at(data: &[u8], offset: usize) -> Option<u16> {
    let slice = data.get(offset..offset + 2)?;
    Some(u16::from_le_bytes([slice[0], slice[1]]))
}

fn u32_at(data: &[u8], offset: usize) -> Option<u32> {
    let slice = data.get(offset..offset + 4)?;
    Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

fn u64_at(data: &[u8], offset: usize) -> Option<u64> {
    let slice = data.get(offset..offset + 8)?;
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(slice);
    Some(u64::from_le_bytes(bytes))
}

/// Section types we care about, for readers of this module.
pub fn is_rela(type_code: u32) -> bool {
    type_code == SHT_RELA
}

/// Whether a section carries no file bytes; exposed so tests can assert the parse
/// did not try to read it.
pub fn is_nobits(type_code: u32) -> bool {
    type_code == SHT_NOBITS
}