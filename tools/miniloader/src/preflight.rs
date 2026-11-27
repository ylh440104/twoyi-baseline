/* SPDX-License-Identifier: MPL-2.0 */
//! Preflight: decide whether a guest image could be loaded, without loading it.
//!
//! Everything the loader knows before mapping — segment layout, relative
//! relocations, imports, and which of those the host can serve — is gathered here
//! into one verdict. A device should run this before touching memory: it turns the
//! vague "the guest failed to start" into a specific reason, and it is the same
//! check the CI runs against real AArch64 binaries.

use crate::bridge::SymbolTable;
use crate::elf::Elf;
use crate::map::plan;
use crate::reloc;
use crate::symbols::{self, Binding};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportStatus {
    pub name: String,
    /// True for `STB_WEAK` imports, which may legitimately stay unresolved.
    pub weak: bool,
    /// Address the host can provide, if any.
    pub address: Option<u64>,
}

impl ImportStatus {
    /// A weak import that the host lacks is still fine: it becomes zero.
    pub fn is_acceptable(&self) -> bool {
        self.address.is_some() || self.weak
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Blocker {
    /// No `PT_LOAD` segment at all: nothing to map.
    NoSegments,
    /// The mapping plan itself failed (bad page size, overflow, …).
    Unplannable(String),
    /// A strong import that the host cannot serve.
    MissingImport { name: String },
    /// The relocation table could not be read.
    BadRelocations(String),
    /// The symbol table could not be read.
    BadSymbols(String),
    /// The entry point does not land in an executable segment.
    EntryNotExecutable { entry: u64 },
    /// The image names shared libraries the loader does not load yet. Resolving its
    /// imports against this process alone would report false "missing symbol" errors.
    NeedsLibraries { names: Vec<String> },
}

impl std::fmt::Display for Blocker {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Blocker::NoSegments => write!(formatter, "the image has no loadable segment"),
            Blocker::Unplannable(reason) => write!(formatter, "cannot plan the mapping: {reason}"),
            Blocker::MissingImport { name } => {
                write!(formatter, "required host libc bridge symbol is unavailable: {name}")
            }
            Blocker::BadRelocations(reason) => write!(formatter, "cannot read relocations: {reason}"),
            Blocker::BadSymbols(reason) => write!(formatter, "cannot read symbols: {reason}"),
            Blocker::EntryNotExecutable { entry } => {
                write!(formatter, "entry 0x{entry:x} is not inside an executable segment")
            }
            Blocker::NeedsLibraries { names } => write!(
                formatter,
                "the image depends on {} shared library(ies) the loader does not load yet: {}",
                names.len(),
                names.join(" ")
            ),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Preflight {
    pub entry: u64,
    pub segments: usize,
    pub reserved_bytes: u64,
    pub relative_relocations: usize,
    pub imports: Vec<ImportStatus>,
    /// `DT_NEEDED` names, in declaration order; empty for a static image.
    pub needed: Vec<String>,
    pub blockers: Vec<Blocker>,
}

impl Preflight {
    pub fn is_loadable(&self) -> bool {
        self.blockers.is_empty()
    }

    /// Imports the host cannot serve, weak ones included, in table order.
    pub fn unresolved(&self) -> Vec<&ImportStatus> {
        self.imports
            .iter()
            .filter(|status| status.address.is_none())
            .collect()
    }

    /// One line per blocker, for a log or a dialog.
    pub fn reason(&self) -> String {
        if self.blockers.is_empty() {
            return String::from("loadable");
        }
        self.blockers
            .iter()
            .map(|blocker| blocker.to_string())
            .collect::<Vec<String>>()
            .join("; ")
    }
}

/// Gather the verdict for `elf` against the symbols `bridge` can serve.
pub fn preflight(elf: &Elf<'_>, bridge: &SymbolTable, page_size: u64) -> Preflight {
    let mut blockers = Vec::new();

    let (segments, reserved_bytes) = match plan(&elf.segments, elf.entry, page_size) {
        Ok(mapping) => (mapping.maps.len(), mapping.reserved_len()),
        Err(error) => {
            blockers.push(Blocker::Unplannable(format!("{error:?}")));
            (elf.segments.len(), 0)
        }
    };
    if elf.segments.is_empty() {
        blockers.push(Blocker::NoSegments);
    } else if !elf.entry_is_executable() {
        blockers.push(Blocker::EntryNotExecutable { entry: elf.entry });
    }

    let relative_relocations = match reloc::relative_relocations(elf) {
        Ok(entries) => entries.len(),
        Err(error) => {
            blockers.push(Blocker::BadRelocations(format!("{error:?}")));
            0
        }
    };

    let needed = match symbols::needed_libraries(elf) {
        Ok(names) => names,
        Err(error) => {
            blockers.push(Blocker::BadSymbols(format!("{error:?}")));
            Vec::new()
        }
    };
    // A dependency only blocks when the bridge could not open it: opening the library
    // is what makes its symbols resolvable, so an opened one is already handled.
    let unopened: Vec<String> = needed
        .iter()
        .filter(|soname| !bridge.has_library(soname))
        .cloned()
        .collect();
    if !unopened.is_empty() {
        blockers.push(Blocker::NeedsLibraries { names: unopened });
    }

    let mut imports = Vec::new();
    match symbols::jump_slots(elf) {
        Ok(slots) => {
            let mut seen: Vec<String> = Vec::new();
            for slot in &slots {
                if seen.contains(&slot.symbol) {
                    continue;
                }
                seen.push(slot.symbol.clone());
                let status = ImportStatus {
                    name: slot.symbol.clone(),
                    weak: slot.weak,
                    address: bridge.resolve(&slot.symbol),
                };
                if !status.is_acceptable() {
                    blockers.push(Blocker::MissingImport {
                        name: slot.symbol.clone(),
                    });
                }
                imports.push(status);
            }
        }
        Err(error) => blockers.push(Blocker::BadSymbols(format!("{error:?}"))),
    }

    Preflight {
        entry: elf.entry,
        segments,
        reserved_bytes,
        relative_relocations,
        imports,
        needed,
        blockers,
    }
}

/// True when every reported import has a binding we understand.
///
/// An unexpected binding is worth surfacing rather than silently treating as
/// strong: a future guest could use `STB_GNU_UNIQUE` and the loader would refuse it.
pub fn bindings_are_known(elf: &Elf<'_>) -> bool {
    match symbols::dynamic_symbols(elf) {
        Ok(list) => list
            .iter()
            .all(|symbol| !matches!(symbol.binding, Binding::Other(_))),
        Err(_) => false,
    }
}