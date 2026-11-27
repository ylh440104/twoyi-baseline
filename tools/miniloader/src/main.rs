/* SPDX-License-Identifier: MPL-2.0 */
//! Command line front end: print the mapping and stack plan for an ELF file.
//!
//! This is a diagnostic tool, not the runtime. It lets a developer inspect what
//! the loader would do to a guest binary without any privileged operation.


use miniloader::{
    build_stack, elf::Elf, map::plan, stack::Auxv, stack::AT_PAGESZ, SymbolTable,
};

fn main() {
    let mut args = std::env::args().skip(1);
    let path = match args.next() {
        Some(value) => value,
        None => {
            eprintln!("usage: miniloader-plan <elf> [page-size]");
            std::process::exit(2);
        }
    };
    let page_size: u64 = args
        .next()
        .and_then(|value| value.parse().ok())
        .unwrap_or(4096);

    let bytes = match std::fs::read(&path) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot read {path}: {error}");
            std::process::exit(1);
        }
    };
    let parsed = match Elf::parse(&bytes) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot parse {path}: {error:?}");
            std::process::exit(1);
        }
    };
    println!("entry: 0x{:x}", parsed.entry);
    println!("entry executable: {}", parsed.entry_is_executable());
    for (index, segment) in parsed.segments.iter().enumerate() {
        println!(
            "seg[{index}] vaddr=0x{:x} filesz={} memsz={} bss={} flags=0x{:x}",
            segment.vaddr,
            segment.filesz,
            segment.memsz,
            segment.bss_len(),
            segment.flags
        );
    }

    // What does the guest import, and can this host serve it? This is the check a
    // real device would run before mapping anything.
    match miniloader::symbols::jump_slots(&parsed) {
        Ok(slots) if !slots.is_empty() => {
            let mut names: Vec<String> = Vec::new();
            for slot in &slots {
                if !names.contains(&slot.symbol) {
                    names.push(slot.symbol.clone());
                }
            }
            let mut table = SymbolTable::new();
            let found = table.from_current_process(
                &names.iter().map(String::as_str).collect::<Vec<&str>>(),
            );
            // A weak import that stays unresolved is normal; only strong ones block.
            let missing: Vec<&String> = slots
                .iter()
                .filter(|slot| !slot.weak && table.resolve(&slot.symbol).is_none())
                .map(|slot| &slot.symbol)
                .collect();
            let missing_weak: Vec<&String> = slots
                .iter()
                .filter(|slot| slot.weak && table.resolve(&slot.symbol).is_none())
                .map(|slot| &slot.symbol)
                .collect();
            println!(
                "imports: {} slot(s), {} distinct name(s), {} servable here",
                slots.len(),
                names.len(),
                found.len()
            );
            if !found.is_empty() {
                println!("imports servable: {}", found.join(" "));
            }
            if !missing_weak.is_empty() {
                println!(
                    "imports weak (may stay zero): {}",
                    missing_weak
                        .iter()
                        .map(|name| name.as_str())
                        .collect::<Vec<&str>>()
                        .join(" ")
                );
            }
            if missing.is_empty() {
                println!("imports resolved: all strong");
                println!(
                    "imports missing: {}",
                    missing
                        .iter()
                        .map(|name| name.as_str())
                        .collect::<Vec<&str>>()
                        .join(" ")
                );
            }
        }
        Ok(_) => println!("imports: none"),
        Err(error) => println!("imports: unreadable ({error:?})"),
    }

    let mapping = match plan(&parsed.segments, parsed.entry, page_size) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot plan mapping: {error:?}");
            std::process::exit(1);
        }
    };
    println!(
        "reserve: 0x{:x}..0x{:x} ({} bytes, page {})",
        mapping.reserve_start,
        mapping.reserve_end,
        mapping.reserved_len(),
        mapping.page_size
    );
    for map in &mapping.maps {
        println!(
            "map 0x{:x}..0x{:x} file={} bss={} prot={}",
            map.start,
            map.end,
            map.file_len,
            map.bss_len,
            map.prot
        );
    }

    if std::env::args().any(|argument| argument == "--check") {
        let mut table = SymbolTable::new();
        let names: Vec<&str> = miniloader::KNOWN_REQUIRED.to_vec();
        table.from_current_process(&names);
        let verdict = miniloader::preflight::preflight(&parsed, &table, page_size);
        println!(
            "check: {}",
            if verdict.is_loadable() {
                "loadable"
            } else {
                "blocked"
            }
        );
        println!(
            "check: segments={} reserved={} relocations={} imports={}",
            verdict.segments,
            verdict.reserved_bytes,
            verdict.relative_relocations,
            verdict.imports.len()
        );
        for blocker in &verdict.blockers {
            println!("check blocker: {blocker}");
        }
        if !verdict.is_loadable() {
            std::process::exit(3);
        }
    }

    if std::env::args().any(|argument| argument == "--map") {
        match miniloader::MappedImage::map(&parsed, page_size) {
            Ok(mapped) => {
                println!(
                    "mapped: entry=0x{:x} segments={}",
                    mapped.entry(),
                    mapped.segments().len()
                );
                for (index, segment) in mapped.segments().iter().enumerate() {
                    println!(
                        "mapped[{}] addr=0x{:x} file={} mem={} prot={}",
                        index, segment.address, segment.file_len, segment.mem_len, segment.prot
                    );
                }
                println!(
                    "relocations: {}",
                    miniloader::reloc::relative_relocations(&parsed)
                        .map(|list| list.len())
                        .unwrap_or(0)
                );
                let entry = mapped.entry();
                match mapped.read(entry, 4) {
                    Some(bytes) => println!(
                        "entry bytes: {:02x} {:02x} {:02x} {:02x}",
                        bytes[0], bytes[1], bytes[2], bytes[3]
                    ),
                    None => println!("entry bytes: unreadable"),
                }
            }
            Err(error) => println!("mapped: failed ({error})"),
        }
    }

    let image = miniloader::GuestImage::load(&parsed, page_size);
    match image {
        Ok(dry_run) => println!(
            "image: base=0x{:x} arena={} bytes entry_offset={:?}",
            dry_run.base_vaddr(),
            dry_run.arena().len(),
            dry_run.entry_offset()
        ),
        Err(error) => println!("image: not loadable ({error:?})"),
    }

    let built = build_stack(
        &[path.as_str()],
        &["TWOYI_ROOTFS=/data/data/io.twoyi/rootfs"],
        &[Auxv::new(AT_PAGESZ, page_size)],
        miniloader::stack::DEFAULT_MAX_BYTES,
    );
    match built {
        Ok(stack) => println!("initial stack: {} bytes", stack.size),
        Err(error) => {
            eprintln!("cannot build initial stack: {error:?}");
            std::process::exit(1);
        }
    }
}