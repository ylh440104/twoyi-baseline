#!/usr/bin/env python3
"""Locate RegisterNatives tables in an ELF shared object.

`libtwoyi.so` exports one symbol (`JNI_OnLoad`) because every native method is
registered dynamically. A registration table is an array of
`{ const char* name; const char* signature; void* fnPtr; }` triples.

Those tables live in a relocated data section, so the file itself contains zeros
where the pointers will be and the values only exist as `R_AARCH64_RELATIVE`
addends in `.rela.dyn`. This script therefore resolves relocations first and then
scans for pointers to the method-name strings.

Usage:
    python3 find_jni_table.py libtwoyi.so nativeResolve nativeHandle native-fence
"""

import struct
import sys

PT_LOAD = 1
SHT_RELA = 4
SHT_DYNSYM = 11
R_AARCH64_RELATIVE = 1027


def read_elf(path):
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("expected a 64-bit little-endian ELF: " + path)

    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize = struct.unpack_from("<H", data, 0x36)[0]
    phnum = struct.unpack_from("<H", data, 0x38)[0]
    segments = []
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type = struct.unpack_from("<I", data, base)[0]
        if p_type != PT_LOAD:
            continue
        p_offset = struct.unpack_from("<Q", data, base + 0x08)[0]
        p_vaddr = struct.unpack_from("<Q", data, base + 0x10)[0]
        p_filesz = struct.unpack_from("<Q", data, base + 0x20)[0]
        segments.append((p_vaddr, p_filesz, p_offset))

    shoff = struct.unpack_from("<Q", data, 0x28)[0]
    shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    shnum = struct.unpack_from("<H", data, 0x3C)[0]
    shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    sections = []
    if shoff and shnum:
        shstr_off = struct.unpack_from("<Q", data, shoff + shstrndx * shentsize + 0x18)[0]
        for index in range(shnum):
            base = shoff + index * shentsize
            name_off = struct.unpack_from("<I", data, base)[0]
            sh_type = struct.unpack_from("<I", data, base + 0x04)[0]
            sh_offset = struct.unpack_from("<Q", data, base + 0x18)[0]
            sh_size = struct.unpack_from("<Q", data, base + 0x20)[0]
            sh_addr = struct.unpack_from("<Q", data, base + 0x10)[0]
            end = data.find(b"\x00", shstr_off + name_off)
            name = data[shstr_off + name_off:end].decode("ascii", "replace")
            sections.append(
                {
                    "name": name,
                    "type": sh_type,
                    "offset": sh_offset,
                    "size": sh_size,
                    "addr": sh_addr,
                }
            )
    return data, segments, sections


def relocation_addends(data, sections):
    """offset (vaddr) -> addend for every R_AARCH64_RELATIVE relocation."""
    addends = {}
    for section in sections:
        if section["type"] != SHT_RELA or section["size"] == 0:
            continue
        count = section["size"] // 24
        for index in range(count):
            base = section["offset"] + index * 24
            offset, info, addend = struct.unpack_from("<QQq", data, base)
            if (info & 0xFFFFFFFF) == R_AARCH64_RELATIVE:
                addends[offset] = addend
    return addends


def word_at(data, segments, sections, vaddr, addends):
    """Read a pointer-sized word at a virtual address, honouring relocations."""
    if vaddr in addends:
        value = addends[vaddr]
        return value & 0xFFFFFFFFFFFFFFFF
    for seg_vaddr, seg_size, seg_offset in segments:
        if seg_vaddr <= vaddr < seg_vaddr + seg_size - 7:
            return struct.unpack_from("<Q", data, seg_offset + (vaddr - seg_vaddr))[0]
    return 0


def read_cstring(data, segments, vaddr, limit=200):
    for seg_vaddr, seg_size, seg_offset in segments:
        if seg_vaddr <= vaddr < seg_vaddr + seg_size:
            start = seg_offset + (vaddr - seg_vaddr)
            end = data.find(b"\x00", start, start + limit)
            if end < 0:
                return None
            return data[start:end].decode("utf-8", "replace")
    return None


def find_strings(data, segments, needles):
    found = {}
    for needle in needles:
        target = needle.encode()
        addresses = []
        start = 0
        while True:
            index = data.find(target, start)
            if index < 0:
                break
            if data[index + len(target): index + len(target) + 1] == b"\x00":
                for seg_vaddr, seg_size, seg_offset in segments:
                    if seg_offset <= index < seg_offset + seg_size:
                        addresses.append(seg_vaddr + (index - seg_offset))
                        break
            start = index + 1
        found[needle] = addresses
    return found


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    path = argv[1]
    needles = argv[2:]
    data, segments, sections = read_elf(path)
    addends = relocation_addends(data, sections)
    print("# segments: %d, sections: %d, relative relocations: %d"
          % (len(segments), len(sections), len(addends)))

    strings = find_strings(data, segments, needles)
    name_by_address = {}
    for needle, locations in strings.items():
        print("string %-26s %s" % (needle, ", ".join("0x%x" % a for a in locations) or "(absent)"))
        for address in locations:
            name_by_address[address] = needle

    if not name_by_address:
        print("no needles found")
        return 1

    print()
    print("# pointer scan (resolving R_AARCH64_RELATIVE)")
    entries = 0
    for seg_vaddr, seg_size, seg_offset in segments:
        # Candidate table slots are 8-byte aligned words inside the segment.
        start = seg_vaddr
        end = seg_vaddr + seg_size - 8
        for address in range(start, end, 8):
            word = word_at(data, segments, sections, address, addends)
            if word in name_by_address:
                signature_ptr = word_at(data, segments, sections, address + 8, addends)
                function_ptr = word_at(data, segments, sections, address + 16, addends)
                signature = read_cstring(data, segments, signature_ptr) or "?"
                entries += 1
                print(
                    "entry=0x%-10x name=%-26s sig=%-38s fn=0x%x"
                    % (address, name_by_address[word], signature, function_ptr)
                )
    print("# entries found: %d" % entries)
    return 0 if entries else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))