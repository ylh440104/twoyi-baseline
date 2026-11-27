/* SPDX-License-Identifier: MPL-2.0 */
//! Try to start a guest binary and report how it finished.
//!
//! This is the diagnostic for the last step of the loader: it maps the image, builds
//! the initial stack, jumps, and prints the exit status or signal. A guest that needs
//! mounts, devices or a full Android environment will die here — the point is that the
//! loader itself gets that far.
//!
//! Usage: cargo run --example runinit -- <elf> [arg ...]

fn main() {
    let mut args = std::env::args().skip(1);
    let path = match args.next() {
        Some(value) => value,
        None => {
            eprintln!("usage: runinit <elf> [arg ...]");
            std::process::exit(2);
        }
    };
    let mut argv: Vec<String> = vec![path.clone()];
    for extra in args {
        argv.push(extra);
    }
    let argv_refs: Vec<&str> = argv.iter().map(String::as_str).collect();

    let bytes = match std::fs::read(&path) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot read {path}: {error}");
            std::process::exit(1);
        }
    };
    // Fill the bridge the way a real start would: open the image's dependencies, then
    // resolve every import against them. Without this any dynamic guest fails to map.
    let mut bridge = miniloader::SymbolTable::new();
    let parsed = match miniloader::elf::Elf::parse(&bytes) {
        Ok(value) => value,
        Err(error) => {
            println!("cannot parse the image: {error:?}");
            std::process::exit(1);
        }
    };
    if let Ok(needed) = miniloader::symbols::needed_libraries(&parsed) {
        if !needed.is_empty() {
            let opened = bridge.open_libraries(&needed);
            println!("libraries: {}/{} opened", opened.len(), needed.len());
            let imports: Vec<String> = miniloader::symbols::jump_slots(&parsed)
                .map(|slots| slots.into_iter().map(|slot| slot.symbol).collect())
                .unwrap_or_default();
            let unresolved = bridge.resolve_names(&imports);
            println!("imports: {} unresolved of {}", unresolved.len(), imports.len());
        }
    }
    match miniloader::run::run_traced(&bytes, 4096, &bridge, &argv_refs, &["PATH=/system/bin"]) {
        Ok((outcome, Some(trap))) => {
            let fault = miniloader::run::Fault {
                signal: trap.signal,
                address: trap.address,
                pc: trap.pc,
            };
            println!(
                "outcome: {outcome:?} fault: signal={} address=0x{:x} pc=0x{:x} sp=0x{:x}",
                trap.signal, trap.address, trap.pc, trap.sp
            );
            println!(
                "  x0=0x{:x} x1=0x{:x} x2=0x{:x} x8=0x{:x} x30=0x{:x}",
                trap.register(0).unwrap_or(0),
                trap.register(1).unwrap_or(0),
                trap.register(2).unwrap_or(0),
                trap.register(8).unwrap_or(0),
                trap.register(30).unwrap_or(0)
            );
            // Say which instruction that PC corresponds to, when the image can tell us.
            if let Ok(parsed) = miniloader::elf::Elf::parse(&bytes) {
                for segment in &parsed.segments {
                    let end = segment.vaddr + segment.filesz;
                    if fault.pc >= segment.vaddr && fault.pc < end {
                        let file_offset = segment.offset + (fault.pc - segment.vaddr);
                        let start = file_offset as usize;
                        let stop = (start + 8).min(bytes.len());
                        let some = &bytes[start..stop];
                        println!(
                            "  pc is at file offset 0x{:x}: {:02x?}",
                            file_offset, some
                        );
                        let bias = fault.pc.wrapping_sub(fault.pc);
                        let _ = bias;
                        break;
                    }
                }
            }
        }
        Ok((outcome, None)) => println!("outcome: {outcome:?}"),
        Err(error) => println!("error: {error}"),
    }
}
