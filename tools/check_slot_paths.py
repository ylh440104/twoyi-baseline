#!/usr/bin/env python3
"""Guard: guest state paths must come from the slot layout, not the data directory.

Slot 0 lives on the app data dir, extra slots live in <data>/slots/<n>. Any code
that rebuilds rootfs/log/loader/socket paths straight from getDataDir() silently
pins itself to slot 0 and collides with a second guest. This check fails when
such a derivation reappears.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "app/src/main/java"

GUEST_NAMES = ("rootfs", "log.txt", "last_kmsg.txt", "loader64", "socket")

# Defining the layout itself is the one place allowed to join the data dir.
LAYOUT_FILES = {
    "app/src/main/java/io/twoyi/GuestSlotLayout.java",
    "app/src/main/java/io/twoyi/utils/GuestInstanceRegistry.java",
}

forbidden = [
    # RomInstallTransaction must be pointed at a slot base, never the data dir.
    (re.compile(r"RomInstallTransaction\.(begin|current)\(\s*context\.getDataDir\(\)"),
     "ROM transaction anchored on the data directory"),
    (re.compile(r"RomInstallTransaction\.(begin|current)\(\s*\w+\.getDataDir\(\)"),
     "ROM transaction anchored on the data directory"),
]


def main() -> int:
    problems = []

    for path in sorted(SOURCES.rglob("*.java")):
        rel = path.relative_to(ROOT).as_posix()
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            for pattern, message in forbidden:
                if pattern.search(line):
                    problems.append(f"{rel}:{number}: {message}: {stripped}")

        if rel in LAYOUT_FILES:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            match = re.search(r"getDataDir\(\)\s*,\s*\"([^\"]+)\"", line)
            if match and match.group(1) in GUEST_NAMES:
                problems.append(
                    f"{rel}:{number}: guest path rebuilt from the data dir "
                    f"({match.group(1)}); use RomManager.getSlotBaseDir")

    # The slot-aware helpers must stay in place; removing them would let every
    # caller drift back to the data directory.
    rom = (SOURCES / "io/twoyi/utils/RomManager.java").read_text(encoding="utf-8")
    if "getSlotBaseDir(Context context, int slot)" not in rom:
        problems.append("RomManager lost the explicit-slot base directory helper")
    if "installRootfsForSlot" not in rom:
        problems.append("RomManager lost the per-slot install entry point")
    logs = (SOURCES / "io/twoyi/utils/LogEvents.java").read_text(encoding="utf-8")
    if "getSlotBaseDir" not in logs:
        problems.append("LogEvents no longer reads guest logs through the slot base")

    if problems:
        print("\n".join(problems))
        print(f"FAIL: {len(problems)} guest path derivation problem(s)")
        return 1
    print("PASS: guest state paths are derived from the slot layout only")
    return 0


if __name__ == "__main__":
    sys.exit(main())