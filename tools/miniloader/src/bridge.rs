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

use std::collections::BTreeMap;

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

#[derive(Debug, Default, Clone)]
pub struct SymbolTable {
    entries: BTreeMap<String, u64>,
}

impl SymbolTable {
    pub fn new() -> SymbolTable {
        SymbolTable {
            entries: BTreeMap::new(),
        }
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