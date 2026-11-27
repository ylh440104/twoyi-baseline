/* SPDX-License-Identifier: MPL-2.0 */
//! `R_AARCH64_RELATIVE` tests, including one against a mapped image.
//!
//! A relative relocation is a store of `bias + addend`, so the whole path —
//! parse the `.rela.dyn` section, map, write, read back — runs on any host.

#![cfg(unix)]

use miniloader::elf::{Elf, PT_F_R, PT_F_W};
use miniloader::executor::MappedImage;
use miniloader::reloc::{apply, has_section_table, relative_relocations, Relative, RelocError};

const PAGE: u64 = 4096;
const R_AARCH64_RELATIVE: u32 = 1027;

/// Build an ELF with one data segment and a `.rela.dyn` section holding `entries`.
fn elf_with_relocations(entries: &[(u64, u32, i64)]) -> Vec<u8> {
    let phoff = 64usize;
    let phentsize = 56usize;
    let phnum = 1usize;
    let rela_offset = phoff + phentsize * phnum; // right after the phdrs
    let rela_size = entries.len() * 24;
    let payload_offset = rela_offset + rela_size;
    let payload_size = 0x40usize;
    let shoff = payload_offset + payload_size;
    let shnum = 2usize; // NULL + .rela.dyn
    let total = shoff + shnum * 64;

    let mut out = vec![0u8; total];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    out[0x18..0x20].copy_from_slice(&0x2000u64.to_le_bytes()); // entry
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x28..0x30].copy_from_slice(&(shoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&(phnum as u16).to_le_bytes());
    out[0x3a..0x3c].copy_from_slice(&64u16.to_le_bytes()); // e_shentsize
    out[0x3c..0x3e].copy_from_slice(&(shnum as u16).to_le_bytes());

    // PT_LOAD covering the payload page.
    let ph = phoff;
    out[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes());
    out[ph + 4..ph + 8].copy_from_slice(&(PT_F_R | PT_F_W).to_le_bytes());
    out[ph + 8..ph + 16].copy_from_slice(&(payload_offset as u64).to_le_bytes());
    out[ph + 16..ph + 24].copy_from_slice(&0x2000u64.to_le_bytes());
    out[ph + 32..ph + 40].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 40..ph + 48].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 48..ph + 56].copy_from_slice(&PAGE.to_le_bytes());

    // Relocation entries.
    for (index, (offset, kind, addend)) in entries.iter().enumerate() {
        let at = rela_offset + index * 24;
        out[at..at + 8].copy_from_slice(&offset.to_le_bytes());
        out[at + 8..at + 16].copy_from_slice(&(*kind as u64).to_le_bytes());
        out[at + 16..at + 24].copy_from_slice(&(*addend as u64).to_le_bytes());
    }

    // .rela.dyn section header (index 1; index 0 is the null header).
    let sh = shoff + 64;
    out[sh + 4..sh + 8].copy_from_slice(&4u32.to_le_bytes()); // SHT_RELA
    out[sh + 0x18..sh + 0x20].copy_from_slice(&(rela_offset as u64).to_le_bytes());
    out[sh + 0x20..sh + 0x28].copy_from_slice(&(rela_size as u64).to_le_bytes());
    out[sh + 0x38..sh + 0x40].copy_from_slice(&24u64.to_le_bytes()); // sh_entsize
    out
}

#[test]
fn parses_rela_dyn_entries() {
    let bytes = elf_with_relocations(&[(0x2000, R_AARCH64_RELATIVE, 0x1234)]);
    let elf = Elf::parse(&bytes).expect("parse");
    assert!(has_section_table(&elf));
    let entries = relative_relocations(&elf).expect("relocations");
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].offset, 0x2000);
    assert_eq!(entries[0].addend, 0x1234);
}

#[test]
fn ignores_other_relocation_types() {
    // A jump slot (R_AARCH64_JUMP_SLOT = 1026) must not be treated as relative.
    let bytes = elf_with_relocations(&[(0x2000, 1026, 0x40)]);
    let elf = Elf::parse(&bytes).expect("parse");
    assert!(relative_relocations(&elf).expect("relocations").is_empty());
}

#[test]
fn an_image_without_section_headers_has_no_relocations() {
    // The segment-only synthetic ELF used by the other tests has no section table.
    let mut bytes = elf_with_relocations(&[(0x2000, R_AARCH64_RELATIVE, 0x10)]);
    bytes[0x28..0x30].copy_from_slice(&0u64.to_le_bytes()); // e_shoff = 0
    let elf = Elf::parse(&bytes).expect("parse");
    assert!(!has_section_table(&elf), "no section table");
    assert!(relative_relocations(&elf).expect("relocations").is_empty());
}

#[test]
fn apply_writes_bias_plus_addend() {
    let entries = [
        Relative {
            offset: 0x1000,
            addend: 0x40,
        },
        Relative {
            offset: 0x1008,
            addend: -0x10,
        },
    ];
    let mut writes = Vec::new();
    let applied = apply(&entries, 0x5000, |offset, value| {
        writes.push((offset, value));
        true
    })
    .expect("apply");
    assert_eq!(applied, 2);
    assert_eq!(writes[0], (0x1000, 0x5040));
    // A negative addend still produces the right two's-complement value.
    assert_eq!(writes[1], (0x1008, 0x4ff0));
}

#[test]
fn a_rejected_write_is_reported() {
    let entries = [Relative {
        offset: 0x1000,
        addend: 1,
    }];
    let result = apply(&entries, 0, |_, _| false);
    assert!(matches!(result, Err(RelocError::BadRelaSection { .. })));
}

#[test]
fn the_mapped_image_holds_rebased_pointers() {
    let bytes = elf_with_relocations(&[(0x2000, R_AARCH64_RELATIVE, 0x1234)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let image = MappedImage::map(&elf, PAGE).expect("map");

    // The relocation slot is the first word of the data segment.
    let slot = image.segments()[0].address;
    let stored = image.read(slot, 8).expect("read slot");
    let value = u64::from_le_bytes([
        stored[0], stored[1], stored[2], stored[3], stored[4], stored[5], stored[6], stored[7],
    ]);
    // bias = mapped address of the segment - its link address (0x2000); the slot
    // must then hold bias + addend, i.e. the addend rebased onto this mapping.
    let bias = slot.wrapping_sub(0x2000) as i64;
    let expected = (bias as i128 + 0x1234) as u64;
    assert_eq!(value, expected, "slot holds bias + addend");
}

#[test]
fn mapping_without_relocation_keeps_the_raw_words() {
    let bytes = elf_with_relocations(&[(0x2000, R_AARCH64_RELATIVE, 0x1234)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let image = MappedImage::map_with_relocations(&elf, PAGE, false).expect("map");
    let slot = image.segments()[0].address;
    let stored = image.read(slot, 8).expect("read slot");
    assert!(
        stored.iter().all(|byte| *byte == 0),
        "the slot is untouched when relocation is skipped"
    );
}
/// ELF with a `DT_RELR` table holding `entries`, at link-time address `relr_vaddr`.
fn elf_with_relr(relr_vaddr: u64, entries: &[u64]) -> Vec<u8> {
    let phoff = 64usize;
    let phentsize = 56usize;
    let phnum = 2usize;
    let dynamic_offset = phoff + phentsize * phnum;
    let table_offset = dynamic_offset + 5 * 16; // 3 tags + DT_NULL, rounded up
    let total = table_offset + entries.len() * 8;

    let mut out = vec![0u8; total];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x10..0x12].copy_from_slice(&3u16.to_le_bytes());
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    out[0x18..0x20].copy_from_slice(&0x2000u64.to_le_bytes());
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&(phnum as u16).to_le_bytes());

    // One PT_LOAD covering the whole file at vaddr 0.
    out[phoff..phoff + 4].copy_from_slice(&1u32.to_le_bytes());
    out[phoff + 4..phoff + 8].copy_from_slice(&5u32.to_le_bytes());
    out[phoff + 16..phoff + 24].copy_from_slice(&0u64.to_le_bytes());
    out[phoff + 32..phoff + 40].copy_from_slice(&(total as u64).to_le_bytes());
    out[phoff + 40..phoff + 48].copy_from_slice(&(total as u64).to_le_bytes());
    out[phoff + 48..phoff + 56].copy_from_slice(&8u64.to_le_bytes());

    let dyn_ph = phoff + phentsize;
    out[dyn_ph..dyn_ph + 4].copy_from_slice(&2u32.to_le_bytes());
    out[dyn_ph + 8..dyn_ph + 16].copy_from_slice(&(dynamic_offset as u64).to_le_bytes());
    out[dyn_ph + 16..dyn_ph + 24].copy_from_slice(&(dynamic_offset as u64).to_le_bytes());
    out[dyn_ph + 32..dyn_ph + 40].copy_from_slice(&80u64.to_le_bytes());
    out[dyn_ph + 40..dyn_ph + 48].copy_from_slice(&80u64.to_le_bytes());

    let mut at = dynamic_offset;
    let mut put = |tag: u64, value: u64| {
        out[at..at + 8].copy_from_slice(&tag.to_le_bytes());
        out[at + 8..at + 16].copy_from_slice(&value.to_le_bytes());
        at += 16;
    };
    put(36, relr_vaddr); // DT_RELR
    put(35, (entries.len() * 8) as u64); // DT_RELRSZ
    put(0, 0);
    let _ = &mut at;

    for (index, entry) in entries.iter().enumerate() {
        let at = table_offset + index * 8;
        out[at..at + 8].copy_from_slice(&entry.to_le_bytes());
    }
    out
}

#[test]
fn relr_entries_become_slot_addresses() {
    use miniloader::reloc::relr_relocations;
    // Two direct entries then a bitmap: 0x1000, 0x2000, and bit 2 names 0x2010.
    let entries = [0x1000u64, 0x2000, (1u64 << 2) | 1];
    let bytes = elf_with_relr(0x100, &entries);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = relr_relocations(&elf).expect("relr");
    assert_eq!(slots, vec![0x1000, 0x2000, 0x2010]);
}

#[test]
fn a_relr_bitmap_covers_63_words() {
    use miniloader::reloc::relr_relocations;
    // One address, then a bitmap whose highest bit is set: the last of 63 words.
    let entries = [0x1000u64, (1u64 << 63) | 1];
    let bytes = elf_with_relr(0x100, &entries);
    let elf = Elf::parse(&bytes).expect("parse");
    let slots = relr_relocations(&elf).expect("relr");
    assert_eq!(slots.len(), 2);
    assert_eq!(slots[0], 0x1000);
    assert_eq!(slots[1], 0x1000 + 8 + 62 * 8, "bit 63 names the 63rd word");
}

#[test]
fn an_image_without_relr_has_no_slots() {
    use miniloader::reloc::relr_relocations;
    let bytes = elf_with_relr(0x100, &[]);
    let elf = Elf::parse(&bytes).expect("parse");
    assert!(relr_relocations(&elf).expect("relr").is_empty());
}
