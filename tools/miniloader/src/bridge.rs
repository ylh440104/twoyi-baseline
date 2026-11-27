/* SPDX-License-Identifier: MPL-2.0 */
//! The host symbol bridge.
//!
//! A guest binary imports names the host has to provide. On Android those come from
//! bionic and from a small set the official loader insists on; the decoded loader
//! strings name two of them explicitly (`android_set_application_target_sdk_version`,
//! `libdl_android.so`) and refuse to start without the bridge
//! (`required host libc bridge symbols are unavailable`).
//!
//! This module is the bridge's shape, not its policy: a name → address table that
//! can be filled from this process, from a static list, or by hand in a test. What
//! the address *does* is the guest's business.

use std::collections::{BTreeMap, BTreeSet};
use std::ffi::{c_void, CString};

/// Names the official loader is known to require (docs/LOADER_DECODE.md).
///
/// Only names with direct evidence are listed; the list is deliberately short
/// rather than a guess at bionic's whole surface.
pub const KNOWN_REQUIRED: &[&str] = &[
    "android_set_application_target_sdk_version",
    "android_get_application_target_sdk_version",
    "__android_log_print",
    "__android_log_write",
    "dlopen",
    "dlsym",
    "dlerror",
    "dlclose",
];

/// A name → address table, plus the libraries it opened to fill itself.
///
/// The handles are kept so the libraries stay mapped: closing them would invalidate
/// every address in the table, which is exactly the kind of bug that only shows up
/// when a guest calls a resolved symbol minutes later.
pub struct SymbolTable {
    entries: BTreeMap<String, u64>,
    handles: Vec<*mut c_void>,
    opened: BTreeSet<String>,
}

impl Default for SymbolTable {
    fn default() -> Self {
        SymbolTable::new()
    }
}

impl Drop for SymbolTable {
    fn drop(&mut self) {
        for handle in self.handles.drain(..) {
            unsafe {
                libc::dlclose(handle);
            }
        }
    }
}

impl SymbolTable {
    pub fn new() -> SymbolTable {
        SymbolTable {
            entries: BTreeMap::new(),
            handles: Vec::new(),
            opened: BTreeSet::new(),
        }
    }

    /// Which shared libraries have been opened for this table.
    pub fn opened_libraries(&self) -> impl Iterator<Item = &String> {
        self.opened.iter()
    }

    pub fn has_library(&self, soname: &str) -> bool {
        self.opened.contains(soname)
    }

    /// Mark a soname as available without opening it, for callers that supply their
    /// own resolver (and for tests).
    pub fn mark_opened(&mut self, soname: &str) {
        if !soname.is_empty() {
            self.opened.insert(soname.to_string());
        }
    }

    /**
     * Open every `soname`, keeping the handles alive.
     *
     * @return the libraries that opened, in the order given
     */
    pub fn open_libraries(&mut self, sonames: &[String]) -> Vec<String> {
        let mut opened = Vec::new();
        for soname in sonames {
            if self.opened.contains(soname) {
                opened.push(soname.clone());
                continue;
            }
            let name = match CString::new(soname.as_str()) {
                Ok(value) => value,
                Err(_) => continue,
            };
            // RTLD_NOW resolves eagerly so a missing dependency surfaces here rather
            // than as a crash inside guest code.
            let handle = unsafe { libc::dlopen(name.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL) };
            if handle.is_null() {
                continue;
            }
            self.handles.push(handle);
            self.opened.insert(soname.clone());
            opened.push(soname.clone());
        }
        opened
    }

    /**
     * Look up `names` in the opened libraries first, then in the global scope.
     *
     * A library local to this table is searched before the process, because a guest
     * asking for `puts` should get the library's own definition when one was named.
     *
     * @return the names that could not be resolved
     */
    pub fn resolve_names(&mut self, names: &[String]) -> Vec<String> {
        let mut missing = Vec::new();
        for name in names {
            if self.entries.contains_key(name) {
                continue;
            }
            let cname = match CString::new(name.as_str()) {
                Ok(value) => value,
                Err(_) => {
                    missing.push(name.clone());
                    continue;
                }
            };
            let mut found = None;
            for handle in &self.handles {
                let symbol = unsafe { libc::dlsym(*handle, cname.as_ptr()) };
                if !symbol.is_null() {
                    found = Some(symbol as usize as u64);
                    break;
                }
            }
            if found.is_none() {
                let symbol = unsafe { libc::dlsym(libc::RTLD_DEFAULT, cname.as_ptr()) };
                if !symbol.is_null() {
                    found = Some(symbol as usize as u64);
                }
            }
            match found {
                Some(address) if address != 0 => {
                    self.entries.insert(name.clone(), address);
                }
                _ => missing.push(name.clone()),
            }
        }
        missing
    }

    /// Record where `name` lives. A zero address is refused: a guest calling zero
    /// would crash with no explanation, which is worse than a load failure.
    pub fn insert(&mut self, name: &str, address: u64) -> Result<(), BridgeError> {
        if name.is_empty() {
            return Err(BridgeError::EmptyName);
        }
        if address == 0 {
            return Err(BridgeError::ZeroAddress {
                name: name.to_string(),
            });
        }
        self.entries.insert(name.to_string(), address);
        Ok(())
    }

    pub fn resolve(&self, name: &str) -> Option<u64> {
        self.entries.get(name).copied()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn names(&self) -> impl Iterator<Item = &String> {
        self.entries.keys()
    }

    /// Which of `required` the table cannot serve, in input order.
    pub fn missing<'a>(&self, required: &[&'a str]) -> Vec<&'a str> {
        required
            .iter()
            .copied()
            .filter(|name| !self.entries.contains_key(*name))
            .collect()
    }

    /// Fill the table from this process with `dlsym`, skipping names it lacks.
    ///
    /// Returns the names that were found so a caller can report what the host does
    /// provide; nothing is invented for the missing ones.
    #[cfg(unix)]
    pub fn from_current_process(&mut self, names: &[&str]) -> Vec<String> {
        let mut found = Vec::new();
        for name in names {
            if let Ok(cname) = std::ffi::CString::new(*name) {
                // RTLD_DEFAULT searches the global scope, which is exactly
                // "what does this process provide".
                let symbol = unsafe { libc::dlsym(libc::RTLD_DEFAULT, cname.as_ptr()) };
                if !symbol.is_null() {
                    let address = symbol as usize as u64;
                    if self.insert(name, address).is_ok() {
                        found.push((*name).to_string());
                    }
                }
            }
        }
        found
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BridgeError {
    EmptyName,
    ZeroAddress { name: String },
    Unresolved { name: String },
}

impl std::fmt::Display for BridgeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BridgeError::EmptyName => write!(formatter, "a symbol needs a name"),
            BridgeError::ZeroAddress { name } => {
                write!(formatter, "symbol {name} resolved to address zero")
            }
            BridgeError::Unresolved { name } => {
                write!(formatter, "required host libc bridge symbol is unavailable: {name}")
            }
        }
    }
}

/// Look up every name and fail on the first the table cannot serve.
pub fn require_all(table: &SymbolTable, names: &[String]) -> Result<(), BridgeError> {
    for name in names {
        if table.resolve(name).is_none() {
            return Err(BridgeError::Unresolved {
                name: name.clone(),
            });
        }
    }
    Ok(())
}