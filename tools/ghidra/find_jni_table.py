#!/usr/bin/env python3
"""Locate RegisterNatives tables in an ELF shared object.

`libtwoyi.so` exports one symbol (`JNI_OnLoad`) because every native method is
registered dynamically. The registration table is an array of
`{ const char* name; const char* signature; void* fnPtr; }` triples, so finding the
address of a method name string and scanning the data for a pointer to it recovers
the function that implements it.

Usage:
    python3 find_jni_table.py libtwoyi.so nativeResolve nativeHandle native-fence
"""

import struct
import sys

PT_LOAD = 1


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
    return data, segments


def read_cstring(data, segments, vaddr, limit=256):
    for seg_vaddr, seg_size, seg_offset in segments:
        if seg_vaddr <= vaddr < seg_vaddr + seg_size:
            start = seg_offset + (vaddr - seg_vaddr)
            end = data.find(b"\x00", start, start + limit)
            if end < 0:
                return None
            return data[start:end].decode("utf-8", "replace")
    return None


def find_strings(data, segments, needles):
    """Map a needle to the virtual addresses of every occurrence."""
    found = {}
    for needle in needles:
        target = needle.encode()
        addresses = []
        start = 0
        while True:
            index = data.find(target, start)
            if index < 0:
                break
            # Only accept occurrences that are NUL-terminated and inside a segment.
            if data[index + len(target): index + len(target) + 1] == b"\x00":
                for seg_vaddr, seg_size, seg_offset in segments:
                    if seg_offset <= index < seg_offset + seg_size:
                        addresses.append(seg_vaddr + (index - seg_offset))
                        break
            start = index + 1
        found[needle] = addresses
    return found


def scan_for_pointers(data, segments, targets):
    """Find data words equal to any target address; return (name, offset_of_word)."""
    hits = []
    for seg_vaddr, seg_size, seg_offset in segments:
        # Only the writable/data segment can hold a table in practice, but scan all.
        for offset in range(seg_offset, seg_offset + seg_size - 8, 8):
            word = struct.unpack_from("<Q", data, offset)[0]
            if word in targets:
                hits.append((targets[word], seg_vaddr + (offset - seg_offset), offset))
    return hits


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    path = argv[1]
    needles = argv[2:]
    data, segments = read_elf(path)
    print("# segments:")
    for seg_vaddr, seg_size, seg_offset in segments:
        print("#   vaddr=0x%x size=0x%x file=0x%x" % (seg_vaddr, seg_size, seg_offset))

    strings = find_strings(data, segments, needles)
    addresses = {}
    for needle, locations in strings.items():
        print("string %-16s at %s" % (needle, ", ".join("0x%x" % a for a in locations) or "(absent)"))
        for address in locations:
            addresses[address] = needle

    if not addresses:
        print("no needles found; nothing to scan")
        return 1

    print()
    print("# pointer candidates (a JNI entry is name pointer, signature pointer, fn pointer)")
    hits = scan_for_pointers(data, segments, addresses)
    if not hits:
        print("no data word points at those strings")
        return 1
    for needle, where, offset in hits:
        signature_ptr = struct.unpack_from("<Q", data, offset + 8)[0]
        function_ptr = struct.unpack_from("<Q", data, offset + 16)[0]
        signature = read_cstring(data, segments, signature_ptr)
        print("name=%-16s entry=0x%x sig_ptr=0x%x sig=%s fn=0x%x"
              % (needle, where, signature_ptr, signature if signature else "?", function_ptr))
        print("  fn file offset = 0x%x" % (function_ptr - segment_base_for(segments, function_ptr)))
    return 0


def segment_base_for(segments, vaddr):
    for seg_vaddr, seg_size, seg_offset in segments:
        if seg_vaddr <= vaddr < seg_vaddr + seg_size:
            return seg_vaddr - seg_offset
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))