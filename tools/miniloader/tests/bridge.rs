/* SPDX-License-Identifier: MPL-2.0 */
//! Host symbol bridge tests: the table's rules, and a real mapping whose jump slot
//! is patched from it.

#![cfg(unix)]

use miniloader::bridge::{require_all, BridgeError, SymbolTable, KNOWN_REQUIRED};
use miniloader::elf::Elf;
use miniloader::executor::{ExecError, MappedImage};
use miniloader::symbols::R_AARCH64_JUMP_SLOT;

const PAGE: u64 = 4096;
const SECTION_HEADER: usize = 64;
const SYM_ENTRY: usize = 24;

/// ELF with one `JUMP_SLOT` for `import_name` pointing at `slot_vaddr`.
fn elf_with_import(import_name: &str, slot_vaddr: u64) -> Vec<u8> {
    let phoff = 64usize;
    let dynstr_offset = phoff + 56;
    let mut table = vec![0u8];
    let name_offset = table.len();
    table.extend_from_slice(import_name.as_bytes());
    table.push(0);

    let dynsym_offset = dynstr_offset + table.len();
    let dynsym_size = 2 * SYM_ENTRY; // null symbol + the import
    let rela_offset = dynsym_offset + dynsym_size;
    let rela_size = 24;
    let payload_offset = rela_offset + rela_size;
    let payload_size = 0x40usize;
    let shoff = (payload_offset + payload_size + 7) & !7usize;
    let shnum = 5usize;
    let total = shoff + shnum * SECTION_HEADER;

    let mut out = vec![0u8; total];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    out[0x18..0x20].copy_from_slice(&slot_vaddr.to_le_bytes());
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x28..0x30].copy_from_slice(&(shoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes());
    out[0x3a..0x3c].copy_from_slice(&(SECTION_HEADER as u16).to_le_bytes());
    out[0x3c..0x3e].copy_from_slice(&(shnum as u16).to_le_bytes());

    // PT_LOAD (R|W) spanning the payload page at 0x2000.
    let ph = phoff;
    out[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes());
    out[ph + 4..ph + 8].copy_from_slice(&6u32.to_le_bytes());
    out[ph + 8..ph + 16].copy_from_slice(&(payload_offset as u64).to_le_bytes());
    out[ph + 16..ph + 24].copy_from_slice(&0x2000u64.to_le_bytes());
    out[ph + 32..ph + 40].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 40..ph + 48].copy_from_slice(&(payload_size as u64).to_le_bytes());
    out[ph + 48..ph + 56].copy_from_slice(&PAGE.to_le_bytes());

    out[dynstr_offset..dynstr_offset + table.len()].copy_from_slice(&table);

    let sym = dynsym_offset + SYM_ENTRY; // skip the null symbol
    out[sym..sym + 4].copy_from_slice(&(name_offset as u32).to_le_bytes());
    out[sym + 4] = 0x12;

    out[rela_offset..rela_offset + 8].copy_from_slice(&slot_vaddr.to_le_bytes());
    let r_info = (1u64 << 32) | R_AARCH64_JUMP_SLOT as u64;
    out[rela_offset + 8..rela_offset + 16].copy_from_slice(&r_info.to_le_bytes());

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

#[test]
fn the_table_refuses_empty_names_and_zero_addresses() {
    let mut table = SymbolTable::new();
    assert!(matches!(table.insert("", 1), Err(BridgeError::EmptyName)));
    assert!(matches!(
        table.insert("write", 0),
        Err(BridgeError::ZeroAddress { .. })
    ));
    assert!(table.insert("write", 0x1234).is_ok());
    assert_eq!(table.resolve("write"), Some(0x1234));
    assert_eq!(table.resolve("close"), None);
    assert_eq!(table.len(), 1);
    assert!(!table.is_empty());
}

#[test]
fn missing_reports_the_required_names_the_table_lacks() {
    let mut table = SymbolTable::new();
    table.insert("__android_log_print", 0x10).unwrap();
    let missing = table.missing(KNOWN_REQUIRED);
    assert!(missing.contains(&"android_set_application_target_sdk_version"));
    assert!(!missing.contains(&"__android_log_print"));
    // require_all turns the first missing name into a bridge error.
    let names: Vec<String> = KNOWN_REQUIRED.iter().map(|name| (*name).to_string()).collect();
    match require_all(&table, &names) {
        Err(BridgeError::Unresolved { name }) => {
            assert_eq!(name, "android_set_application_target_sdk_version")
        }
        other => panic!("expected an unresolved bridge symbol, got {other:?}"),
    }
}

#[test]
fn the_current_process_provides_some_symbols() {
    // These exist in every C runtime, so the bridge must find them here.
    let mut table = SymbolTable::new();
    let found = table.from_current_process(&["malloc", "free", "memcpy"]);
    assert_eq!(found.len(), 3, "found {found:?}");
    for name in ["malloc", "free", "memcpy"] {
        assert!(table.resolve(name).is_some(), "{name} resolved");
    }
    // A name that does not exist stays absent rather than being invented.
    let absent = table.from_current_process(&["twoyi_definitely_not_a_symbol"]);
    assert!(absent.is_empty());
    assert!(table.resolve("twoyi_definitely_not_a_symbol").is_none());
}

#[test]
fn a_mapped_slot_holds_the_bridge_address() {
    let bytes = elf_with_import("malloc", 0x2000);
    let elf = Elf::parse(&bytes).expect("parse");
    let mut table = SymbolTable::new();
    table.insert("malloc", 0xdead_0000).unwrap();

    let image = MappedImage::map_with_bridge(&elf, PAGE, true, Some(&table)).expect("map");
    let slot = image.segments()[0].address;
    let stored = image.read(slot, 8).expect("read slot");
    let value = u64::from_le_bytes([
        stored[0], stored[1], stored[2], stored[3], stored[4], stored[5], stored[6], stored[7],
    ]);
    assert_eq!(value, 0xdead_0000, "the jump slot holds the bridge address");
}

#[test]
fn an_unresolved_import_fails_the_load() {
    let bytes = elf_with_import("malloc", 0x2000);
    let elf = Elf::parse(&bytes).expect("parse");
    let table = SymbolTable::new(); // empty on purpose
    match MappedImage::map_with_bridge(&elf, PAGE, true, Some(&table)) {
        Err(ExecError::Unresolved { name }) => assert_eq!(name, "malloc"),
        other => panic!("expected an unresolved symbol, got {:?}", other.map(|_| ())),
    }
}

#[test]
fn without_a_bridge_the_slot_is_left_alone() {
    let bytes = elf_with_import("malloc", 0x2000);
    let elf = Elf::parse(&bytes).expect("parse");
    let image = MappedImage::map(&elf, PAGE).expect("map");
    let slot = image.segments()[0].address;
    let stored = image.read(slot, 8).expect("read slot");
    assert!(
        stored.iter().all(|byte| *byte == 0),
        "without a bridge the slot stays as the file had it"
    );
}