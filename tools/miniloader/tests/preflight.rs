/* SPDX-License-Identifier: MPL-2.0 */
//! Preflight tests: the verdict for good, weak-only and broken images.

use miniloader::bridge::SymbolTable;
use miniloader::elf::{Elf, PT_F_R, PT_F_W, PT_F_X};
use miniloader::preflight::{preflight, Blocker};
use miniloader::symbols::R_AARCH64_JUMP_SLOT;

const PAGE: u64 = 4096;
const SECTION_HEADER: usize = 64;
const SYM_ENTRY: usize = 24;

/// Image with `imports` as `(name, weak)` and one JUMP_SLOT each at 0x2000+.
fn elf_with_imports(imports: &[(&str, bool)]) -> Vec<u8> {
    let phoff = 64usize;
    let dynstr_offset = phoff + 56;

    let mut table = vec![0u8];
    let mut offsets = Vec::new();
    for (name, _) in imports {
        offsets.push(table.len());
        table.extend_from_slice(name.as_bytes());
        table.push(0);
    }

    let dynsym_offset = dynstr_offset + table.len();
    let symbol_count = imports.len() + 1;
    let dynsym_size = symbol_count * SYM_ENTRY;
    let rela_offset = dynsym_offset + dynsym_size;
    let rela_size = imports.len() * 24;
    let payload_offset = rela_offset + rela_size;
    let payload_size = 0x80usize;
    let shoff = (payload_offset + payload_size + 7) & !7usize;
    let shnum = 5usize;
    let total = shoff + shnum * SECTION_HEADER;

    let mut out = vec![0u8; total];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    out[0x18..0x20].copy_from_slice(&0x2000u64.to_le_bytes()); // entry inside the segment
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x28..0x30].copy_from_slice(&(shoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes());
    out[0x3a..0x3c].copy_from_slice(&(SECTION_HEADER as u16).to_le_bytes());
    out[0x3c..0x3e].copy_from_slice(&(shnum as u16).to_le_bytes());

    let ph = phoff;
    out[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes());
    out[ph + 4..ph + 8].copy_from_slice(&(PT_F_R | PT_F_W | PT_F_X).to_le_bytes());
    out[ph + 8..ph + 16].copy_from_slice(&(payload_offset as u64).to_le_bytes());
    out[ph + 16..ph + 24].copy_from_slice(&0x2000u64.to_le_bytes());
    out[ph + 32..ph + 40].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 40..ph + 48].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 48..ph + 56].copy_from_slice(&PAGE.to_le_bytes());

    out[dynstr_offset..dynstr_offset + table.len()].copy_from_slice(&table);

    for (index, (_, weak)) in imports.iter().enumerate() {
        let at = dynsym_offset + (index + 1) * SYM_ENTRY;
        out[at..at + 4].copy_from_slice(&(offsets[index] as u32).to_le_bytes());
        out[at + 4] = ((if *weak { 2 } else { 1 }) << 4) | 0x2;
    }

    for (index, _) in imports.iter().enumerate() {
        let at = rela_offset + index * 24;
        let slot = 0x2000 + (index as u64) * 8;
        out[at..at + 8].copy_from_slice(&slot.to_le_bytes());
        let r_info = ((index as u64 + 1) << 32) | R_AARCH64_JUMP_SLOT as u64;
        out[at + 8..at + 16].copy_from_slice(&r_info.to_le_bytes());
    }

    let s1 = shoff + SECTION_HEADER;
    out[s1 + 4..s1 + 8].copy_from_slice(&3u32.to_le_bytes());
    out[s1 + 0x18..s1 + 0x20].copy_from_slice(&(dynstr_offset as u64).to_le_bytes());
    out[s1 + 0x20..s1 + 0x28].copy_from_slice(&(table.len() as u64).to_le_bytes());

    let s2 = shoff + 2 * SECTION_HEADER;
    out[s2 + 4..s2 + 8].copy_from_slice(&11u32.to_le_bytes());
    out[s2 + 0x18..s2 + 0x20].copy_from_slice(&(dynsym_offset as u64).to_le_bytes());
    out[s2 + 0x20..s2 + 0x28].copy_from_slice(&(dynsym_size as u64).to_le_bytes());
    out[s2 + 0x28..s2 + 0x2c].copy_from_slice(&1u32.to_le_bytes());
    out[s2 + 0x38..s2 + 0x40].copy_from_slice(&(SYM_ENTRY as u64).to_le_bytes());

    let s3 = shoff + 3 * SECTION_HEADER;
    out[s3 + 4..s3 + 8].copy_from_slice(&4u32.to_le_bytes());
    out[s3 + 0x18..s3 + 0x20].copy_from_slice(&(rela_offset as u64).to_le_bytes());
    out[s3 + 0x20..s3 + 0x28].copy_from_slice(&(rela_size as u64).to_le_bytes());
    out[s3 + 0x28..s3 + 0x2c].copy_from_slice(&2u32.to_le_bytes());
    out[s3 + 0x38..s3 + 0x40].copy_from_slice(&24u64.to_le_bytes());

    out
}

fn bridge_with(names: &[(&str, u64)]) -> SymbolTable {
    let mut table = SymbolTable::new();
    for (name, address) in names {
        table.insert(name, *address).unwrap();
    }
    table
}

#[test]
fn an_image_whose_imports_are_served_is_loadable() {
    let bytes = elf_with_imports(&[("malloc", false), ("free", false)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let bridge = bridge_with(&[("malloc", 0x1000), ("free", 0x2000)]);
    let verdict = preflight(&elf, &bridge, PAGE);

    assert!(verdict.is_loadable(), "reason: {}", verdict.reason());
    assert_eq!(verdict.segments, 1);
    assert_eq!(verdict.imports.len(), 2);
    assert_eq!(verdict.relative_relocations, 0);
    assert_eq!(verdict.reserved_bytes, PAGE);
    assert!(verdict.unresolved().is_empty());
    assert_eq!(verdict.reason(), "loadable");
}

#[test]
fn a_missing_strong_import_blocks_the_load() {
    let bytes = elf_with_imports(&[("malloc", false)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let verdict = preflight(&elf, &SymbolTable::new(), PAGE);

    assert!(!verdict.is_loadable());
    assert_eq!(
        verdict.blockers,
        vec![Blocker::MissingImport {
            name: "malloc".to_string()
        }]
    );
    assert!(verdict.reason().contains("required host libc bridge symbol is unavailable: malloc"));
}

#[test]
fn a_missing_weak_import_does_not_block() {
    // __gmon_start__ is the real-world weak import; it may stay unresolved.
    let bytes = elf_with_imports(&[("__gmon_start__", true)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let verdict = preflight(&elf, &SymbolTable::new(), PAGE);

    assert!(verdict.is_loadable(), "reason: {}", verdict.reason());
    assert_eq!(verdict.imports.len(), 1);
    assert!(verdict.imports[0].weak);
    assert_eq!(verdict.imports[0].address, None);
    assert!(verdict.imports[0].is_acceptable());
    // It is still reported as unresolved so a caller can log it.
    assert_eq!(verdict.unresolved().len(), 1);
}

#[test]
fn an_entry_outside_executable_segments_blocks() {
    // Push the entry outside the (executable) segment so it cannot run.
    let mut bytes = elf_with_imports(&[]);
    bytes[0x18..0x20].copy_from_slice(&0x9000u64.to_le_bytes());
    let elf = Elf::parse(&bytes).expect("parse");
    let verdict = preflight(&elf, &SymbolTable::new(), PAGE);
    assert!(!verdict.is_loadable());
    assert!(verdict
        .blockers
        .iter()
        .any(|blocker| matches!(blocker, Blocker::EntryNotExecutable { .. })));
}

#[test]
fn duplicate_imports_appear_once() {
    let bytes = elf_with_imports(&[("malloc", false), ("malloc", false)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let bridge = bridge_with(&[("malloc", 0x1000)]);
    let verdict = preflight(&elf, &bridge, PAGE);
    assert_eq!(verdict.imports.len(), 1, "the same name is reported once");
    assert!(verdict.is_loadable());
}

#[test]
fn bindings_are_classified() {
    let bytes = elf_with_imports(&[("malloc", false), ("__gmon_start__", true)]);
    let elf = Elf::parse(&bytes).expect("parse");
    assert!(miniloader::preflight::bindings_are_known(&elf));
}