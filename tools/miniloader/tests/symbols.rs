/* SPDX-License-Identifier: MPL-2.0 */
//! Dynamic symbol and jump slot tests.
//!
//! The fixture is a hand-built ELF with `.dynstr`, `.dynsym` and `.rela.plt`, so the
//! reader is exercised against a real section layout rather than a mock.

#![cfg(unix)]

use miniloader::elf::Elf;
use miniloader::symbols::{
    apply_jump_slots, dynamic_symbols, jump_slots, SymError, R_AARCH64_JUMP_SLOT,
};

const PAGE: u64 = 4096;
const SECTION_HEADER: usize = 64;
const SYM_ENTRY: usize = 24;

/// One import: name plus the slot that must be patched.
struct Import<'a> {
    name: &'a str,
    slot_vaddr: u64,
    weak: bool,
}

fn build_elf(imports: &[Import<'_>]) -> Vec<u8> {
    // Layout: header | 1 phdr | .dynstr | .dynsym | .rela.plt | payload | sections
    let phoff = 64usize;
    let phnum = 1usize;
    let dynstr_offset = phoff + 56 * phnum;

    let mut names = Vec::new();
    let mut cursor = 1usize; // index 0 is the empty name
    for import in imports {
        names.push((import.name, cursor, import.weak));
        cursor += import.name.len() + 1;
    }
    // The string table must hold exactly the names used, in that order.
    let mut table = vec![0u8];
    for (name, _, _) in &names {
        table.extend_from_slice(name.as_bytes());
        table.push(0);
    }

    let dynsym_offset = dynstr_offset + table.len();
    // Symbol 0 is the null symbol; imports follow.
    let symbol_count = imports.len() + 1;
    let dynsym_size = symbol_count * SYM_ENTRY;
    let rela_offset = dynsym_offset + dynsym_size;
    let rela_size = imports.len() * 24;
    let payload_offset = rela_offset + rela_size;
    let payload_size = 0x40usize;
    let shoff = (payload_offset + payload_size + 7) & !7usize;
    let shnum = 5usize; // NULL, .dynstr, .dynsym, .rela.plt, reserved
    let total = shoff + shnum * SECTION_HEADER;

    let mut out = vec![0u8; total];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    out[0x18..0x20].copy_from_slice(&0x2000u64.to_le_bytes());
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x28..0x30].copy_from_slice(&(shoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&(phnum as u16).to_le_bytes());
    out[0x3a..0x3c].copy_from_slice(&(SECTION_HEADER as u16).to_le_bytes());
    out[0x3c..0x3e].copy_from_slice(&(shnum as u16).to_le_bytes());

    // PT_LOAD covering the payload page so the image can be mapped.
    let ph = phoff;
    out[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes());
    out[ph + 4..ph + 8].copy_from_slice(&6u32.to_le_bytes()); // R|W
    out[ph + 8..ph + 16].copy_from_slice(&(payload_offset as u64).to_le_bytes());
    out[ph + 16..ph + 24].copy_from_slice(&0x2000u64.to_le_bytes());
    out[ph + 32..ph + 40].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 40..ph + 48].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 48..ph + 56].copy_from_slice(&PAGE.to_le_bytes());

    // .dynstr
    out[dynstr_offset..dynstr_offset + table.len()].copy_from_slice(&table);

    // .dynsym: null symbol then the imports, each undefined (shndx 0).
    for (index, (_, name_offset, weak_flag)) in names.iter().enumerate() {
        let at = dynsym_offset + (index + 1) * SYM_ENTRY;
        out[at..at + 4].copy_from_slice(&(*name_offset as u32).to_le_bytes());
        let binding: u8 = if *weak_flag { 2 } else { 1 }; // STB_WEAK | STB_GLOBAL
        out[at + 4] = (binding << 4) | 0x2; // function
        out[at + 6..at + 8].copy_from_slice(&0u16.to_le_bytes());
    }

    // .rela.plt: one JUMP_SLOT per import, symbol index = position + 1.
    for (index, import) in imports.iter().enumerate() {
        let at = rela_offset + index * 24;
        out[at..at + 8].copy_from_slice(&import.slot_vaddr.to_le_bytes());
        let r_info = ((index as u64 + 1) << 32) | R_AARCH64_JUMP_SLOT as u64;
        out[at + 8..at + 16].copy_from_slice(&r_info.to_le_bytes());
    }

    // Section headers: 1 = .dynstr, 2 = .dynsym (links to 1), 3 = .rela.plt (links to 2).
    let s1 = shoff + SECTION_HEADER;
    out[s1 + 4..s1 + 8].copy_from_slice(&3u32.to_le_bytes()); // SHT_STRTAB
    out[s1 + 0x18..s1 + 0x20].copy_from_slice(&(dynstr_offset as u64).to_le_bytes());
    out[s1 + 0x20..s1 + 0x28].copy_from_slice(&(table.len() as u64).to_le_bytes());

    let s2 = shoff + 2 * SECTION_HEADER;
    out[s2 + 4..s2 + 8].copy_from_slice(&11u32.to_le_bytes()); // SHT_DYNSYM
    out[s2 + 0x18..s2 + 0x20].copy_from_slice(&(dynsym_offset as u64).to_le_bytes());
    out[s2 + 0x20..s2 + 0x28].copy_from_slice(&(dynsym_size as u64).to_le_bytes());
    out[s2 + 0x28..s2 + 0x2c].copy_from_slice(&1u32.to_le_bytes()); // sh_link = .dynstr
    out[s2 + 0x38..s2 + 0x40].copy_from_slice(&(SYM_ENTRY as u64).to_le_bytes());

    let s3 = shoff + 3 * SECTION_HEADER;
    out[s3 + 4..s3 + 8].copy_from_slice(&4u32.to_le_bytes()); // SHT_RELA
    out[s3 + 0x18..s3 + 0x20].copy_from_slice(&(rela_offset as u64).to_le_bytes());
    out[s3 + 0x20..s3 + 0x28].copy_from_slice(&(rela_size as u64).to_le_bytes());
    out[s3 + 0x28..s3 + 0x2c].copy_from_slice(&2u32.to_le_bytes()); // sh_link = .dynsym
    out[s3 + 0x38..s3 + 0x40].copy_from_slice(&24u64.to_le_bytes());

    out
}

#[test]
fn reads_dynamic_symbols() {
    let bytes = build_elf(&[
        Import {
            name: "write",
            slot_vaddr: 0x2000,
            weak: false,
        },
        Import {
            name: "close",
            slot_vaddr: 0x2008,
            weak: false,
        },
    ]);
    let elf = Elf::parse(&bytes).expect("parse");
    let symbols = dynamic_symbols(&elf).expect("symbols");
    // The null symbol comes first, then the two imports in order.
    assert_eq!(symbols.len(), 3);
    assert_eq!(symbols[0].name, "");
    assert_eq!(symbols[1].name, "write");
    assert_eq!(symbols[2].name, "close");
    assert!(symbols[1].undefined, "imports are undefined");
}

#[test]
fn reads_jump_slots_with_their_names() {
    let bytes = build_elf(&[
        Import {
            name: "write",
            slot_vaddr: 0x2000,
            weak: false,
        },
        Import {
            name: "close",
            slot_vaddr: 0x2008,
            weak: false,
        },
    ]);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = jump_slots(&elf).expect("slots");
    assert_eq!(slots.len(), 2);
    assert_eq!(slots[0].symbol, "write");
    assert_eq!(slots[0].offset, 0x2000);
    assert_eq!(slots[1].symbol, "close");
    assert_eq!(slots[1].offset, 0x2008);
}

#[test]
fn patches_slots_through_the_resolver() {
    let bytes = build_elf(&[Import {
        name: "write",
        slot_vaddr: 0x2000,
        weak: false,
    }]);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = jump_slots(&elf).expect("slots");
    let mut writes = Vec::new();
    let patched = apply_jump_slots(
        &slots,
        |name| if name == "write" { Some(0xdead_0000) } else { None },
        |offset, value| {
            writes.push((offset, value));
            true
        },
    )
    .expect("patch");
    assert_eq!(patched, 1);
    assert_eq!(writes, vec![(0x2000, 0xdead_0000)]);
}

#[test]
fn an_unresolved_import_is_reported() {
    let bytes = build_elf(&[Import {
        name: "write",
        slot_vaddr: 0x2000,
        weak: false,
    }]);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = jump_slots(&elf).expect("slots");
    let result = apply_jump_slots(&slots, |_| None, |_, _| true);
    assert!(matches!(result, Err(SymError::BadRela { .. })));
}

#[test]
fn a_rejected_write_is_reported() {
    let bytes = build_elf(&[Import {
        name: "write",
        slot_vaddr: 0x2000,
        weak: false,
    }]);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = jump_slots(&elf).expect("slots");
    let result = apply_jump_slots(&slots, |_| Some(1), |_, _| false);
    assert!(matches!(result, Err(SymError::BadRela { .. })));
}

#[test]
fn a_symbol_free_image_reads_as_empty() {
    // The plain synthetic image from the other suites has no section table at all.
    let mut bytes = build_elf(&[]);
    bytes[0x28..0x30].copy_from_slice(&0u64.to_le_bytes());
    let elf = Elf::parse(&bytes).expect("parse");
    assert!(dynamic_symbols(&elf).expect("symbols").is_empty());
    assert!(jump_slots(&elf).expect("slots").is_empty());
}
#[test]
fn the_binding_is_recorded() {
    use miniloader::symbols::Binding;
    let bytes = build_elf(&[
        Import {
            name: "write",
            slot_vaddr: 0x2000,
            weak: false,
        },
        Import {
            name: "__gmon_start__",
            slot_vaddr: 0x2008,
            weak: true,
        },
    ]);
    let elf = Elf::parse(&bytes).expect("parse");
    let symbols = dynamic_symbols(&elf).expect("symbols");
    assert_eq!(symbols[1].binding, Binding::Global);
    assert_eq!(symbols[2].binding, Binding::Weak);
    let slots = jump_slots(&elf).expect("slots");
    assert!(!slots[0].weak);
    assert!(slots[1].weak);
}

#[test]
fn a_weak_import_may_stay_unresolved() {
    // __gmon_start__ is the real-world case: glibc does not export it for dlsym, and
    // a weak undefined symbol is allowed to become zero.
    let bytes = build_elf(&[Import {
        name: "__gmon_start__",
        slot_vaddr: 0x2000,
        weak: true,
    }]);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = jump_slots(&elf).expect("slots");
    let mut writes = Vec::new();
    let patched = apply_jump_slots(&slots, |_| None, |offset, value| {
        writes.push((offset, value));
        true
    })
    .expect("a weak import must not fail the load");
    assert_eq!(patched, 1);
    assert_eq!(writes, vec![(0x2000, 0)], "a weak miss resolves to zero");
}
