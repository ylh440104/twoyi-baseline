/* SPDX-License-Identifier: MPL-2.0 */
//! Mapping planner: turns parsed segments into an mmap plan.
//!
//! Official loader order (docs/LOADER_RE.md, `0x114560`): reserve the whole span
//! with PROT_NONE, map each PT_LOAD with MAP_PRIVATE|MAP_FIXED at its page-aligned
//! address, fill the part beyond `filesz` with zeros (bss), then narrow the
//! protections per segment. This module only computes that plan so it can be
//! asserted in tests on any host architecture.

use crate::elf::{Segment, PT_F_R, PT_F_W, PT_F_X};

pub const PROT_NONE: u32 = 0;
pub const PROT_READ: u32 = 1;
pub const PROT_WRITE: u32 = 2;
pub const PROT_EXEC: u32 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Map {
    /// Page-aligned start of the mapping.
    pub start: u64,
    /// Page-aligned end of the mapping (exclusive).
    pub end: u64,
    /// Bytes copied from the file; the rest of the mapping is zero-filled.
    pub file_len: u64,
    /// Zero-filled tail beyond `file_len`.
    pub bss_len: u64,
    pub prot: u32,
}

impl Map {
    pub fn byte_len(&self) -> u64 {
        self.end - self.start
    }

    pub fn is_empty(&self) -> bool {
        self.end == self.start
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MappingPlan {
    pub reserve_start: u64,
    pub reserve_end: u64,
    pub maps: Vec<Map>,
    pub page_size: u64,
    pub entry: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MapError {
    PageSizeNotPowerOfTwo(u64),
    PageSizeZero,
    EmptySegments,
    AddressOverflow,
    AlignmentOverflow,
}

pub fn prot_for(flags: u32) -> u32 {
    let mut prot = PROT_NONE;
    if flags & PT_F_R != 0 {
        prot |= PROT_READ;
    }
    if flags & PT_F_W != 0 {
        prot |= PROT_WRITE;
    }
    if flags & PT_F_X != 0 {
        prot |= PROT_EXEC;
    }
    prot
}

pub fn align_down(value: u64, page: u64) -> Result<u64, MapError> {
    if page == 0 {
        return Err(MapError::PageSizeZero);
    }
    if page & (page - 1) != 0 {
        return Err(MapError::PageSizeNotPowerOfTwo(page));
    }
    Ok(value & !(page - 1))
}

pub fn align_up(value: u64, page: u64) -> Result<u64, MapError> {
    let down = align_down(value, page)?;
    if down == value {
        return Ok(value);
    }
    down.checked_add(page).ok_or(MapError::AlignmentOverflow)
}

/// Build the mmap plan for a segment list.
pub fn plan(segments: &[Segment], entry: u64, page_size: u64) -> Result<MappingPlan, MapError> {
    if page_size == 0 {
        return Err(MapError::PageSizeZero);
    }
    if page_size & (page_size - 1) != 0 {
        return Err(MapError::PageSizeNotPowerOfTwo(page_size));
    }
    if segments.is_empty() {
        return Err(MapError::EmptySegments);
    }

    let mut reserve_start = u64::MAX;
    let mut reserve_end = 0u64;
    let mut maps = Vec::with_capacity(segments.len());
    for segment in segments {
        let start = align_down(segment.vaddr, page_size)?;
        let end = align_up(
            segment
                .vaddr
                .checked_add(segment.memsz)
                .ok_or(MapError::AddressOverflow)?,
            page_size,
        )?;
        reserve_start = reserve_start.min(start);
        reserve_end = reserve_end.max(end);
        maps.push(Map {
            start,
            end,
            file_len: segment.filesz,
            bss_len: segment.memsz - segment.filesz,
            prot: prot_for(segment.flags),
        });
    }
    // A plan whose maps are not sorted is still valid, but callers expect order.
    maps.sort_by_key(|map| map.start);
    Ok(MappingPlan {
        reserve_start,
        reserve_end,
        maps,
        page_size,
        entry,
    })
}

impl MappingPlan {
    pub fn reserved_len(&self) -> u64 {
        self.reserve_end - self.reserve_start
    }

    /// Segments whose maps overlap the entry point and are executable.
    pub fn entry_map(&self) -> Option<&Map> {
        self.maps.iter().find(|map| {
            map.prot & PROT_EXEC != 0 && self.entry >= map.start && self.entry < map.end
        })
    }
}