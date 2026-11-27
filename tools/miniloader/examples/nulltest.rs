/* SPDX-License-Identifier: MPL-2.0 */
//! Sanity check that a sigaction-based handler is reached on this platform.

extern "C" fn handler(signal: libc::c_int, _info: *mut libc::siginfo_t, _context: *mut std::ffi::c_void) {
    let text = format!("caught {signal}\n");
    unsafe {
        libc::write(2, text.as_ptr() as *const libc::c_void, text.len());
        libc::_exit(99);
    }
}

fn main() {
    // Same shape as the loader: the handler is installed in a forked child.
    let pid = unsafe { libc::fork() };
    if pid == 0 {
        run_child_like();
        unsafe { libc::_exit(0) }
    }
    let mut status = 0;
    unsafe { libc::waitpid(pid, &mut status, 0) };
    println!("child status: {}", status & 0x7f);
}

fn run_child_like() {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = handler as *const () as libc::sighandler_t;
        action.sa_flags = libc::SA_SIGINFO;
        libc::sigemptyset(&mut action.sa_mask);
        libc::sigaction(libc::SIGSEGV, &action, std::ptr::null_mut());
    }
    eprintln!("about to dereference null");
    unsafe { std::ptr::write_volatile(std::ptr::null_mut::<u8>(), 1u8) };
    eprintln!("survived");
}
