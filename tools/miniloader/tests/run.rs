/* SPDX-License-Identifier: MPL-2.0 */
//! Jumping into a guest image.
//!
//! These tests build a tiny AArch64 program, run it through the loader, and check what
//! it did: the guest writes to stdout and exits with a known status. They only run on
//! an AArch64 host, because the guest's own instructions have to be executable there.

#![cfg(all(unix, target_arch = "aarch64"))]

use std::fs;
use std::io::Read;
use std::process::Command;

use miniloader::run::{run, Outcome, RunError};
use miniloader::SymbolTable;

/// A guest that writes `text` to stdout and exits with `status`.
///
/// Hand-written assembly rather than C: the point is to control exactly what the
/// guest does (two syscalls and nothing else), so a failure cannot be blamed on a
/// runtime the guest brought along.
fn guest_source(text: &str, status: i32) -> String {
    format!(
        r#"
        .globl _start
        _start:
            mov x0, #1              // stdout
            adr x1, message
            mov x2, #{length}
            mov x8, #64             // write
            svc #0

            mov x0, #{status}       // exit status
            mov x8, #93             // exit
            svc #0

        message:
            .ascii "{text}"
        "#,
        length = text.len(),
        status = status,
        text = text
    )
}

/// Assemble `source` into a static AArch64 ELF and return its bytes.
fn assemble(dir: &std::path::Path, source: &str) -> Vec<u8> {
    let asm = dir.join("guest.S");
    let elf = dir.join("guest");
    fs::write(&asm, source).expect("write asm");
    let output = Command::new("cc")
        .args(["-nostdlib", "-static", "-o"])
        .arg(&elf)
        .arg(&asm)
        .output()
        .expect("run cc");
    assert!(
        output.status.success(),
        "cc failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    fs::read(&elf).expect("read guest")
}

fn workdir(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("twoyi-run-{}-{}", name, std::process::id()));
    fs::create_dir_all(&dir).expect("mkdir");
    dir
}

#[test]
fn a_guest_runs_and_reports_its_exit_status() {
    let dir = workdir("exit");
    let bytes = assemble(&dir, &guest_source("hi\n", 42));

    let outcome = run(&bytes, 4096, &SymbolTable::new(), &["guest"], &[]).expect("run");
    assert_eq!(outcome, Outcome::Exited(42), "the guest's own exit status comes back");
}

#[test]
fn the_guest_sees_the_argv_and_envp_it_was_given() {
    // A guest that only exits proves control transfer. Reading argc from the stack
    // proves the initial stack is the one we built. `argc` is at [sp].
    let dir = workdir("argv");
    let source = r#"
        .globl _start
        _start:
            ldr x0, [sp]            // argc
            mov x8, #93             // exit
            svc #0
        "#;
    let bytes = assemble(&dir, source);
    let outcome = run(
        &bytes,
        4096,
        &SymbolTable::new(),
        &["guest", "one", "two"],
        &["A=1"],
    )
    .expect("run");
    assert_eq!(outcome, Outcome::Exited(3), "argc is 3: program plus two arguments");
}

#[test]
fn a_guest_that_crashes_is_reported_as_a_signal() {
    let dir = workdir("crash");
    let source = r#"
        .globl _start
        _start:
            brk #7                  // deliberate trap
        "#;
    let bytes = assemble(&dir, source);
    let outcome = run(&bytes, 4096, &SymbolTable::new(), &["guest"], &[]).expect("run");
    assert_eq!(
        outcome,
        Outcome::Signaled(libc_sigtrap()),
        "a trap must come back as SIGTRAP"
    );
}

#[test]
fn a_broken_image_is_refused_before_any_process_starts() {
    let error = run(b"not an elf at all", 4096, &SymbolTable::new(), &["guest"], &[])
        .expect_err("a non-ELF must be refused");
    assert!(matches!(error, RunError::Unloadable(_)));
    // And the process is still alive to say so, which is the point of checking first.
}

fn libc_sigtrap() -> i32 {
    5
}

#[test]
fn captured_stdout_contains_the_guest_output() {
    // Redirect the child's stdout to a pipe and check the bytes arrive.
    let dir = workdir("pipe");
    let bytes = assemble(&dir, &guest_source("captured\n", 7));

    let mut pipe = [0i32; 2];
    assert_eq!(unsafe { libc::pipe(pipe.as_mut_ptr()) }, 0);
    let saved = unsafe { libc::dup(1) };
    assert!(saved >= 0);
    // The loader's child inherits fd 1, so point it at the pipe for this call.
    unsafe {
        libc::dup2(pipe[1], 1);
    }
    let outcome = run(&bytes, 4096, &SymbolTable::new(), &["guest"], &[]).expect("run");
    unsafe {
        libc::dup2(saved, 1);
        libc::close(saved);
        libc::close(pipe[1]);
    }
    assert_eq!(outcome, Outcome::Exited(7));

    let mut reader = unsafe { <std::fs::File as std::os::unix::io::FromRawFd>::from_raw_fd(pipe[0]) };
    let mut text = String::new();
    reader.read_to_string(&mut text).expect("read pipe");
    assert_eq!(text, "captured\n", "the guest's write reached the pipe");
}

#[test]
fn a_faulting_guest_reports_where_it_faulted() {
    // A guest that dereferences null and installs no handler of its own: the loader's
    // reporter must name the address, which is what turns "signalled 11" into a cause.
    let dir = workdir("fault-address");
    let source = r#"
        .globl _start
        _start:
            mov x0, #0
            str x0, [x0]        // store to address zero
        "#;
    let bytes = assemble(&dir, source);
    let (outcome, fault) =
        miniloader::run::run_diagnosed(&bytes, 4096, &SymbolTable::new(), &["guest"], &[])
            .expect("run");
    assert_eq!(outcome, Outcome::Signaled(11), "SIGSEGV");
    let fault = fault.expect("the fault address must be reported");
    assert_eq!(fault.signal, 11);
    assert_eq!(fault.address, 0, "the guest wrote to address zero");
}

#[test]
fn a_clean_guest_reports_no_fault() {
    let dir = workdir("no-fault");
    let bytes = assemble(&dir, &guest_source("ok\n", 0));
    let (outcome, fault) =
        miniloader::run::run_diagnosed(&bytes, 4096, &SymbolTable::new(), &["guest"], &[])
            .expect("run");
    assert_eq!(outcome, Outcome::Exited(0));
    assert!(fault.is_none(), "a guest that exits cleanly has no fault");
}

#[test]
fn the_fault_reporter_works_inside_a_test_harness_child() {
    // Isolates the harness from the loader: if this fails, the problem is the test
    // process, not the guest run.
    static FD: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(-1);
    extern "C" fn handler(signal: libc::c_int, _i: *mut libc::siginfo_t, _c: *mut std::ffi::c_void) {
        unsafe { libc::_exit(200 + signal) }
    }
    let mut pipe = [0i32; 2];
    assert_eq!(unsafe { libc::pipe(pipe.as_mut_ptr()) }, 0);
    let pid = unsafe { libc::fork() };
    if pid == 0 {
        FD.store(pipe[1], std::sync::atomic::Ordering::SeqCst);
        unsafe {
            let mut action: libc::sigaction = std::mem::zeroed();
            action.sa_sigaction = handler as *const () as libc::sighandler_t;
            action.sa_flags = libc::SA_SIGINFO;
            libc::sigemptyset(&mut action.sa_mask);
            let result = libc::sigaction(libc::SIGSEGV, &action, std::ptr::null_mut());
            if result != 0 {
                libc::_exit(1);
            }
            std::ptr::write_volatile(std::ptr::null_mut::<u8>(), 1u8);
        }
        unsafe { libc::_exit(2) }
    }
    let mut status: libc::c_int = 0;
    unsafe { libc::waitpid(pid, &mut status, 0) };
    assert!(
        libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 211,
        "the handler must run in a forked child of the test harness; status={status}"
    );
}
