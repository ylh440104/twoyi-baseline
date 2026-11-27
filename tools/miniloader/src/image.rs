/* SPDX-License-Identifier: MPL-2.0 */
//! A dry-run of the guest image: lay the segments out in one arena exactly as the
//! real loader would map them.
//!
//! The official loader reserves the whole span, maps each `PT_LOAD` at its page
//! aligned address, copies the file bytes and leaves the tail zero (bss). Doing
//! that against a byte arena instead of `mmap` lets the layout, the copy offsets
//! and the zero fill be checked on any host, with the real AArch64 binaries, before
//! anything is mapped into a live process.

use crate::elf::{Elf, Segment};
use crate::map::{align_down, plan, MapError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImageError {
    Map(MapError),
    /// A segment's file range does not fit the image bytes it was parsed from.
    SegmentOutOfRange { index: usize },
    ArithmeticOverflow,
}

impl From<MapError> for ImageError {
    fn from(error: MapError) -> Self {
        ImageError::Map(error)
    }
}

/// One segment after placement, in arena coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Placed {
    pub vaddr: u64,
    /// Offset of the segment's first byte inside [`GuestImage::arena`].
    pub arena_offset: usize,
    pub filesz: u64,
    pub memsz: u64,
    pub executable: bool,
}

#[derive(Debug, Clone)]
pub struct GuestImage {
    arena: Vec<u8>,
    base_vaddr: u64,
    entry_vaddr: u64,
    placed: Vec<Placed>,
}

impl GuestImage {
    /// Lay the ELF out in an arena the size of the reserved span.
    pub fn load(elf: &Elf<'_>, page_size: u64) -> Result<GuestImage, ImageError> {
        let mapping = plan(&elf.segments, elf.entry, page_size)?;
        let total = mapping
            .reserve_end
            .checked_sub(mapping.reserve_start)
            .ok_or(ImageError::ArithmeticOverflow)?;
        let total = usize::try_from(total).map_err(|_| ImageError::ArithmeticOverflow)?;
        let mut arena = vec![0u8; total];
        let mut placed = Vec::with_capacity(elf.segments.len());

        for (index, segment) in elf.segments.iter().enumerate() {
            let start = align_down(segment.vaddr, page_size)?;
            let offset = start
                .checked_sub(mapping.reserve_start)
                .ok_or(ImageError::ArithmeticOverflow)?;
            let offset = usize::try_from(offset).map_err(|_| ImageError::ArithmeticOverflow)?;
            let filesz = usize::try_from(segment.filesz).map_err(|_| ImageError::ArithmeticOverflow)?;
            let end = offset
                .checked_add(filesz)
                .ok_or(ImageError::ArithmeticOverflow)?;
            if end > arena.len() {
                return Err(ImageError::SegmentOutOfRange { index });
            }
            let source = elf
                .segment_bytes_at(segment)
                .ok_or(ImageError::SegmentOutOfRange { index })?;
            arena[offset..end].copy_from_slice(source);
            placed.push(Placed {
                vaddr: start,
                arena_offset: offset,
                filesz: segment.filesz,
                memsz: segment.memsz,
                executable: segment.flags & crate::elf::PT_F_X != 0,
            });
        }

        Ok(GuestImage {
            arena,
            base_vaddr: mapping.reserve_start,
            entry_vaddr: elf.entry,
            placed,
        })
    }

    pub fn arena(&self) -> &[u8] {
        &self.arena
    }

    pub fn base_vaddr(&self) -> u64 {
        self.base_vaddr
    }

    pub fn entry_vaddr(&self) -> u64 {
        self.entry_vaddr
    }

    pub fn placed(&self) -> &[Placed] {
        &self.placed
    }

    /// Offset the guest would jump to, relative to the arena.
    pub fn entry_offset(&self) -> Option<usize> {
        let offset = self.entry_vaddr.checked_sub(self.base_vaddr)?;
        let offset = usize::try_from(offset).ok()?;
        if offset < self.arena.len() {
            Some(offset)
        } else {
            None
        }
    }

    /// The executable segment containing the entry, if any.
    pub fn entry_segment(&self) -> Option<&Placed> {
        self.placed.iter().find(|placed| {
            placed.executable
                && self.entry_vaddr >= placed.vaddr
                && self.entry_vaddr < placed.vaddr + placed.memsz
        })
    }

    /// Read guest bytes by virtual address, as the running process would see them.
    pub fn read_vaddr(&self, vaddr: u64, length: usize) -> Option<&[u8]> {
        let offset = vaddr.checked_sub(self.base_vaddr)?;
        let offset = usize::try_from(offset).ok()?;
        let end = offset.checked_add(length)?;
        self.arena.get(offset..end)
    }
}

impl Segment {
    /// File bytes, or None when the recorded range is outside the parsed image.
    pub fn file_bytes<'a>(&self, data: &'a [u8]) -> Option<&'a [u8]> {
        let start = usize::try_from(self.offset).ok()?;
        let end = start.checked_add(usize::try_from(self.filesz).ok()?)?;
        data.get(start..end)
    }
}