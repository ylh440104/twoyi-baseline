/* SPDX-License-Identifier: MPL-2.0 */
//! Dynamic symbols and jump slots.
//!
//! A guest ELF that calls into a shared library leaves those calls as
//! `R_AARCH64_JUMP_SLOT` entries: at `bias + r_offset` the loader must write the
//! address it resolved for the symbol name. Resolving the name is the host's job
//! (the libc bridge) but reading the tables, and knowing which names are needed,
//! belongs to the loader — and both are testable without a device.

use crate::elf::Elf;

const SHT_STRTAB: u32 = 3;
const SHT_RELA: u32 = 4;
const SHT_DYNSYM: u32 = 11;
/// `R_AARCH64_JUMP_SLOT`.
pub const R_AARCH64_JUMP_SLOT: u32 = 1026;

const ELF64_HEADER: usize = 64;
const SECTION_HEADER: usize = 64;
const SYM_ENTRY: usize = 24;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DynSym {
    pub name: String,
    /// Link-time value; zero for an undefined import.
    pub value: u64,
    pub size: u64,
    /// `SHN_UNDEF` when the symbol must be resolved by the host.
    pub undefined: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JumpSlot {
    /// Link-time address of the slot to patch.
    pub offset: u64,
    pub symbol: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SymError {
    BadSectionTable,
    BadDynsym { index: usize },
    BadStrtab { index: usize },
    BadRela { index: usize },
    MissingStringTable,
}

/// Every symbol in `.dynsym`, with its name resolved from the linked string table.
pub fn dynamic_symbols(elf: &Elf<'_>) -> Result<Vec<DynSym>, SymError> {
    let sections = sections(elf)?;
    let mut out = Vec::new();
    for (index, section) in sections.iter().enumerate() {
        if section.sh_type != SHT_DYNSYM {
            continue;
        }
        let strings = sections
            .iter()
            .find(|candidate| candidate.index == section.sh_link as usize)
            .ok_or(SymError::MissingStringTable)?;
        if strings.sh_type != SHT_STRTAB {
            return Err(SymError::MissingStringTable);
        }
        if section.sh_entsize != SYM_ENTRY
            || section.sh_offset + section.sh_size > elf.data.len()
            || strings.sh_offset + strings.sh_size > elf.data.len()
        {
            return Err(SymError::BadDynsym { index });
        }
        let count = section.sh_size / SYM_ENTRY;
        for entry in 0..count {
            let at = section.sh_offset + entry * SYM_ENTRY;
            let st_name = u32_at(elf.data, at).ok_or(SymError::BadDynsym { index })? as usize;
            let st_shndx = u16_at(elf.data, at + 6).ok_or(SymError::BadDynsym { index })?;
            let st_value = u64_at(elf.data, at + 8).ok_or(SymError::BadDynsym { index })?;
            let st_size = u64_at(elf.data, at + 16).ok_or(SymError::BadDynsym { index })?;
            let name = string_at(elf.data, strings.sh_offset, strings.sh_size, st_name)
                .ok_or(SymError::BadDynsym { index })?;
            out.push(DynSym {
                name,
                value: st_value,
                size: st_size,
                undefined: st_shndx == 0,
            });
        }
    }
    Ok(out)
}

/// Every `R_AARCH64_JUMP_SLOT`, paired with the name it must be resolved to.
pub fn jump_slots(elf: &Elf<'_>) -> Result<Vec<JumpSlot>, SymError> {
    let sections = sections(elf)?;
    let mut out = Vec::new();
    for (index, section) in sections.iter().enumerate() {
        if section.sh_type != SHT_RELA {
            continue;
        }
        if section.sh_entsize != 24 || section.sh_offset + section.sh_size > elf.data.len() {
            return Err(SymError::BadRela { index });
        }
        // The symbols this section refers to live in the table it links to.
        let symbols = match sections
            .iter()
            .find(|candidate| candidate.index == section.sh_link as usize)
        {
            Some(table) => table,
            None => continue,
        };
        if symbols.sh_type != SHT_DYNSYM {
            continue;
        }
        let strings = sections
            .iter()
            .find(|candidate| candidate.index == symbols.sh_link as usize)
            .ok_or(SymError::MissingStringTable)?;
        let count = section.sh_size / 24;
        for entry in 0..count {
            let at = section.sh_offset + entry * 24;
            let r_offset = u64_at(elf.data, at).ok_or(SymError::BadRela { index })?;
            let r_info = u64_at(elf.data, at + 8).ok_or(SymError::BadRela { index })?;
            if (r_info & 0xffff_ffff) as u32 != R_AARCH64_JUMP_SLOT {
                continue;
            }
            let symbol_index = (r_info >> 32) as usize;
            let symbol_at = symbols.sh_offset + symbol_index * SYM_ENTRY;
            let st_name = u32_at(elf.data, symbol_at).ok_or(SymError::BadRela { index })? as usize;
            let name = string_at(elf.data, strings.sh_offset, strings.sh_size, st_name)
                .ok_or(SymError::BadRela { index })?;
            out.push(JumpSlot {
                offset: r_offset,
                symbol: name,
            });
        }
    }
    Ok(out)
}

/// Patch the jump slots with addresses supplied by `resolve`.
///
/// `write` receives `(link-time slot address, resolved address)`; returning false
/// aborts, which is how an unresolved import surfaces to the caller instead of
/// silently pointing the guest at address zero.
pub fn apply_jump_slots(
    slots: &[JumpSlot],
    mut resolve: impl FnMut(&str) -> Option<u64>,
    mut write: impl FnMut(u64, u64) -> bool,
) -> Result<usize, SymError> {
    let mut patched = 0;
    for slot in slots {
        let address = match resolve(&slot.symbol) {
            Some(value) => value,
            None => return Err(SymError::BadRela { index: 0 }),
        };
        if !write(slot.offset, address) {
            return Err(SymError::BadRela { index: 0 });
        }
        patched += 1;
    }
    Ok(patched)
}

struct Section {
    index: usize,
    sh_type: u32,
    sh_offset: usize,
    sh_size: usize,
    sh_link: u32,
    sh_entsize: usize,
}

fn sections(elf: &Elf<'_>) -> Result<Vec<Section>, SymError> {
    let data = elf.data;
    if data.len() < ELF64_HEADER {
        return Err(SymError::BadSectionTable);
    }
    let shoff = u64_at(data, 0x28).ok_or(SymError::BadSectionTable)? as usize;
    let shentsize = u16_at(data, 0x3a).ok_or(SymError::BadSectionTable)? as usize;
    let shnum = u16_at(data, 0x3c).ok_or(SymError::BadSectionTable)? as usize;
    if shoff == 0 || shnum == 0 {
        return Ok(Vec::new());
    }
    if shentsize != SECTION_HEADER || shoff + shnum * SECTION_HEADER > data.len() {
        return Err(SymError::BadSectionTable);
    }
    let mut out = Vec::with_capacity(shnum);
    for index in 0..shnum {
        let base = shoff + index * SECTION_HEADER;
        out.push(Section {
            index,
            sh_type: u32_at(data, base + 4).ok_or(SymError::BadSectionTable)?,
            sh_offset: u64_at(data, base + 0x18).ok_or(SymError::BadSectionTable)? as usize,
            sh_size: u64_at(data, base + 0x20).ok_or(SymError::BadSectionTable)? as usize,
            sh_link: u32_at(data, base + 0x28).ok_or(SymError::BadSectionTable)?,
            sh_entsize: u64_at(data, base + 0x38).ok_or(SymError::BadSectionTable)? as usize,
        });
    }
    Ok(out)
}

fn string_at(data: &[u8], table_offset: usize, table_size: usize, index: usize) -> Option<String> {
    if index >= table_size {
        return None;
    }
    let start = table_offset + index;
    let end_limit = table_offset + table_size;
    if start >= end_limit || end_limit > data.len() {
        return None;
    }
    let end = data[start..end_limit].iter().position(|byte| *byte == 0)? + start;
    std::str::from_utf8(&data[start..end]).ok().map(str::to_string)
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