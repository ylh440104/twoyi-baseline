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

/// The register state at the moment a guest died, read through ptrace.
///
/// This is what a guest's own crash handler cannot hide: the guest may install its own
/// SIGSEGV handler (bionic does), but the tracer still sees the stop and can read the
/// registers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Trap {
    pub signal: i32,
    /// Faulting address from `PTRACE_GETSIGINFO`.
    pub address: u64,
    pub pc: u64,
    pub sp: u64,
    /// `x0`..`x30`.
    pub regs: [u64; 31],
}

impl Trap {
    /// Register by index, `0`..=`30` for `x0`..`x30`.
    pub fn register(&self, index: usize) -> Option<u64> {
        self.regs.get(index).copied()
    }
}

/// Where a crashed guest died, which is what a real loader reports.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fault {
    /// Faulting address, from `siginfo.si_addr`.
    pub address: u64,
    /// Instruction pointer at the fault.
    pub pc: u64,
    pub signal: i32,
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

/// Run `bytes` under ptrace and report the register state if it dies.
///
/// The guest may install its own signal handlers — bionic's startup does — which makes a
/// handler inside the child unreliable. A tracer is not affected: it sees the stop and
/// reads the registers directly, so the faulting instruction and the arguments are
/// always available.
pub fn run_traced(
    bytes: &[u8],
    page_size: u64,
    bridge: &SymbolTable,
    argv: &[&str],
    envp: &[&str],
) -> Result<(Outcome, Option<Trap>), RunError> {
    let elf = Elf::parse(bytes).map_err(|error| RunError::Unloadable(format!("{error:?}")))?;
    if stack::auxv_for(&elf, 0, page_size).is_none() {
        return Err(RunError::Unloadable(String::from(
            "no program headers to hand over",
        )));
    }

    let pid = unsafe { libc::fork() };
    match pid {
        -1 => Err(RunError::System(io::Error::last_os_error())),
        0 => {
            // Let the parent trace us, then wait for it to start us again.
            unsafe {
                libc::ptrace(
                    libc::PTRACE_TRACEME,
                    0,
                    std::ptr::null_mut::<libc::c_void>(),
                    std::ptr::null_mut::<libc::c_void>(),
                );
                libc::raise(libc::SIGSTOP);
            }
            let status = match run_child(bytes, page_size, bridge, argv, envp) {
                Ok(()) => 127,
                Err(_) => 126,
            };
            unsafe { libc::_exit(status) }
        }
        child => trace_child(child),
    }
}

/// Drive the traced child: resume after the initial stop, then report how it ended.
fn trace_child(child: libc::pid_t) -> Result<(Outcome, Option<Trap>), RunError> {
    let mut status: libc::c_int = 0;
    // The child stops itself first so the tracer can resume it.
    if unsafe { libc::waitpid(child, &mut status, 0) } != child {
        return Err(RunError::System(io::Error::last_os_error()));
    }
    if unsafe { libc::ptrace(libc::PTRACE_CONT, child, std::ptr::null_mut::<libc::c_void>(), std::ptr::null_mut::<libc::c_void>()) } != 0 {
        return Err(RunError::System(io::Error::last_os_error()));
    }

    loop {
        let waited = unsafe { libc::waitpid(child, &mut status, 0) };
        if waited < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(RunError::System(error));
        }
        if libc::WIFEXITED(status) || libc::WIFSIGNALED(status) {
            return Ok((decode_status(status), None));
        }
        if libc::WIFSTOPPED(status) {
            let signal = libc::WSTOPSIG(status);
            // A plain SIGTRAP/SIGSTOP is a delivery we caused, not a guest fault.
            if signal == libc::SIGTRAP || signal == libc::SIGSTOP {
                unsafe {
                    libc::ptrace(
                        libc::PTRACE_CONT,
                        child,
                        std::ptr::null_mut::<libc::c_void>(),
                        std::ptr::null_mut::<libc::c_void>(),
                    );
                }
                continue;
            }
            let trap = read_trap(child, signal);
            // The guest is already dead in every way that matters; stop waiting on it.
            unsafe {
                libc::ptrace(
                    libc::PTRACE_KILL,
                    child,
                    std::ptr::null_mut::<libc::c_void>(),
                    std::ptr::null_mut::<libc::c_void>(),
                );
                libc::waitpid(child, &mut status, 0);
            }
            let outcome = match trap {
                Some(trap) => Outcome::Signaled(trap.signal),
                None => Outcome::Signaled(signal),
            };
            return Ok((outcome, trap));
        }
    }
}

/// Read the registers and the faulting address of a stopped child.
fn read_trap(child: libc::pid_t, signal: i32) -> Option<Trap> {
    let mut registers: libc::user_regs_struct = unsafe { std::mem::zeroed() };
    let mut vector = libc::iovec {
        iov_base: &mut registers as *mut _ as *mut libc::c_void,
        iov_len: std::mem::size_of::<libc::user_regs_struct>(),
    };
    // NT_PRSTATUS is 1 on every Linux architecture the loader targets.
    let read = unsafe {
        libc::ptrace(
            libc::PTRACE_GETREGSET,
            child,
            1 as *mut libc::c_void,
            &mut vector as *mut _ as *mut libc::c_void,
        )
    };
    if read != 0 {
        return None;
    }

    let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
    let got = unsafe {
        libc::ptrace(
            libc::PTRACE_GETSIGINFO,
            child,
            std::ptr::null_mut::<libc::c_void>(),
            &mut info as *mut _ as *mut libc::c_void,
        )
    };
    let address = if got == 0 {
        unsafe { *((&info as *const libc::siginfo_t as *const u8).add(16) as *const usize) as u64 }
    } else {
        0
    };

    let mut regs = [0u64; 31];
    regs.copy_from_slice(&registers.regs[..31]);
    Some(Trap {
        signal,
        address,
        pc: registers.pc as u64,
        sp: registers.sp as u64,
        regs,
    })
}

/// Run `bytes` and, when it dies from a signal, report where.
///
/// A bare "signalled 11" is useless for an AArch64 guest: the fault address separates
/// a missing TLS setup from a null dereference from a jump into garbage. The child
/// reports through a pipe, because a signal handler may not allocate.
pub fn run_diagnosed(
    bytes: &[u8],
    page_size: u64,
    bridge: &SymbolTable,
    argv: &[&str],
    envp: &[&str],
) -> Result<(Outcome, Option<Fault>), RunError> {
    let elf = Elf::parse(bytes).map_err(|error| RunError::Unloadable(format!("{error:?}")))?;
    if stack::auxv_for(&elf, 0, page_size).is_none() {
        return Err(RunError::Unloadable(String::from(
            "no program headers to hand over",
        )));
    }

    let mut pipe = [0i32; 2];
    if unsafe { libc::pipe(pipe.as_mut_ptr()) } != 0 {
        return Err(RunError::System(io::Error::last_os_error()));
    }

    let pid = unsafe { libc::fork() };
    match pid {
        -1 => {
            let error = io::Error::last_os_error();
            unsafe {
                libc::close(pipe[0]);
                libc::close(pipe[1]);
            }
            Err(RunError::System(error))
        }
        0 => {
            unsafe {
                libc::close(pipe[0]);
            }
            FAULT_FD.store(pipe[1], std::sync::atomic::Ordering::SeqCst);
            install_fault_handlers();
            // A marker first: it proves the pipe works, so a missing fault line means
            // "the guest did not fault" rather than "the report was lost".
            unsafe {
                let marker = b"marker\n";
                libc::write(pipe[1], marker.as_ptr() as *const libc::c_void, marker.len());
            }
            let status = match run_child(bytes, page_size, bridge, argv, envp) {
                Ok(()) => 127,
                Err(_) => 126,
            };
            unsafe { libc::_exit(status) }
        }
        child => {
            unsafe {
                libc::close(pipe[1]);
            }
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
                    unsafe { libc::close(pipe[0]) };
                    return Err(RunError::System(error));
                }
            }

            // The handler wrote one line before exiting; reading it cannot block
            // because the child is already gone and the write end is closed.
            let mut text = String::new();
            {
                let mut file =
                    unsafe { <std::fs::File as std::os::unix::io::FromRawFd>::from_raw_fd(pipe[0]) };
                let _ = std::io::Read::read_to_string(&mut file, &mut text);
            }
            if std::env::var("TWOYI_TRACE_FAULT").is_ok() {
                eprintln!("fault pipe text: {text:?}");
            }
            let fault = parse_fault(&text);
            let outcome = decode_status(status);
            // The reporter exits with 128+signal, so a faulted guest looks like a
            // normal exit; translate it back into the signal it actually died from.
            let outcome = match (outcome, fault) {
                (Outcome::Exited(code), Some(fault)) if code == 128 + fault.signal => {
                    Outcome::Signaled(fault.signal)
                }
                (other, _) => other,
            };
            Ok((outcome, fault))
        }
    }
}

/// Set by the parent, read by the signal handler in the child.
static FAULT_FD: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(-1);

/// Write `signal address` and leave: the child has already failed by this point.
extern "C" fn fault_handler(
    signal: libc::c_int,
    info: *mut libc::siginfo_t,
    context: *mut std::ffi::c_void,
) {
    let fd = FAULT_FD.load(std::sync::atomic::Ordering::SeqCst);
    if fd >= 0 {
        // `si_addr` follows the three-int header, aligned to a pointer.
        let address = if info.is_null() {
            0usize
        } else {
            unsafe { *((info as *const u8).add(16) as *const usize) }
        };
        // The program counter comes from the signal context; without it the address
        // alone cannot say which instruction went wrong.
        let pc = if context.is_null() {
            0usize
        } else {
            unsafe { (*(context as *const libc::ucontext_t)).uc_mcontext.pc as usize }
        };
        let text = format!("{} 0x{:x} 0x{:x}\n", signal, address, pc);
        unsafe {
            libc::write(2, text.as_ptr() as *const libc::c_void, text.len());
            libc::write(fd, text.as_ptr() as *const libc::c_void, text.len());
            libc::close(fd);
        }
    }
    unsafe { libc::_exit(128 + signal) }
}

/// Install the handler for every signal that means "the guest went wrong".
fn install_fault_handlers() {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = fault_handler as *const () as libc::sighandler_t;
        // SA_SIGINFO is what makes `si_addr` available; SA_NODEFER lets a second fault
        // inside the handler still be reported instead of deadlocking.
        action.sa_flags = libc::SA_SIGINFO | libc::SA_NODEFER;
        libc::sigemptyset(&mut action.sa_mask);
        for signal in [libc::SIGSEGV, libc::SIGBUS, libc::SIGILL, libc::SIGFPE] {
            let result = libc::sigaction(signal, &action, std::ptr::null_mut());
            if std::env::var("TWOYI_TRACE_FAULT").is_ok() {
                eprintln!("sigaction({signal}) = {result}");
            }
        }
    }
}

/// The child writes `marker` first, then `signal address` if it faults; take the last
/// line that parses, so the marker never masks a real report.
fn parse_fault(text: &str) -> Option<Fault> {
    text.lines().rev().find_map(|line| {
        let mut parts = line.split_whitespace();
        let signal = parts.next()?.parse().ok()?;
        let address = u64::from_str_radix(parts.next()?.trim_start_matches("0x"), 16).ok()?;
        let pc = parts
            .next()
            .and_then(|text| u64::from_str_radix(text.trim_start_matches("0x"), 16).ok())
            .unwrap_or(0);
        Some(Fault {
            signal,
            address,
            pc,
        })
    })
}

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

fn align_up(value: u64, align: u64) -> u64 {
    if align <= 1 {
        return value;
    }
    (value + align - 1) & !(align - 1)
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
    // The image is laid out so that byte 0 is the stack pointer, with the strings at
    // higher addresses. It therefore sits at the *top* of the region: the stack grows
    // downwards from the stack pointer, and if the image were at the bottom the first
    // push — including a signal handler's — would land outside the mapping.
    let image_len = stack_image.bytes.len() as u64;
    let base_address = (region as u64) + STACK_RESERVE as u64 - image_len;
    if image_len > STACK_RESERVE as u64 {
        return Err(());
    }
    stack_image.apply_base(base_address).map_err(|_| ())?;
    unsafe {
        std::ptr::copy_nonoverlapping(
            stack_image.bytes.as_ptr(),
            base_address as *mut u8,
            stack_image.bytes.len(),
        );
    }

    // The image's own entry, biased by where it was actually mapped.
    let entry = image.entry() as u64;

    // Hand control over. `blr` sets the return address to right after itself, so a
    // guest that returns lands on the trap instead of into random memory.
    // Thread-local storage. AArch64 uses variant I: the static TLS block sits *below*
    // the thread pointer, the TCB at and above it.
    //
    // The TCB is needed even when `PT_TLS` is empty (a real bionic binary here has
    // `memsz == 0`) and even when there is no `PT_TLS` at all (the static Android 8.1
    // `init` has none): bionic keeps errno and its own slots behind the thread pointer,
    // so an unset `TPIDR_EL0` still points at whatever the host thread had.
    let (tls_size, tls_filesz, tls_source) = match elf.tls {
        Some(tls) if tls.memsz > 0 && tls.memsz < (1 << 20) => {
            let align = if tls.align == 0 { 16 } else { tls.align };
            let source = elf.segments.iter().find_map(|segment| {
                let end = segment.vaddr.checked_add(segment.filesz)?;
                if tls.vaddr >= segment.vaddr && tls.vaddr < end {
                    Some(segment.offset + (tls.vaddr - segment.vaddr))
                } else {
                    None
                }
            });
            (align_up(tls.memsz, align), tls.filesz, source)
        }
        _ => (0, 0, None),
    };
    let tcb_size = 4096u64;
    let total = tls_size + tcb_size;
    let block = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            total as usize,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
            -1,
            0,
        )
    };
    if block == libc::MAP_FAILED {
        return Err(());
    }
    let block_base = block as u64;
    if tls_filesz > 0 {
        if let Some(offset) = tls_source {
            let take = (tls_filesz as usize).min(bytes.len().saturating_sub(offset as usize));
            unsafe {
                std::ptr::copy_nonoverlapping(
                    bytes.as_ptr().add(offset as usize),
                    block_base as *mut u8,
                    take,
                );
            }
        }
    }
    // Switch the thread pointer only when the guest really has TLS to point at.
    //
    // A fabricated control block is *not* a bionic TCB: bionic keeps errno and its own
    // slots behind the thread pointer, so switching to a zeroed block makes the first
    // host libc call in this thread fault. That is why the switch is last, why nothing
    // after it may call the host libc, and why a guest with no `PT_TLS` keeps the
    // inherited pointer for now.
    let tp = block_base + tls_size;
    if tls_size > 0 {
        unsafe {
            core::arch::asm!("msr tpidr_el0, {}", in(reg) tp, options(nostack, preserves_flags));
        }
    }

    if std::env::var("TWOYI_TRACE_FAULT").is_ok() {
        eprintln!("jumping to entry=0x{entry:x} sp=0x{base_address:x}");
    }
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