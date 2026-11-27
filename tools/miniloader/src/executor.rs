/* SPDX-License-Identifier: MPL-2.0 */
//! The real mapping step: reserve the span, copy the segments in, narrow the
//! protections.
//!
//! This is the same sequence the official loader performs (docs/LOADER_RE.md,
//! `0x114560`): one `PROT_NONE` reservation for the whole span, the file bytes
//! copied into it at page-aligned offsets, then `mprotect` per segment. Mapping a
//! guest image is architecture independent — only jumping into it is not — so this
//! can be exercised on the CI host instead of waiting for a device.

use std::ffi::c_void;

use crate::elf::Elf;
use crate::image::ImageError;
use crate::map::{plan, prot_for};

#[derive(Debug)]
pub enum ExecError {
    Image(ImageError),
    Allocate { size: usize, errno: i32 },
    Protect { at: u64, len: usize, errno: i32 },
    ProtectedButEmpty,
}

impl From<ImageError> for ExecError {
    fn from(error: ImageError) -> Self {
        ExecError::Image(error)
    }
}

impl std::fmt::Display for ExecError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ExecError::Image(error) => write!(formatter, "image: {error:?}"),
            ExecError::Allocate { size, errno } => {
                write!(formatter, "mmap of {size} bytes failed (errno {errno})")
            }
            ExecError::Protect { at, len, errno } => {
                write!(formatter, "mprotect 0x{at:x} len {len} failed (errno {errno})")
            }
            ExecError::ProtectedButEmpty => write!(formatter, "nothing was mapped"),
        }
    }
}

/// A guest image mapped into this process.
///
/// The span is unmapped on drop, so a failed test cannot leak address space into
/// the rest of the run.
pub struct MappedImage {
    base: *mut u8,
    len: usize,
    page_size: u64,
    entry_vaddr: u64,
    segments: Vec<MappedSegment>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MappedSegment {
    /// Address inside this process where the segment was placed.
    pub address: u64,
    pub file_len: u64,
    pub mem_len: u64,
    pub prot: u32,
}

impl MappedImage {
    /// Map every `PT_LOAD` of `elf` and apply its protections.
    pub fn map(elf: &Elf<'_>, page_size: u64) -> Result<MappedImage, ExecError> {
        let mapping = plan(&elf.segments, elf.entry, page_size)?;
        let span = mapping
            .reserve_end
            .checked_sub(mapping.reserve_start)
            .ok_or(ExecError::Image(ImageError::ArithmeticOverflow))?;
        let len = usize::try_from(span)
            .map_err(|_| ExecError::Image(ImageError::ArithmeticOverflow))?;
        if len == 0 {
            return Err(ExecError::ProtectedButEmpty);
        }

        // One anonymous reservation for the whole span; the kernel may place it
        // anywhere, which is why the bias is computed afterwards.
        let raw = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_NONE,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        if raw == libc::MAP_FAILED {
            return Err(ExecError::Allocate {
                size: len,
                errno: errno(),
            });
        }
        let base = raw as *mut u8;
        let base_vaddr = base as u64;
        let bias = base_vaddr.wrapping_sub(mapping.reserve_start);

        let mut segments = Vec::with_capacity(elf.segments.len());
        for (index, segment) in elf.segments.iter().enumerate() {
            let start = crate::map::align_down(segment.vaddr, page_size)
                .map_err(|_| ExecError::Image(ImageError::ArithmeticOverflow))?;
            let offset = match start.checked_sub(mapping.reserve_start) {
                Some(value) => value,
                None => return Err(cleanup(base, len, ExecError::ProtectedButEmpty)),
            };
            let offset = match usize::try_from(offset) {
                Ok(value) => value,
                Err(_) => return Err(cleanup(base, len, ExecError::ProtectedButEmpty)),
            };
            let filesz = match usize::try_from(segment.filesz) {
                Ok(value) => value,
                Err(_) => return Err(cleanup(base, len, ExecError::ProtectedButEmpty)),
            };
            if offset + filesz > len {
                let error = ExecError::Image(ImageError::SegmentOutOfRange { index });
                return Err(cleanup(base, len, error));
            }
            let source = match elf.segment_bytes_at(segment) {
                Some(bytes) => bytes,
                None => {
                    let error = ExecError::Image(ImageError::SegmentOutOfRange { index });
                    return Err(cleanup(base, len, error));
                }
            };
            // The reservation is PROT_NONE, so it must be writable before the copy.
            let first_page_start = start;
            let last_page_end = crate::map::align_up(
                segment.vaddr.saturating_add(segment.memsz),
                page_size,
            )
            .map_err(|_| ExecError::Image(ImageError::ArithmeticOverflow))?;
            let span_len = usize::try_from(last_page_end.saturating_sub(first_page_start))
                .unwrap_or(filesz);
            if span_len == 0 {
                continue;
            }
            let prot = prot_for(segment.flags);
            if let Err(error) = protect(base, len, offset, span_len, libc::PROT_READ | libc::PROT_WRITE) {
                return Err(cleanup(base, len, error));
            }
            unsafe {
                std::ptr::copy_nonoverlapping(source.as_ptr(), base.add(offset), filesz);
            }
            if let Err(error) = protect(base, len, offset, span_len, prot) {
                return Err(cleanup(base, len, error));
            }
            segments.push(MappedSegment {
                address: base_vaddr + offset as u64,
                file_len: segment.filesz,
                mem_len: segment.memsz,
                prot,
            });
        }

        Ok(MappedImage {
            base,
            len,
            page_size,
            entry_vaddr: elf.entry.wrapping_add(bias),
            segments,
        })
    }

    /// Where this process would jump to for the guest entry.
    pub fn entry(&self) -> u64 {
        self.entry_vaddr
    }

    pub fn page_size(&self) -> u64 {
        self.page_size
    }

    pub fn segments(&self) -> &[MappedSegment] {
        &self.segments
    }

    /// Read mapped bytes, as the guest would see them.
    pub fn read(&self, address: u64, length: usize) -> Option<&[u8]> {
        if length == 0 {
            return None;
        }
        let offset = address.checked_sub(self.base as u64)?;
        let offset = usize::try_from(offset).ok()?;
        let end = offset.checked_add(length)?;
        if end > self.len {
            return None;
        }
        Some(unsafe { std::slice::from_raw_parts(self.base.add(offset), length) })
    }
}

impl Drop for MappedImage {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.base as *mut c_void, self.len);
        }
    }
}

fn protect(base: *mut u8, total: usize, offset: usize, len: usize, prot: i32) -> Result<(), ExecError> {
    let address = unsafe { base.add(offset) };
    let result = unsafe { libc::mprotect(address as *mut c_void, len.min(total - offset), prot) };
    if result != 0 {
        return Err(ExecError::Protect {
            at: address as u64,
            len,
            errno: errno(),
        });
    }
    Ok(())
}

fn cleanup(base: *mut u8, len: usize, error: ExecError) -> ExecError {
    unsafe {
        libc::munmap(base as *mut c_void, len);
    }
    error
}

fn errno() -> i32 {
    std::io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or(0)
}