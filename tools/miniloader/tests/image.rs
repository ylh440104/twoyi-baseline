/* SPDX-License-Identifier: MPL-2.0 */
//! Tests for the dry-run image: segments land where the loader would map them,
//! file bytes are copied and the bss tail stays zero.

use miniloader::elf::{Elf, PT_F_R, PT_F_W, PT_F_X};
use miniloader::image::{GuestImage, ImageError};

const PAGE: u64 = 4096;

/// A valid ELF64 aarch64 image whose single exec segment holds a known pattern.
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
        // Fill the payload area with a recognisable pattern so a copy can be checked.
        let start = out.len();
        out.resize(needed, 0);
        for (index, slot) in out[start..needed].iter_mut().enumerate() {
            *slot = (index % 251) as u8;
        }
    }
    out
}

#[test]
fn segments_are_placed_and_file_bytes_copied() {
    let bytes = synth_elf(0x1000, &[(0x100, 0x1000, 0x40, 0x140, PT_F_R | PT_F_X)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let image = GuestImage::load(&elf, PAGE).expect("load");

    assert_eq!(image.base_vaddr(), 0x1000);
    assert_eq!(image.entry_vaddr(), 0x1000);
    assert_eq!(image.entry_offset(), Some(0));
    assert_eq!(image.arena().len(), 0x1000);
    assert_eq!(image.placed().len(), 1);

    let placed = image.placed()[0];
    assert_eq!(placed.vaddr, 0x1000);
    assert_eq!(placed.arena_offset, 0);
    assert_eq!(placed.filesz, 0x40);
    assert_eq!(placed.memsz, 0x140);
    assert!(placed.executable);

    // The copied bytes match the file payload, and the bss tail is zero.
    let expected = &bytes[0x100..0x140];
    assert_eq!(&image.arena()[0..0x40], expected);
    assert!(image.arena()[0x40..0x100].iter().all(|byte| *byte == 0));
    // Reading by virtual address gives the same bytes the guest would see.
    assert_eq!(image.read_vaddr(0x1000, 0x40), Some(expected));
    assert_eq!(image.entry_segment().map(|p| p.vaddr), Some(0x1000));
}

#[test]
fn addresses_are_page_aligned_and_bias_is_respected() {
    // A segment starting mid-page must be mapped from the page boundary, so the
    // file bytes land at vaddr & !(page-1) + (vaddr - aligned) ... here vaddr is
    // already aligned, and a second segment starts unaligned.
    let bytes = synth_elf(
        0x1000,
        &[
            (0x100, 0x1000, 0x100, 0x100, PT_F_R | PT_F_X),
            (0x300, 0x2800, 0x80, 0x80, PT_F_R | PT_F_W),
        ],
    );
    let elf = Elf::parse(&bytes).expect("parse");
    let image = GuestImage::load(&elf, PAGE).expect("load");
    assert_eq!(image.base_vaddr(), 0x1000);
    assert_eq!(image.placed().len(), 2);
    assert_eq!(image.placed()[0].arena_offset, 0);
    assert_eq!(image.placed()[1].vaddr, 0x2000, "segment start is page aligned");
    assert_eq!(image.placed()[1].arena_offset, 0x1000);
    // The second segment's bytes were copied from its file offset.
    let expected = &bytes[0x300..0x380];
    assert_eq!(image.read_vaddr(0x2000, 0x80), Some(expected));
    assert!(!image.placed()[1].executable);
}

#[test]
fn an_out_of_range_segment_is_refused() {
    // filesz claims more bytes than the file holds; parse already rejects this, so
    // build the Segment by hand and check the image refuses it too.
    use miniloader::elf::Segment;
    let bytes = synth_elf(0x1000, &[(0x100, 0x1000, 0x10, 0x10, PT_F_R)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let mut tampered = elf.clone();
    tampered.segments = vec![Segment {
        offset: 0xffff,
        vaddr: 0x1000,
        filesz: 0x10,
        memsz: 0x10,
        flags: PT_F_R,
        align: PAGE,
    }];
    match GuestImage::load(&tampered, PAGE) {
        Err(ImageError::SegmentOutOfRange { index }) => assert_eq!(index, 0),
        other => panic!("expected SegmentOutOfRange, got {other:?}"),
    }
}

#[test]
fn entry_outside_the_image_has_no_offset() {
    let bytes = synth_elf(0x9000, &[(0x100, 0x1000, 0x10, 0x10, PT_F_R | PT_F_X)]);
    let elf = Elf::parse(&bytes).expect("parse");
    let image = GuestImage::load(&elf, PAGE).expect("load");
    assert_eq!(image.entry_offset(), None, "entry is outside the reserved span");
    assert!(image.entry_segment().is_none());
    assert_eq!(image.read_vaddr(0x9000, 4), None);
}