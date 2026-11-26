/* SPDX-License-Identifier: MPL-2.0 */
//! Command line front end: print the mapping and stack plan for an ELF file.
//!
//! This is a diagnostic tool, not the runtime. It lets a developer inspect what
//! the loader would do to a guest binary without any privileged operation.


use miniloader::{build_stack, elf::Elf, map::plan, stack::Auxv, stack::AT_PAGESZ};

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
    let image = match Elf::parse(&bytes) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot parse {path}: {error:?}");
            std::process::exit(1);
        }
    };
    println!("entry: 0x{:x}", image.entry);
    println!("entry executable: {}", image.entry_is_executable());
    for (index, segment) in image.segments.iter().enumerate() {
        println!(
            "seg[{index}] vaddr=0x{:x} filesz={} memsz={} bss={} flags=0x{:x}",
            segment.vaddr,
            segment.filesz,
            segment.memsz,
            segment.bss_len(),
            segment.flags
        );
    }

    let mapping = match plan(&image.segments, image.entry, page_size) {
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