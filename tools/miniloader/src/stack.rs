/* SPDX-License-Identifier: MPL-2.0 */
//! Guest initial stack image.
//!
//! AArch64 layout produced here, from low to high addresses:
//!
//! ```text
//! argc                 u64
//! argv[0..argc]        u64 * argc
//! NULL                 u64
//! envp[0..envc]        u64 * envc
//! NULL                 u64
//! auxv pairs           (kind, value) * n, terminated by AT_NULL
//! padding              up to a 16-byte boundary
//! argv strings         NUL terminated
//! env strings          NUL terminated
//! ```
//!
//! The official loader rejects an over-large image with
//! `guest initial stack is too large` (see `docs/LOADER_DECODE.md`), so the same
//! bound is enforceable here before anything is mapped.

pub const AT_NULL: u64 = 0;
pub const AT_PHDR: u64 = 3;
pub const AT_PHENT: u64 = 4;
pub const AT_PHNUM: u64 = 5;
pub const AT_PAGESZ: u64 = 6;
pub const AT_BASE: u64 = 7;
pub const AT_ENTRY: u64 = 9;
pub const AT_RANDOM: u64 = 25;
pub const DEFAULT_MAX_BYTES: usize = 1 << 20;
const STACK_ALIGN: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Auxv {
    pub kind: u64,
    pub value: u64,
}

impl Auxv {
    pub fn new(kind: u64, value: u64) -> Self {
        Self { kind, value }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StackError {
    TooLarge { needed: usize, limit: usize },
    ArithmeticOverflow,
    MissingPageSize,
}

/// Where each region landed inside [`StackImage::bytes`], relative to the image.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Layout {
    pub argc_offset: usize,
    pub argv_offset: usize,
    pub envp_offset: usize,
    pub auxv_offset: usize,
    pub strings_offset: usize,
}

/// A ready stack image plus the offsets needed to patch absolute pointers.
#[derive(Debug, Clone)]
pub struct StackImage {
    pub bytes: Vec<u8>,
    pub layout: Layout,
    pub size: usize,
    /// Slots that must hold an absolute address once the stack base is known.
    pointers: Vec<(usize, Pointer)>,
}

#[derive(Debug, Clone, Copy)]
enum Pointer {
    /// Pointer to a string, stored as an offset from the image start.
    String(usize),
}

impl StackImage {
    pub fn is_empty(&self) -> bool {
        self.size == 0
    }

    /// Write absolute addresses for every pointer slot, given the image's base.
    pub fn apply_base(&mut self, base: u64) -> Result<(), StackError> {
        for (slot, pointer) in self.pointers.clone() {
            let Pointer::String(offset) = pointer;
            let address = base
                .checked_add(offset as u64)
                .ok_or(StackError::ArithmeticOverflow)?;
            let start = slot;
            let end = slot
                .checked_add(8)
                .ok_or(StackError::ArithmeticOverflow)?;
            if end > self.bytes.len() {
                return Err(StackError::ArithmeticOverflow);
            }
            self.bytes[start..end].copy_from_slice(&address.to_le_bytes());
        }
        Ok(())
    }

    /// Read a pointer slot back, for tests and diagnostics.
    pub fn slot(&self, offset: usize) -> Option<u64> {
        let slice = self.bytes.get(offset..offset + 8)?;
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(slice);
        Some(u64::from_le_bytes(bytes))
    }
}

fn push_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_le_bytes());
}

/// The auxiliary vector a dynamically linked guest needs before it can start.
///
/// A guest `init` that is linked against a loader reads `AT_PHDR`, `AT_PHENT`,
/// `AT_PHNUM` and `AT_ENTRY` to find its own program headers and entry point; without
/// them it cannot relocate itself. `load_bias` is the difference between where the
/// image was mapped and the addresses it was linked at.
///
/// Returns `None` when the program header table is not inside a mapped segment, which
/// is not loadable by this design anyway.
pub fn auxv_for(elf: &crate::elf::Elf<'_>, load_bias: i64, page_size: u64) -> Option<Vec<Auxv>> {
    let phdr = elf.phdr_vaddr?;
    let bias = load_bias as i128;
    let at = |base: u64| -> u64 { (base as i128 + bias) as u64 };
    Some(vec![
        Auxv::new(AT_PAGESZ, page_size),
        Auxv::new(AT_PHDR, at(phdr)),
        Auxv::new(AT_PHENT, u64::from(elf.phdr_entry_size)),
        Auxv::new(AT_PHNUM, u64::from(elf.phdr_count)),
        Auxv::new(AT_ENTRY, at(elf.entry)),
        // No interpreter of its own: the loader is already in this process.
        Auxv::new(AT_BASE, 0),
    ])
}

/// Build the image. String contents are placed after the pointer tables.
pub fn build(
    argv: &[&str],
    env: &[&str],
    auxv: &[Auxv],
    max_bytes: usize,
) -> Result<StackImage, StackError> {
    let mut auxv_entries: Vec<Auxv> = auxv.to_vec();
    if !auxv_entries.iter().any(|entry| entry.kind == AT_PAGESZ) {
        return Err(StackError::MissingPageSize);
    }
    auxv_entries.push(Auxv::new(AT_NULL, 0));

    let tables_len = 8 // argc
        + 8 * argv.len() + 8 // argv + NULL
        + 8 * env.len() + 8 // envp + NULL
        + 16 * auxv_entries.len();
    let strings_len: usize = argv.iter().map(|value| value.len() + 1).sum::<usize>()
        + env.iter().map(|value| value.len() + 1).sum::<usize>();

    let padding = (STACK_ALIGN - (tables_len % STACK_ALIGN)) % STACK_ALIGN;
    let size = tables_len
        .checked_add(padding)
        .and_then(|value| value.checked_add(strings_len))
        .ok_or(StackError::ArithmeticOverflow)?;
    if size > max_bytes {
        return Err(StackError::TooLarge {
            needed: size,
            limit: max_bytes,
        });
    }

    let mut bytes = Vec::with_capacity(size);
    let mut pointers: Vec<(usize, Pointer)> = Vec::new();

    let mut layout = Layout {
        argc_offset: 0,
        ..Layout::default()
    };
    push_u64(&mut bytes, argv.len() as u64);

    layout.argv_offset = bytes.len();
    for _ in 0..argv.len() {
        pointers.push((bytes.len(), Pointer::String(0))); // patched below
        push_u64(&mut bytes, 0);
    }
    push_u64(&mut bytes, 0); // argv NULL terminator

    layout.envp_offset = bytes.len();
    for _ in 0..env.len() {
        pointers.push((bytes.len(), Pointer::String(0)));
        push_u64(&mut bytes, 0);
    }
    push_u64(&mut bytes, 0); // envp NULL terminator

    layout.auxv_offset = bytes.len();
    for entry in &auxv_entries {
        push_u64(&mut bytes, entry.kind);
        push_u64(&mut bytes, entry.value);
    }

    bytes.extend(std::iter::repeat(0u8).take(padding));
    layout.strings_offset = bytes.len();

    // Strings go after the tables; remember each one's offset, then patch pointers.
    let mut string_offsets: Vec<usize> = Vec::with_capacity(argv.len() + env.len());
    for value in argv.iter().chain(env.iter()) {
        string_offsets.push(bytes.len());
        bytes.extend_from_slice(value.as_bytes());
        bytes.push(0);
    }
    for (index, slot) in pointers.iter_mut().enumerate() {
        let offset = *string_offsets.get(index).ok_or(StackError::ArithmeticOverflow)?;
        slot.1 = Pointer::String(offset);
    }

    // The whole image must end on the alignment boundary the ABI expects.
    let tail = (STACK_ALIGN - (bytes.len() % STACK_ALIGN)) % STACK_ALIGN;
    bytes.extend(std::iter::repeat(0u8).take(tail));
    let size = bytes.len();
    debug_assert_eq!(size % STACK_ALIGN, 0);
    if size > max_bytes {
        return Err(StackError::TooLarge {
            needed: size,
            limit: max_bytes,
        });
    }
    Ok(StackImage {
        bytes,
        layout,
        size,
        pointers,
    })
}