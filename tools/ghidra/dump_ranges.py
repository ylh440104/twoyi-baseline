#!/usr/bin/env python3
"""Hex dump byte ranges of an ELF file by virtual address.

The loader keeps its strings XOR-encoded in .data and its import table in
.got.plt, none of which Ghidra's text export reproduces. This prints the raw
bytes of the ranges named on the command line so the literals can be decoded
offline without shipping the binary anywhere.

Usage:
    python3 tools/ghidra/dump_ranges.py libloader.so 0x2e0d0-0x2f100 ...

Addresses may also be given relative to the image base used by Ghidra, which is
0x100000 for these AArch64 images; pass --ghidra-base to subtract it.
"""

import struct
import sys


def load_segments(path):
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:4] != b"\x7fELF":
        raise SystemExit("not an ELF file: " + path)
    is64 = data[4] == 2
    little = data[5] == 1
    endian = "<" if little else ">"
    if is64:
        phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
        phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
        phnum = struct.unpack_from(endian + "H", data, 0x38)[0]
    else:
        phoff = struct.unpack_from(endian + "I", data, 0x1C)[0]
        phentsize = struct.unpack_from(endian + "H", data, 0x2A)[0]
        phnum = struct.unpack_from(endian + "H", data, 0x2C)[0]
    segments = []
    for index in range(phnum):
        base = phoff + index * phentsize
        if is64:
            p_type = struct.unpack_from(endian + "I", data, base)[0]
            if p_type != 1:  # PT_LOAD
                continue
            p_offset = struct.unpack_from(endian + "Q", data, base + 0x08)[0]
            p_vaddr = struct.unpack_from(endian + "Q", data, base + 0x10)[0]
            p_filesz = struct.unpack_from(endian + "Q", data, base + 0x20)[0]
        else:
            p_type = struct.unpack_from(endian + "I", data, base)[0]
            if p_type != 1:
                continue
            p_offset = struct.unpack_from(endian + "I", data, base + 0x04)[0]
            p_vaddr = struct.unpack_from(endian + "I", data, base + 0x08)[0]
            p_filesz = struct.unpack_from(endian + "I", data, base + 0x10)[0]
        segments.append((p_vaddr, p_vaddr + p_filesz, p_offset, p_filesz))
    return data, segments


def read_vaddr(data, segments, address, size):
    """Read a range that may span several PT_LOAD segments; gaps become zeros."""
    out = bytearray()
    cursor = address
    end_address = address + size
    while cursor < end_address:
        matched = None
        for seg_start, seg_end, seg_offset, _ in segments:
            if seg_start <= cursor < seg_end:
                matched = (seg_start, seg_end, seg_offset)
                break
        if matched is None:
            # Skip to the next segment that starts after the cursor.
            following = [s for s in segments if s[0] > cursor]
            if not following:
                out.extend(b"\x00" * (end_address - cursor))
                break
            next_start = min(s[0] for s in following)
            gap = min(next_start, end_address) - cursor
            out.extend(b"\x00" * gap)
            cursor += gap
            continue
        seg_start, seg_end, seg_offset = matched
        take = min(seg_end, end_address) - cursor
        start_in_segment = cursor - seg_start
        out.extend(data[seg_offset + start_in_segment: seg_offset + start_in_segment + take])
        cursor += take
    return bytes(out)


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    path = argv[1]
    ghidra_base = 0
    ranges = []
    for argument in argv[2:]:
        if argument.startswith("--ghidra-base="):
            ghidra_base = int(argument.split("=", 1)[1], 0)
            continue
        low, _, high = argument.partition("-")
        ranges.append((int(low, 0) - ghidra_base, int(high, 0) - ghidra_base))
    data, segments = load_segments(path)
    print("# segments: " + ", ".join("vaddr=0x%x..0x%x file=0x%x" % (s[0], s[1], s[2]) for s in segments))
    for low, high in ranges:
        size = high - low
        chunk = read_vaddr(data, segments, low, size)
        print("\n# range 0x%x..0x%x (%d bytes requested, %d available)" % (low, high, size, len(chunk)))
        for line_start in range(0, len(chunk), 16):
            row = chunk[line_start:line_start + 16]
            hex_part = " ".join("%02x" % byte for byte in row)
            text_part = "".join(chr(byte) if 32 <= byte < 127 else "." for byte in row)
            print("%08x  %-47s  %s" % (low + line_start, hex_part, text_part))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))