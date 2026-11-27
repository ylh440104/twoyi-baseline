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
/// `R_AARCH64_JUMP_SLOT`: a function import, patched through the PLT.
pub const R_AARCH64_JUMP_SLOT: u32 = 1026;
/// `R_AARCH64_GLOB_DAT`: a data import, patched in the GOT.
///
/// Both are symbol relocations and both must hold the resolved address; missing this
/// one leaves imported *data* — `stdout`, `stderr`, `environ` — pointing at zero, so the
/// guest dereferences null the first time it touches them.
pub const R_AARCH64_GLOB_DAT: u32 = 1025;

const ELF64_HEADER: usize = 64;
const SECTION_HEADER: usize = 64;
const SYM_ENTRY: usize = 24;

/// ELF symbol binding, from `st_info`'s high nibble.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Binding {
    Local,
    Global,
    Weak,
    Other(u8),
}

impl Binding {
    fn from_st_info(st_info: u8) -> Binding {
        match st_info >> 4 {
            0 => Binding::Local,
            1 => Binding::Global,
            2 => Binding::Weak,
            other => Binding::Other(other),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DynSym {
    pub name: String,
    /// Link-time value; zero for an undefined import.
    pub value: u64,
    pub size: u64,
    /// `SHN_UNDEF` when the symbol must be resolved by the host.
    pub undefined: bool,
    pub binding: Binding,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JumpSlot {
    /// Link-time address of the slot to patch.
    pub offset: u64,
    pub symbol: String,
    /// A weak import may legitimately stay unresolved; the loader then writes zero,
    /// which is what a real dynamic linker does. A strong import must resolve.
    pub weak: bool,
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
            let st_info = *elf.data.get(at + 4).ok_or(SymError::BadDynsym { index })?;
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
                binding: Binding::from_st_info(st_info),
            });
        }
    }
    Ok(out)
}

/// Every symbol relocation — `JUMP_SLOT` and `GLOB_DAT` — with the name to resolve.
///
/// The two kinds differ only in what the guest does with the resolved address (call it
/// versus read through it); the loader's job is the same.
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
            let kind = (r_info & 0xffff_ffff) as u32;
            if kind != R_AARCH64_JUMP_SLOT && kind != R_AARCH64_GLOB_DAT {
                continue;
            }
            let symbol_index = (r_info >> 32) as usize;
            let symbol_at = symbols.sh_offset + symbol_index * SYM_ENTRY;
            let st_name = u32_at(elf.data, symbol_at).ok_or(SymError::BadRela { index })? as usize;
            let st_info = *elf.data.get(symbol_at + 4).ok_or(SymError::BadRela { index })?;
            let name = string_at(elf.data, strings.sh_offset, strings.sh_size, st_name)
                .ok_or(SymError::BadRela { index })?;
            out.push(JumpSlot {
                offset: r_offset,
                symbol: name,
                weak: Binding::from_st_info(st_info) == Binding::Weak,
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
            // A weak import that the host lacks resolves to zero, as a real dynamic
            // linker does; a strong one aborts the load.
            None if slot.weak => 0,
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

/// `DT_NEEDED` / `DT_STRTAB` / `DT_STRSZ` from the dynamic section.
const DT_NEEDED: u64 = 1;
const DT_STRTAB: u64 = 5;
const DT_STRSZ: u64 = 10;
const PT_DYNAMIC: u32 = 2;

/// The shared libraries this image asks for, in declaration order.
///
/// A guest `init` that is statically linked needs nothing; a dynamic one names its
/// libraries here. The loader has to know the list before it can resolve any symbol:
/// resolving against the current process alone gives a misleading "missing symbol"
/// answer for everything that lives in a separate library.
pub fn needed_libraries(elf: &Elf<'_>) -> Result<Vec<String>, SymError> {
    let data = elf.data;
    // Find PT_DYNAMIC; its file range holds {tag, value} pairs terminated by DT_NULL.
    let mut dynamic = None;
    {
        let phoff = u64_at(data, 0x20).ok_or(SymError::BadSectionTable)? as usize;
        let phentsize = u16_at(data, 0x36).ok_or(SymError::BadSectionTable)? as usize;
        let phnum = u16_at(data, 0x38).ok_or(SymError::BadSectionTable)? as usize;
        if phentsize != 56 || phoff + phnum * phentsize > data.len() {
            return Err(SymError::BadSectionTable);
        }
        for index in 0..phnum {
            let base = phoff + index * phentsize;
            if u32_at(data, base).ok_or(SymError::BadSectionTable)? != PT_DYNAMIC {
                continue;
            }
            let offset = u64_at(data, base + 0x08).ok_or(SymError::BadSectionTable)? as usize;
            let filesz = u64_at(data, base + 0x20).ok_or(SymError::BadSectionTable)? as usize;
            if offset + filesz > data.len() || filesz % 16 != 0 {
                return Err(SymError::BadSectionTable);
            }
            dynamic = Some((offset, filesz));
            break;
        }
    }
    let (offset, size) = match dynamic {
        Some(range) => range,
        // No dynamic section at all: statically linked, nothing to load.
        None => return Ok(Vec::new()),
    };

    let mut strtab_addr = 0u64;
    let mut strsz = 0u64;
    let mut offsets = Vec::new();
    let entries = size / 16;
    for index in 0..entries {
        let at = offset + index * 16;
        let tag = u64_at(data, at).ok_or(SymError::BadSectionTable)?;
        let value = u64_at(data, at + 8).ok_or(SymError::BadSectionTable)?;
        match tag {
            0 => break, // DT_NULL
            DT_NEEDED => offsets.push(value),
            DT_STRTAB => strtab_addr = value,
            DT_STRSZ => strsz = value,
            _ => {}
        }
    }
    if offsets.is_empty() {
        return Ok(Vec::new());
    }
    if strtab_addr == 0 || strsz == 0 {
        return Err(SymError::MissingStringTable);
    }

    // DT_STRTAB is a link-time address; find the segment that maps it to a file offset.
    let strtab = elf
        .segments
        .iter()
        .find_map(|segment| {
            let end = segment.vaddr.checked_add(segment.filesz)?;
            if strtab_addr >= segment.vaddr && strtab_addr < end {
                Some((segment.offset + (strtab_addr - segment.vaddr)) as usize)
            } else {
                None
            }
        })
        .ok_or(SymError::MissingStringTable)?;

    let mut names = Vec::new();
    for index in offsets {
        let name = string_at(data, strtab, strsz as usize, index as usize)
            .ok_or(SymError::BadDynsym { index: 0 })?;
        if !name.is_empty() {
            names.push(name);
        }
    }
    Ok(names)
}
