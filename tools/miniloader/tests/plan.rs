/* SPDX-License-Identifier: MPL-2.0 */
//! Tests for the three pure modules. They run on the CI host (x86_64) because
//! nothing here touches AArch64 state: parsing, planning and stack layout are
//! plain data transformations.

use miniloader::elf::{Elf, ElfError, Segment, PT_F_R, PT_F_W, PT_F_X};
use miniloader::map::{plan, prot_for, MapError, PROT_EXEC, PROT_READ, PROT_WRITE};
use miniloader::stack::{build, Auxv, StackError, AT_NULL, AT_PAGESZ};

const PAGE: u64 = 4096;

/// Build a minimal but valid ELF64 aarch64 image with the given PT_LOAD entries.
fn synth_elf(entry: u64, segments: &[(u64, u64, u64, u64, u32)]) -> Vec<u8> {
    let phnum = segments.len();
    let phoff = 64usize;
    let data_start = phoff + 56 * phnum;
    let mut out = vec![0u8; data_start];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2; // ELF64
    out[5] = 1; // little endian
    out[6] = 1; // version
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes()); // aarch64
    out[0x18..0x20].copy_from_slice(&entry.to_le_bytes());
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&(phnum as u16).to_le_bytes());
    for (index, (offset, vaddr, filesz, memsz, flags)) in segments.iter().enumerate() {
        let base = phoff + 56 * index;
        out[base..base + 4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
        out[base + 4..base + 8].copy_from_slice(&flags.to_le_bytes());
        out[base + 8..base + 16].copy_from_slice(&offset.to_le_bytes());
        out[base + 16..base + 24].copy_from_slice(&vaddr.to_le_bytes());
        out[base + 32..base + 40].copy_from_slice(&filesz.to_le_bytes());
        out[base + 40..base + 48].copy_from_slice(&memsz.to_le_bytes());
        out[base + 48..base + 56].copy_from_slice(&PAGE.to_le_bytes());
    }
    // Pad so the file covers the declared offsets/sizes.
    let needed = segments
        .iter()
        .map(|(offset, _, filesz, _, _)| (*offset + *filesz) as usize)
        .max()
        .unwrap_or(0);
    if out.len() < needed {
        out.resize(needed, 0xaa);
    }
    out
}

#[test]
fn parses_a_single_executable_segment() {
    let bytes = synth_elf(0x100, &[(0x100, 0x0, 0x200, 0x400, PT_F_R | PT_F_X)]);
    let image = Elf::parse(&bytes).expect("parse");
    assert_eq!(image.entry, 0x100);
    assert_eq!(image.segments.len(), 1);
    let segment = image.segments[0];
    assert_eq!(segment.filesz, 0x200);
    assert_eq!(segment.bss_len(), 0x200);
    assert!(segment.is_executable());
    assert!(image.entry_is_executable());
    assert_eq!(image.segment_bytes(&segment).len(), 0x200);
}

#[test]
fn rejects_malformed_images() {
    assert!(matches!(Elf::parse(&[0u8; 8]), Err(ElfError::Truncated)));
    let mut bad_magic = synth_elf(0x1000, &[(0x100, 0x0, 0x10, 0x10, PT_F_R)]);
    bad_magic[0] = b'X';
    assert!(matches!(Elf::parse(&bad_magic), Err(ElfError::BadMagic)));

    let mut not_64 = synth_elf(0x1000, &[(0x100, 0x0, 0x10, 0x10, PT_F_R)]);
    not_64[4] = 1;
    assert!(matches!(Elf::parse(&not_64), Err(ElfError::NotElf64)));

    let mut big_endian = synth_elf(0x1000, &[(0x100, 0x0, 0x10, 0x10, PT_F_R)]);
    big_endian[5] = 2;
    assert!(matches!(Elf::parse(&big_endian), Err(ElfError::NotLittleEndian)));

    let mut wrong_machine = synth_elf(0x1000, &[(0x100, 0x0, 0x10, 0x10, PT_F_R)]);
    wrong_machine[0x12..0x14].copy_from_slice(&0x3eu16.to_le_bytes());
    assert!(matches!(
        Elf::parse(&wrong_machine),
        Err(ElfError::NotAarch64(0x3e))
    ));

    let mut wrong_phent = synth_elf(0x1000, &[(0x100, 0x0, 0x10, 0x10, PT_F_R)]);
    wrong_phent[0x36..0x38].copy_from_slice(&0x30u16.to_le_bytes());
    assert!(matches!(
        Elf::parse(&wrong_phent),
        Err(ElfError::BadPhentsize(0x30))
    ));

    // memsz < filesz is refused (would mean a negative bss).
    let inverted = synth_elf(0x1000, &[(0x100, 0x0, 0x400, 0x200, PT_F_R)]);
    assert!(matches!(
        Elf::parse(&inverted),
        Err(ElfError::MemorySmallerThanFile { index: 0 })
    ));

    // A segment claiming more file bytes than the image holds.
    let truncated_segment = synth_elf(0x1000, &[(0x100, 0x0, 0x10, 0x10, PT_F_R)]);
    let mut cut = truncated_segment.clone();
    cut.truncate(0x104);
    assert!(matches!(
        Elf::parse(&cut),
        Err(ElfError::SegmentOutOfFile { index: 0 })
    ));
}

#[test]
fn plans_page_aligned_mappings_with_bss() {
    let segments = [
        Segment {
            offset: 0,
            vaddr: 0x1000,
            filesz: 0x400,
            memsz: 0x900,
            flags: PT_F_R | PT_F_X,
            align: PAGE,
        },
        Segment {
            offset: 0x400,
            vaddr: 0x3100,
            filesz: 0x100,
            memsz: 0x100,
            flags: PT_F_R | PT_F_W,
            align: PAGE,
        },
    ];
    let planned = plan(&segments, 0x1000, PAGE).expect("plan");
    assert_eq!(planned.reserve_start, 0x1000);
    assert_eq!(planned.reserve_end, 0x4000);
    assert_eq!(planned.reserved_len(), 0x3000);
    assert_eq!(planned.maps.len(), 2);
    assert_eq!(planned.maps[0].start, 0x1000);
    assert_eq!(planned.maps[0].end, 0x2000);
    assert_eq!(planned.maps[0].bss_len, 0x500);
    assert_eq!(planned.maps[0].prot, PROT_READ | PROT_EXEC);
    assert_eq!(planned.maps[1].start, 0x3000);
    assert_eq!(planned.maps[1].end, 0x4000);
    assert_eq!(planned.maps[1].prot, PROT_READ | PROT_WRITE);
    assert!(planned.entry_map().is_some());
    assert_eq!(planned.entry_map().map(|m| m.start), Some(0x1000));
}

#[test]
fn plan_rejects_bad_pages_and_empty_inputs() {
    let segment = Segment {
        offset: 0,
        vaddr: 0x1000,
        filesz: 0x10,
        memsz: 0x10,
        flags: PT_F_R,
        align: PAGE,
    };
    assert!(matches!(
        plan(&[segment], 0x1000, 0),
        Err(MapError::PageSizeZero)
    ));
    assert!(matches!(
        plan(&[segment], 0x1000, 3000),
        Err(MapError::PageSizeNotPowerOfTwo(3000))
    ));
    assert!(matches!(plan(&[], 0x1000, PAGE), Err(MapError::EmptySegments)));
}

#[test]
fn prot_flags_map_to_the_expected_bits() {
    assert_eq!(prot_for(0), 0);
    assert_eq!(prot_for(PT_F_R), PROT_READ);
    assert_eq!(prot_for(PT_F_R | PT_F_W), PROT_READ | PROT_WRITE);
    assert_eq!(
        prot_for(PT_F_R | PT_F_W | PT_F_X),
        PROT_READ | PROT_WRITE | PROT_EXEC
    );
}

#[test]
fn stack_layout_is_ordered_aligned_and_terminated() {
    let argv = ["/system/bin/init", "--probe"];
    let env = ["TWOYI_ROOTFS=/data/data/io.twoyi/rootfs", "TWOYI_GUEST_UID=10000"];
    let auxv = [Auxv::new(AT_PAGESZ, PAGE), Auxv::new(25, 0x11223344)];
    let image = build(&argv, &env, &auxv, 1 << 20).expect("stack");

    assert_eq!(image.size % 16, 0, "stack size must be 16-byte aligned");
    assert_eq!(image.layout.argc_offset, 0);
    assert_eq!(image.layout.argv_offset, 8);
    assert!(image.layout.argv_offset < image.layout.envp_offset);
    assert!(image.layout.envp_offset < image.layout.auxv_offset);
    assert!(image.layout.auxv_offset < image.layout.strings_offset);

    let argc = image.slot(image.layout.argc_offset).unwrap();
    assert_eq!(argc, argv.len() as u64);

    // Apply a base address and check the first argv pointer resolves to the text.
    let base = 0x7fff_0000_0000u64;
    let mut image = image;
    image.apply_base(base).unwrap();
    let first = image.slot(image.layout.argv_offset).unwrap();
    assert!(first >= base);
    let offset = (first - base) as usize;
    let text = std::str::from_utf8(&image.bytes[offset..offset + argv[0].len()]).unwrap();
    assert_eq!(text, argv[0]);
    assert_eq!(image.bytes[offset + argv[0].len()], 0, "strings are NUL terminated");

    // envp[0] must point at the first environment string.
    let env0 = image.slot(image.layout.envp_offset).unwrap();
    let env_offset = (env0 - base) as usize;
    let env_text = std::str::from_utf8(&image.bytes[env_offset..env_offset + env[0].len()]).unwrap();
    assert_eq!(env_text, env[0]);

    // auxv must terminate with AT_NULL.
    let last = image.layout.auxv_offset + 16 * auxv.len();
    assert_eq!(image.slot(last).unwrap(), AT_NULL);
}

#[test]
fn stack_rejects_oversized_and_incomplete_inputs() {
    let page = [Auxv::new(AT_PAGESZ, PAGE)];
    let big = "x".repeat(4096);
    let argv = ["init", big.as_str()];
    match build(&argv, &[], &page, 1024) {
        Err(StackError::TooLarge { limit, .. }) => assert_eq!(limit, 1024),
        other => panic!("expected TooLarge, got {other:?}"),
    }
    assert!(matches!(
        build(&["init"], &[], &[], 1 << 20),
        Err(StackError::MissingPageSize)
    ));
}

#[test]
fn empty_argv_is_still_a_valid_image() {
    let image = build(&[], &[], &[Auxv::new(AT_PAGESZ, PAGE)], 1 << 20).expect("stack");
    assert_eq!(image.slot(image.layout.argc_offset).unwrap(), 0);
    assert_eq!(image.size % 16, 0);
}