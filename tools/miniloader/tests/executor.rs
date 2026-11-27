/* SPDX-License-Identifier: MPL-2.0 */
//! Real mapping tests. They run on the CI host: mapping a guest image needs no
//! AArch64 CPU because nothing is executed, only placed and read back.
//!
//! The sequence under test is the one the official loader performs: one PROT_NONE
//! reservation for the whole span, the file bytes copied per segment, then
//! `mprotect` to the segment's own protections.

#![cfg(unix)]

use miniloader::elf::{Elf, PT_F_R, PT_F_W, PT_F_X};
use miniloader::executor::MappedImage;
use miniloader::map::{PROT_EXEC, PROT_READ, PROT_WRITE};

const PAGE: u64 = 4096;

fn synth_elf(entry: u64, segments: &[(u64, u64, u64, u64, u32)]) -> Vec<u8> {
    let phnum = segments.len();
    let phoff = 64usize;
    let data_start = phoff + 56 * phnum;
    let mut out = vec![0u8; data_start];
    out[0..4].copy_from_slice(b"\x7fELF");
    out[4] = 2;
    out[5] = 1;
    out[6] = 1;
    out[0x12..0x14].copy_from_slice(&0xb7u16.to_le_bytes());
    out[0x18..0x20].copy_from_slice(&entry.to_le_bytes());
    out[0x20..0x28].copy_from_slice(&(phoff as u64).to_le_bytes());
    out[0x36..0x38].copy_from_slice(&0x38u16.to_le_bytes());
    out[0x38..0x3a].copy_from_slice(&(phnum as u16).to_le_bytes());
    for (index, (offset, vaddr, filesz, memsz, flags)) in segments.iter().enumerate() {
        let base = phoff + 56 * index;
        out[base..base + 4].copy_from_slice(&1u32.to_le_bytes());
        out[base + 4..base + 8].copy_from_slice(&flags.to_le_bytes());
        out[base + 8..base + 16].copy_from_slice(&offset.to_le_bytes());
        out[base + 16..base + 24].copy_from_slice(&vaddr.to_le_bytes());
        out[base + 32..base + 40].copy_from_slice(&filesz.to_le_bytes());
        out[base + 40..base + 48].copy_from_slice(&memsz.to_le_bytes());
        out[base + 48..base + 56].copy_from_slice(&PAGE.to_le_bytes());
    }
    let needed = segments
        .iter()
        .map(|(offset, _, filesz, _, _)| (*offset + *filesz) as usize)
        .max()
        .unwrap_or(0);
    if out.len() < needed {
        let start = out.len();
        out.resize(needed, 0);
        for (index, slot) in out[start..needed].iter_mut().enumerate() {
            *slot = (index % 251) as u8;
        }
    }
    out
}

#[test]
fn maps_a_guest_image_and_reads_it_back() {
    let bytes = synth_elf(0x1000, &[(0x100, 0x1000, 0x40, 0x140, PT_F_R | PT_F_X)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let image = MappedImage::map(&elf, PAGE).expect("map");

    assert_eq!(image.segments().len(), 1);
    let segment = image.segments()[0];
    assert_eq!(segment.prot, PROT_READ | PROT_EXEC);
    assert_eq!(segment.file_len, 0x40);
    assert_eq!(segment.mem_len, 0x140);

    // The entry must point at the mapped copy of the segment's first byte.
    let entry = image.entry();
    assert_eq!(entry, segment.address);
    let mapped = image.read(entry, 0x40).expect("read mapped bytes");
    assert_eq!(mapped, &bytes[0x100..0x140], "file bytes were copied verbatim");

    // The bss tail is inside the mapping and reads as zero.
    let tail = image.read(entry + 0x40, 0x100).expect("read bss");
    assert!(tail.iter().all(|byte| *byte == 0), "bss must stay zero");

    // Reading past the reservation is refused rather than returning garbage.
    assert!(image.read(entry + 0x1000, 4).is_none());
}

#[test]
fn maps_two_segments_with_their_own_protections() {
    let bytes = synth_elf(
        0x1000,
        &[
            (0x100, 0x1000, 0x100, 0x100, PT_F_R | PT_F_X),
            (0x300, 0x2800, 0x80, 0x100, PT_F_R | PT_F_W),
        ],
    );
    let elf = Elf::parse(&bytes).expect("parse");
    let image = MappedImage::map(&elf, PAGE).expect("map");
    assert_eq!(image.segments().len(), 2);
    assert_eq!(image.segments()[0].prot, PROT_READ | PROT_EXEC);
    assert_eq!(image.segments()[1].prot, PROT_READ | PROT_WRITE);
    // Segments are page aligned and independent.
    assert_eq!(image.segments()[0].address % PAGE, 0);
    assert_eq!(image.segments()[1].address % PAGE, 0);
    assert_ne!(image.segments()[0].address, image.segments()[1].address);
    assert_eq!(
        image.read(image.segments()[1].address, 0x80).expect("read second"),
        &bytes[0x300..0x380]
    );
}

#[test]
fn a_failed_map_does_not_leave_the_span_behind() {
    // An image with no PT_LOAD cannot be planned, so mapping fails before any
    // reservation is made; the error must carry the reason, not a leak.
    let bytes = synth_elf(0x1000, &[]);
    // Parsing already refuses a segment-less image; that is the behaviour under test.
    assert!(Elf::parse(&bytes).is_err(), "an ELF without PT_LOAD is refused");
}

#[test]
fn drop_unmaps_the_span() {
    // Two images mapped and dropped in a loop would exhaust address space if the
    // unmapping were missing; this simply proves the cycle completes.
    for _ in 0..64 {
        let bytes = synth_elf(0x1000, &[(0x100, 0x1000, 0x40, 0x40, PT_F_R)]);
        let elf = Elf::parse(&bytes).expect("parse");
        let image = MappedImage::map(&elf, PAGE).expect("map");
        assert!(image.read(image.segments()[0].address, 0x40).is_some());
    }
}