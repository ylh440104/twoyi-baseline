fn main() {
    println!("user_regs_struct size = {}", std::mem::size_of::<libc::user_regs_struct>());
    println!("PTRACE_GETREGSET = {}", libc::PTRACE_GETREGSET);
    println!("PTRACE_ATTACH = {}", libc::PTRACE_ATTACH);
    println!("PTRACE_CONT = {}", libc::PTRACE_CONT);
    println!("PTRACE_TRACEME = {}", libc::PTRACE_TRACEME);
}
