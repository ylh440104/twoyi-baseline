/* SPDX-License-Identifier: MPL-2.0 */
//! Jump into a mapped guest image.
//!
//! Everything before this module prepares an image: it is parsed, relocated, patched
//! and mapped. This is the step that hands control to it — set the stack pointer to
//! the image we built, jump to the entry point, and let the guest run.
//!
//! The jump happens in a **forked child**, the way a real loader starts a process: the
//! guest's `exit` then only ends the child, and the parent can report the exit status
//! instead of dying with it. The guest owns its own address space copy, which is also
//! what makes it safe to try an untrusted image.

use std::io;

use crate::bridge::SymbolTable;
use crate::elf::Elf;
use crate::executor::MappedImage;
use crate::stack::{self, Auxv};

/// How the guest finished.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// The guest called `exit(status)`.
    Exited(i32),
    /// The guest died from a signal, which is the common "it crashed" case.
    Signaled(i32),
}

#[derive(Debug)]
pub enum RunError {
    /// The image could not be prepared; nothing was jumped to.
    Unloadable(String),
    /// The child could not be created or waited for.
    System(io::Error),
    /// The guest returned to the loader instead of exiting.
    Returned,
}

impl std::fmt::Display for RunError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RunError::Unloadable(reason) => write!(formatter, "unloadable guest: {reason}"),
            RunError::System(error) => write!(formatter, "system: {error}"),
            RunError::Returned => write!(formatter, "the guest returned instead of exiting"),
        }
    }
}

/// Where the guest's stack is placed. Separate from the image so a runaway guest
/// cannot overwrite its own code through the stack.
const STACK_RESERVE: usize = 1 << 20; // 1 MiB

/// Run `bytes` as a guest and report how it finished.
///
/// `bridge` supplies the addresses for the image's imports; `argv`/`envp` become the
/// guest's initial stack. The guest inherits stdout so its output lands where the
/// caller expects it.
pub fn run(
    bytes: &[u8],
    page_size: u64,
    bridge: &SymbolTable,
    argv: &[&str],
    envp: &[&str],
) -> Result<Outcome, RunError> {
    // Parse and check before forking: a bad image must not cost a process.
    let elf = Elf::parse(bytes).map_err(|error| RunError::Unloadable(format!("{error:?}")))?;
    // The auxiliary vector names addresses inside the image, so it can only be built
    // once the image has been mapped and its bias is known — that happens in the child.
    if stack::auxv_for(&elf, 0, page_size).is_none() {
        return Err(RunError::Unloadable(String::from(
            "no program headers to hand over",
        )));
    }

    let pid = unsafe { libc::fork() };
    match pid {
        -1 => Err(RunError::System(io::Error::last_os_error())),
        0 => {
            // Child: on any failure there is nowhere to report it, so exit loudly.
            let status = match run_child(bytes, page_size, bridge, argv, envp) {
                Ok(()) => 127,
                Err(_) => 126,
            };
            unsafe { libc::_exit(status) }
        }
        child => {
            let mut status: libc::c_int = 0;
            loop {
                let waited = unsafe { libc::waitpid(child, &mut status, 0) };
                if waited == child {
                    break;
                }
                if waited < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(RunError::System(error));
                }
            }
            Ok(decode_status(status))
        }
    }
}

fn decode_status(status: libc::c_int) -> Outcome {
    if libc::WIFEXITED(status) {
        Outcome::Exited(libc::WEXITSTATUS(status))
    } else {
        Outcome::Signaled(libc::WTERMSIG(status))
    }
}

fn run_child(
    bytes: &[u8],
    page_size: u64,
    bridge: &SymbolTable,
    argv: &[&str],
    envp: &[&str],
) -> Result<(), ()> {
    let elf = Elf::parse(bytes).map_err(|_| ())?;
    let image = MappedImage::map_with_bridge(&elf, page_size, true, Some(bridge)).map_err(|_| ())?;
    let image = std::mem::ManuallyDrop::new(image);

    // Where the image landed relative to the addresses it was linked at. The guest's
    // own linker reads this from `AT_PHDR`/`AT_ENTRY`, so it must be right.
    let bias = (image.entry() as i64).wrapping_sub(elf.entry as i64);
    let auxv: Vec<Auxv> = stack::auxv_for(&elf, bias, page_size).ok_or(())?;

    // The stack the guest will see: argv, envp and auxv laid out by the stack builder.
    let mut stack_image = stack::build(argv, envp, &auxv, STACK_RESERVE).map_err(|_| ())?;
    let region = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            STACK_RESERVE,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
            -1,
            0,
        )
    };
    if region == libc::MAP_FAILED {
        return Err(());
    }
    let base = region as *mut u8;
    let base_address = region as u64;
    // The image is laid out so that byte 0 is the stack pointer: argc comes first and
    // the strings sit at higher addresses. Pointers inside it are offsets from that
    // base, so they must be made absolute before the guest sees the image.
    stack_image.apply_base(base_address).map_err(|_| ())?;
    unsafe {
        std::ptr::copy_nonoverlapping(
            stack_image.bytes.as_ptr(),
            base,
            stack_image.bytes.len(),
        );
    }

    // The image's own entry, biased by where it was actually mapped.
    let entry = image.entry() as u64;

    // Hand control over. `blr` sets the return address to right after itself, so a
    // guest that returns lands on the trap instead of into random memory.
    unsafe {
        core::arch::asm!(
            "mov sp, x9",
            "blr x10",
            "brk #1",
            in("x9") base_address,
            in("x10") entry,
            options(noreturn),
        );
    }
}