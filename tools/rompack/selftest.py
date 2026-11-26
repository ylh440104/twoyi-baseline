#!/usr/bin/env python3
"""``tools/rompack`` 的自检脚本：纯函数测试、负例容器、往返断言。

不依赖任何官方 ROM 内容，也不联网。分三段：

1. **纯 USTAR 层**（不需要 zstd）：归一化、排序、长路径 prefix 切分、校验和、
   两次构建逐字节一致，并用标准库 ``tarfile`` 独立交叉解析一遍。
2. **容器层负例**（需要 zstd CLI）：故意造出缺 BOM、绝对路径、``..``、重复条目、
   设备文件、多个 zstd 帧、顶层不是 ``rootfs/``、载荷被改坏的容器，逐个要求
   ``verify`` 返回非 0 且失败项正是预期的那一条。
3. **往返断言**（``--tree DIR``，由 CI 传入）：把 ``unpack`` 出来的树、清单 JSON
   与源树逐条比对——文件列表、权限位归一化、时间戳归一化、符号链接目标。

用法::

    python3 tools/rompack/selftest.py                      # 全部（有 zstd 时）
    python3 tools/rompack/selftest.py --skip-zstd          # 只跑纯 USTAR 层
    python3 tools/rompack/selftest.py --tree /tmp/rp        # 追加往返断言
"""

from __future__ import annotations

import argparse
import io
import json
import os
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import rompack  # noqa: E402  (同目录模块，需先改 sys.path)

TOP = rompack.TOP_NAME.decode()


class Checker:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.skipped = 0

    def check(self, name: str, condition, detail: str = "") -> bool:
        if condition in (None,):
            self.skipped += 1
            print(f"[skip] {name}: {detail}")
            return False
        if condition:
            self.passed += 1
            print(f"[ok]   {name}" + (f": {detail}" if detail else ""))
            return True
        self.failed += 1
        print(f"[FAIL] {name}" + (f": {detail}" if detail else ""))
        return False

    def summary(self) -> int:
        print(
            f"selftest: 结论：{self.passed} 项通过、{self.failed} 项失败、"
            f"{self.skipped} 项跳过"
        )
        return 1 if self.failed else 0


def zstd_program(zstd_cmd: str):
    words = shlex.split(zstd_cmd or "zstd")
    return shutil.which(words[0]) if words else None


def zstd_compress(data: bytes, program: str) -> bytes:
    completed = subprocess.run(
        [program, "-19", "-q", "-c"], input=data, capture_output=True
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "zstd 压缩失败：" + completed.stderr.decode("utf-8", "replace").strip()
        )
    return completed.stdout


# ------------------------------------------------------------------ 纯 USTAR 层


def build_sample_tree(root: Path) -> None:
    (root / "level1" / "level2").mkdir(parents=True)
    (root / "level1" / "empty").mkdir()
    (root / "run.sh").write_text("#!/bin/sh\necho twoyi-rompack\n", encoding="utf-8")
    os.chmod(root / "run.sh", 0o755)
    (root / "level1" / "level2" / "plain.txt").write_text("plain\n", encoding="utf-8")
    os.chmod(root / "level1" / "level2" / "plain.txt", 0o600)  # → 0644
    os.chmod(root / "level1" / "level2", 0o777)  # → 0755
    (root / "level1" / "level2" / "中文文件.txt").write_text("中文内容\n", encoding="utf-8")
    (root / "big.bin").write_bytes(bytes(range(256)) * 16)
    os.chmod(root / "big.bin", 0o640)  # → 0644
    os.symlink("../level1/big.bin", root / "level1" / "level2" / "rel-link")


def test_ustar_layer(checker: Checker) -> None:
    with tempfile.TemporaryDirectory(prefix="rompack-selftest-") as temp_dir:
        root = Path(temp_dir) / "src"
        root.mkdir()
        build_sample_tree(root)

        entries = rompack.scan_source(root)
        names = [entry["name"] for entry in entries]
        checker.check(
            "scan.normalized_modes",
            sorted({entry["mode"] for entry in entries}) == [0o644, 0o755, 0o777],
            "出现过的权限位：" + "、".join(oct(m) for m in sorted({e["mode"] for e in entries})),
        )
        checker.check(
            "scan.directories_first_and_sorted",
            names == sorted(names) and names[0] == TOP.encode() + b"/",
            f"{len(names)} 个条目按字节序排列，首条是 {TOP}/",
        )
        checker.check(
            "scan.normalization_detail",
            [entry["mode"] for entry in entries if entry["name"].endswith(b"plain.txt")]
            == [0o644]
            and [entry["mode"] for entry in entries if entry["name"].endswith(b"run.sh")]
            == [0o755]
            and [entry["mode"] for entry in entries if entry["name"].endswith(b"level2/")]
            == [0o755],
            "0600→0644、可执行→0755、0777 目录→0755",
        )
        link = [entry for entry in entries if entry["kind"] == "symlink"]
        checker.check(
            "scan.symlink_target_preserved",
            len(link) == 1 and link[0]["linkname"] == b"../level1/big.bin",
            "符号链接目标按原样写入（不解析）",
        )
        empty = [entry for entry in entries if entry["name"].endswith(b"empty/")]
        checker.check(
            "scan.empty_directory_kept",
            len(empty) == 1 and empty[0]["kind"] == "dir",
            "空目录保留为独立条目",
        )

        # 特殊文件必须被拒绝
        fifo_created = False
        try:
            os.mkfifo(root / "bad.fifo")
            fifo_created = True
        except OSError as error:  # pragma: no cover - 依赖平台
            checker.check("scan.rejects_special_file", None, f"无法创建 FIFO：{error}")
        if fifo_created:
            rejected = ""
            try:
                rompack.scan_source(root)
            except rompack.RomPackError as error:
                rejected = str(error)
            checker.check(
                "scan.rejects_special_file",
                "bad.fifo" in rejected,
                rejected or "没有拒绝 FIFO",
            )
            (root / "bad.fifo").unlink()

        buffer = io.BytesIO()
        stats = rompack.build_tar(rompack.scan_source(root), buffer)
        data = buffer.getvalue()
        again = io.BytesIO()
        rompack.build_tar(rompack.scan_source(root), again)
        checker.check(
            "tar.byte_identical_rebuild",
            again.getvalue() == data,
            f"{len(data)} 字节，两次构建逐字节一致",
        )
        checker.check(
            "tar.record_aligned",
            len(data) % rompack.RECORD_SIZE == 0 and stats["tarBytes"] == len(data),
            f"长度是 {rompack.RECORD_SIZE} 的整数倍",
        )
        checker.check(
            "tar.header_fields",
            data[257:263] == b"ustar\0"
            and data[263:265] == b"00"
            and rompack.header_checksum(data[:512])
            == int(data[148:156].split(b"\0")[0], 8),
            "第一块头：ustar\\0 + 00，校验和自洽",
        )

        # 用标准库 tarfile 独立解析：权限/mtime/属主/内容
        members = []
        try:
            with tarfile_open(data) as handle:
                members = handle.getmembers()
                checker.check(
                    "tar.stdlib_member_count",
                    len(members) == stats["entryCount"],
                    f"tarfile 解析到 {len(members)} 个条目",
                )
                checker.check(
                    "tar.stdlib_normalized",
                    all(
                        member.uid == 0
                        and member.gid == 0
                        and member.mtime == 0
                        and member.uname == ""
                        and member.gname == ""
                        for member in members
                    ),
                    "uid/gid=0、mtime=0、uname/gname 为空",
                )
                checker.check(
                    "tar.stdlib_top_dir",
                    {member.name.split("/")[0] for member in members} == {TOP},
                    f"顶层只有 {TOP}",
                )
                executable = [m for m in members if m.name.endswith("run.sh")]
                checker.check(
                    "tar.stdlib_exec_bit",
                    len(executable) == 1 and executable[0].mode == 0o755,
                    "可执行文件保持 0755",
                )
                payload = handle.extractfile(f"{TOP}/big.bin").read()
                checker.check(
                    "tar.content_roundtrip",
                    payload == (root / "big.bin").read_bytes(),
                    "文件内容按字节还原",
                )
        except Exception as error:  # noqa: BLE001 - 自检脚本需要报告而不是抛出
            checker.check("tar.stdlib_parse", False, f"{type(error).__name__}: {error}")

    # 长路径 prefix 切分
    long_name = b"rootfs/" + b"a" * 60 + b"/" + b"b" * 60 + b"/" + b"c" * 40
    header = rompack.ustar_header(long_name, 0o644, 0)
    prefix = header[345:500].split(b"\0")[0]
    short = header[0:100].split(b"\0")[0]
    checker.check(
        "ustar.long_name_prefix_split",
        len(prefix) <= 155
        and len(short) <= 100
        and (prefix + b"/" + short) == long_name
        and rompack.header_checksum(header) == int(header[148:156].split(b"\0")[0], 8),
        f"prefix {len(prefix)} 字节 + name {len(short)} 字节",
    )
    too_long = b"rootfs/" + b"x" * 300
    rejected = ""
    try:
        rompack.ustar_header(too_long, 0o644, 0)
    except rompack.RomPackError as error:
        rejected = str(error)
    checker.check("ustar.rejects_overlong_name", bool(rejected), rejected or "没有拒绝超长路径")

    # BOM 序列化稳定且带必需字段
    bom = rompack.bom_default(stats, rompack.DEFAULT_LEVEL, rompack.DEFAULT_WINDOW_LOG)
    checker.check(
        "bom.required_fields",
        all(key in bom for key in ("bomVersion", "entryCount", "totalBytes", "generator")),
        "含 bomVersion / entryCount / totalBytes / generator",
    )
    checker.check(
        "bom.stable_serialization",
        rompack.serialize_bom(bom) == rompack.serialize_bom(dict(bom)),
        "同一份 BOM 序列化字节稳定（键排序、紧凑分隔符）",
    )
    frame = rompack.encode_skippable(rompack.serialize_bom(bom), 0)
    checker.check(
        "skippable.magic_and_length",
        frame[:4] == b"\x50\x2a\x4d\x18"
        and int.from_bytes(frame[4:8], "little") == len(frame) - 8,
        f"magic=0x184D2A50，载荷 {len(frame) - 8} 字节",
    )


# ------------------------------------------- 容器解析 / tar 检查（都不需要 zstd）


def build_raw_zstd_frame(content: bytes, window_log: int = 16, single_segment: bool = False,
                          checksum: bool = True) -> bytes:
    """手搓一个**合法** zstd 帧（只用 raw 块），用来单测解析器而不需要压缩器。"""
    if not single_segment and not 10 <= window_log <= 31:
        raise ValueError("window_log 越界")
    payload = b""
    if single_segment:
        descriptor = (0 << 6) | 0x20 | (0x04 if checksum else 0)  # fcs_flag=0 → 1 字节 FCS
        header = bytes([descriptor])
        if len(content) > 255:
            raise ValueError("单段帧的 1 字节 FCS 只能表示 <256 字节")
        header += bytes([len(content)])
    else:
        descriptor = (2 << 6) | (0x04 if checksum else 0)  # fcs_flag=2 → 4 字节 FCS
        header = bytes([descriptor, (window_log - 10) << 3])
        header += len(content).to_bytes(4, "little")
    block_size = len(content)
    if block_size > 4096:
        raise ValueError("raw 块过大")
    block_header = (1 | (0 << 1) | (block_size << 3)).to_bytes(3, "little")
    payload = header + block_header + content
    if checksum:
        payload += b"\x00\x00\x00\x00"
    return b"\x28\xb5\x2f\xfd" + payload


def test_container_parsing(checker: Checker) -> None:
    content = b"A" * 1024
    framed = build_raw_zstd_frame(content, window_log=16, checksum=True)
    with tempfile.TemporaryFile() as handle:
        handle.write(framed)
        handle.flush()
        handle.seek(0)
        frame = rompack.parse_zstd_frame(handle, 0, len(framed))
    checker.check(
        "container.parse_frame_header",
        frame["single_segment"] is False
        and frame["window_log"] == 16
        and frame["window_size"] == 1 << 16
        and frame["content_size"] == len(content)
        and frame["has_checksum"] is True
        and frame["blocks"] == 1
        and frame["end"] == len(framed),
        f"windowLog=16、contentSize={len(content)}、1 块、带 XXH64、边界精确",
    )

    single = build_raw_zstd_frame(b"B" * 200, single_segment=True, checksum=False)
    with tempfile.TemporaryFile() as handle:
        handle.write(single)
        handle.flush()
        handle.seek(0)
        info = rompack.parse_zstd_frame(handle, 0, len(single))
    checker.check(
        "container.parse_single_segment",
        info["single_segment"] is True
        and info["window_log"] is None
        and info["window_size"] == 200
        and info["has_checksum"] is False
        and info["end"] == len(single),
        "单段帧：窗口=内容长度、无 XXH64",
    )

    bom_frame = rompack.encode_skippable(b'{"bomVersion":"x"}', 0)
    pad_frame = rompack.encode_skippable(b"\0" * 8, 1)
    layout = bom_frame + pad_frame + framed
    with tempfile.TemporaryFile() as handle:
        handle.write(layout)
        handle.flush()
        handle.seek(0)
        container = rompack.parse_container(handle, len(layout))
    checker.check(
        "container.parse_layout",
        len(container["skippables"]) == 2
        and len(container["frames"]) == 1
        and container["frames"][0]["offset"] == len(bom_frame) + len(pad_frame)
        and rompack.find_boms(container)[0]["bom"] == {"bomVersion": "x"},
        "2 个 skippable + 1 个 zstd 帧，且能读出 BOM JSON",
    )

    for label, data in (
        ("trailing_garbage", layout + b"junk"),
        ("short_magic", b"\x28\xb5"),
        ("unknown_magic", b"\xff\xff\xff\xff" + b"\0" * 16),
    ):
        rejected = ""
        with tempfile.TemporaryFile() as handle:
            handle.write(data)
            handle.flush()
            handle.seek(0)
            try:
                rompack.parse_container(handle, len(data))
            except rompack.RomPackError as error:
                rejected = str(error)
        checker.check(f"container.rejects_{label}", bool(rejected), rejected)

    two = framed + framed
    with tempfile.TemporaryFile() as handle:
        handle.write(two)
        handle.flush()
        handle.seek(0)
        container = rompack.parse_container(handle, len(two))
    checker.check(
        "container.counts_two_frames",
        len(container["frames"]) == 2,
        f"识别出 {len(container['frames'])} 个 zstd 帧（verify 会判不合格）",
    )


def payload_failures(tar_data: bytes, bom=None) -> set:
    """直接喂 tar 字节给 verify 的载荷检查，不经 zstd。"""
    report = rompack.Report(verbose=False)
    original = sys.stdout
    try:
        sys.stdout = io.StringIO()
        rompack._verify_payload(io.BytesIO(tar_data), report, bom or {})
    except rompack.RomPackError:
        return {"<exception>"}
    finally:
        sys.stdout = original
    return {check["name"] for check in report.checks if check["status"] == "fail"}


GOOD_TAR_ITEMS = [
    {"name": b"rootfs/", "typeflag": b"5", "mode": 0o755},
    {"name": b"rootfs/etc/", "typeflag": b"5", "mode": 0o755},
    {"name": b"rootfs/etc/build.prop", "data": b"ro.build.id=OC\n"},
    {"name": b"rootfs/system/bin/sh", "typeflag": b"2", "mode": 0o777, "linkname": b"/system/bin/mksh"},
]


def test_tar_checks(checker: Checker) -> None:
    good = build_raw_tar(GOOD_TAR_ITEMS)
    checker.check(
        "checks.accepts_good_tar", payload_failures(good) == set(), "合格载荷没有任何失败项"
    )

    cases = [
        ("absolute_path", [{"name": b"/etc/passwd", "data": b"x"}], "tar.no_absolute_path"),
        ("parent_component", [{"name": b"rootfs/../escape", "data": b"x"}], "tar.no_parent_component"),
        (
            "duplicate_entries",
            [{"name": b"rootfs/dup", "data": b"a"}, {"name": b"rootfs/dup", "data": b"b"}],
            "tar.no_duplicate_entries",
        ),
        (
            "device_file",
            [{"name": b"rootfs/dev/null", "typeflag": b"3", "mode": 0o666}],
            "tar.no_device_or_special",
        ),
        ("wrong_top_dir", [{"name": b"system/build.prop", "data": b"x"}], "tar.top_level_rootfs"),
    ]
    for label, extra, expected in cases:
        failures = payload_failures(build_raw_tar(GOOD_TAR_ITEMS[:1] + extra))
        checker.check(
            f"checks.rejects_{label}", expected in failures, f"命中 {sorted(failures)}" if failures else "无失败项"
        )

    # uid/gid 未归一化
    buffer = io.BytesIO()
    buffer.write(rompack.ustar_header(b"rootfs/", 0o755, 0, b"5", uid=1000, gid=1000))
    buffer.write(rompack.ustar_header(b"rootfs/bad", 0o644, 1, b"0"))
    buffer.write(b"x" + b"\0" * 511)
    buffer.write(rompack.ZERO_TRAILER)
    failures = payload_failures(buffer.getvalue())
    checker.check("checks.rejects_owner", "tar.normalized_owner" in failures, f"命中 {sorted(failures)}")

    # mtime 未归一化
    buffer = io.BytesIO()
    buffer.write(rompack.ustar_header(b"rootfs/", 0o755, 0, b"5", mtime=1700000000))
    buffer.write(rompack.ustar_header(b"rootfs/bad", 0o644, 1, b"0"))
    buffer.write(b"x" + b"\0" * 511)
    buffer.write(rompack.ZERO_TRAILER)
    failures = payload_failures(buffer.getvalue())
    checker.check("checks.rejects_mtime", "tar.normalized_mtime" in failures, f"命中 {sorted(failures)}")

    # 头部校验和被破坏（改一位 mode 数字，仍是合法八进制）
    corrupted = bytearray(good)
    corrupted[105] ^= 0x01
    failures = payload_failures(bytes(corrupted))
    checker.check(
        "checks.rejects_header_checksum", "tar.header_checksums" in failures, f"命中 {sorted(failures)}"
    )

    # BOM 统计与载荷不一致
    failures = payload_failures(
        good, {"bomVersion": "x", "entryCount": 99, "tarBytes": len(good) + 1}
    )
    checker.check(
        "checks.rejects_bom_mismatch",
        "bom.entry_count_matches" in failures and "bom.tar_bytes_match" in failures,
        f"命中 {sorted(failures)}",
    )


class tarfile_open:
    """``with tarfile_open(bytes) as tf`` 的小包装。"""

    def __init__(self, data: bytes):
        import tarfile

        self._tarfile = tarfile
        self._data = data
        self._handle = None

    def __enter__(self):
        self._handle = self._tarfile.open(fileobj=io.BytesIO(self._data), mode="r:")
        return self._handle

    def __exit__(self, *exc_info):
        self._handle.close()
        return False


# ------------------------------------------------------------------ 容器层负例


def build_raw_tar(items) -> bytes:
    """用最底层原语拼 tar，允许造出畸形条目（verify 必须拒绝）。"""
    buffer = io.BytesIO()
    for item in items:
        data = item.get("data", b"")
        buffer.write(
            rompack.ustar_header(
                item["name"],
                item.get("mode", 0o644),
                len(data),
                item.get("typeflag", b"0"),
                linkname=item.get("linkname", b""),
            )
        )
        if data:
            buffer.write(data)
            buffer.write(b"\0" * ((-len(data)) % rompack.BLOCK_SIZE))
    written = buffer.tell() + len(rompack.ZERO_TRAILER)
    buffer.write(rompack.ZERO_TRAILER)
    buffer.write(b"\0" * ((-written) % rompack.RECORD_SIZE))
    return buffer.getvalue()


def write_container(path: Path, tar_data: bytes, program: str, bom=None, extra_frames=0, tail=b"") -> None:
    compressed = zstd_compress(tar_data, program)
    with open(path, "wb") as handle:
        if bom is not None:
            handle.write(rompack.encode_skippable(rompack.serialize_bom(bom), 0))
        handle.write(compressed)
        for index in range(extra_frames):
            handle.write(zstd_compress(b"extra frame %d\n" % index, program))
        handle.write(tail)


def run_verify(source: Path) -> tuple:
    """跑一次 verify，返回 (退出码, 失败检查名集合, 汇总文本)。"""
    report_path = source.with_suffix(".report.json")
    buffer = io.StringIO()
    original = sys.stdout
    code = 1
    try:
        sys.stdout = buffer
        code = rompack.verify(str(source), report_path=str(report_path))
    except rompack.RomPackError as error:  # verify 自身没法开始（例如没有 zstd）
        sys.stdout = original
        return 1, {"<exception>"}, str(error)
    finally:
        sys.stdout = original
    document = json.loads(report_path.read_text(encoding="utf-8"))
    failed = {
        check["name"] for check in document["checks"] if check["status"] == "fail"
    }
    return code, failed, document


def test_container_cases(checker: Checker, program: str) -> None:
    with tempfile.TemporaryDirectory(prefix="rompack-negative-") as temp_dir:
        directory = Path(temp_dir)
        good_tar = build_raw_tar(GOOD_TAR_ITEMS)
        bom = {
            "bomVersion": "dsh-rompack-selftest",
            "entryCount": len(GOOD_TAR_ITEMS),
            "generator": rompack.GENERATOR,
        }

        good = directory / "good.tar.zst"
        write_container(good, good_tar, program, bom=bom)
        code, failed, document = run_verify(good)
        checker.check(
            "verify.accepts_wellformed",
            code == 0 and not failed,
            f"退出码 {code}，{document['summary']['ok']} 项通过，0 项失败",
        )
        checker.check(
            "verify.detects_bom_stats",
            document["bom"] is not None
            and document["summary"]["entries"] == len(GOOD_TAR_ITEMS)
            and document["summary"]["bytes"] == len(good_tar),
            f"BOM 数量与 tar 长度（{len(good_tar)} 字节）一致",
        )

        cases = []

        no_bom = directory / "no-bom.tar.zst"
        write_container(no_bom, good_tar, program)
        cases.append(("rejects_missing_bom", no_bom, "container.skippable_frames"))

        absolute = directory / "absolute.tar.zst"
        write_container(
            absolute,
            build_raw_tar(
                [{"name": b"rootfs/", "typeflag": b"5", "mode": 0o755}, {"name": b"/etc/passwd", "data": b"x"}]
            ),
            program,
            bom=bom,
        )
        cases.append(("rejects_absolute_path", absolute, "tar.no_absolute_path"))

        parent = directory / "parent.tar.zst"
        write_container(
            parent,
            build_raw_tar(
                [
                    {"name": b"rootfs/", "typeflag": b"5", "mode": 0o755},
                    {"name": b"rootfs/../escape", "data": b"x"},
                ]
            ),
            program,
            bom=bom,
        )
        cases.append(("rejects_parent_component", parent, "tar.no_parent_component"))

        duplicate = directory / "duplicate.tar.zst"
        write_container(
            duplicate,
            build_raw_tar(
                [
                    {"name": b"rootfs/", "typeflag": b"5", "mode": 0o755},
                    {"name": b"rootfs/dup", "data": b"a"},
                    {"name": b"rootfs/dup", "data": b"b"},
                ]
            ),
            program,
            bom=bom,
        )
        cases.append(("rejects_duplicate_entries", duplicate, "tar.no_duplicate_entries"))

        device = directory / "device.tar.zst"
        write_container(
            device,
            build_raw_tar(
                [
                    {"name": b"rootfs/", "typeflag": b"5", "mode": 0o755},
                    {"name": b"rootfs/dev/null", "typeflag": b"3", "mode": 0o666},
                ]
            ),
            program,
            bom=bom,
        )
        cases.append(("rejects_device_file", device, "tar.no_device_or_special"))

        two_frames = directory / "two-frames.tar.zst"
        write_container(two_frames, good_tar, program, bom=bom, extra_frames=1)
        cases.append(("rejects_two_zstd_frames", two_frames, "container.single_zstd_frame"))

        wrong_top = directory / "wrong-top.tar.zst"
        write_container(
            wrong_top,
            build_raw_tar(
                [
                    {"name": b"system/", "typeflag": b"5", "mode": 0o755},
                    {"name": b"system/build.prop", "data": b"x"},
                ]
            ),
            program,
            bom=bom,
        )
        cases.append(("rejects_wrong_top_dir", wrong_top, "tar.top_level_rootfs"))

        for name, source, expected in cases:
            code, failed, _document = run_verify(source)
            checker.check(
                f"verify.{name}",
                code != 0 and expected in failed,
                f"退出码 {code}，命中 {sorted(failed)}" if failed else f"退出码 {code}，无失败项",
            )

        # 载荷被改坏：XXH64 / 解压必须报错
        broken = directory / "broken.tar.zst"
        write_container(broken, good_tar, program, bom=bom)
        raw = bytearray(broken.read_bytes())
        offset = 8 + len(rompack.serialize_bom(bom)) + 120  # 落在 zstd 帧内部的块数据里
        if offset < len(raw):
            raw[offset] ^= 0xFF
        broken.write_bytes(bytes(raw))
        code, failed, _document = run_verify(broken)
        expected_any = {"payload.zstd_integrity", "payload.readable", "tar.header_checksums"}
        checker.check(
            "verify.rejects_corrupted_payload",
            code != 0 and bool(failed & expected_any),
            f"退出码 {code}，命中 {sorted(failed)}",
        )

        # 尾部垃圾
        trailing = directory / "trailing.tar.zst"
        write_container(trailing, good_tar, program, bom=bom, tail=b"\xde\xad\xbe\xef")
        code, failed, _document = run_verify(trailing)
        checker.check(
            "verify.rejects_trailing_garbage",
            code != 0,
            f"退出码 {code}，命中 {sorted(failed)}",
        )


# ---------------------------------------------------------------- 往返断言


def expected_mode(info: os.stat_result) -> int:
    if stat.S_ISLNK(info.st_mode):
        return 0o777
    if stat.S_ISDIR(info.st_mode):
        return 0o755
    if info.st_mode & 0o111:
        return 0o755
    return 0o644


def walk_source(root: Path):
    """按打包规则列出期望条目：{name: (kind, mode)}"""
    expected = {f"{TOP}/": ("dir", 0o755, 0)}
    for directory, dirnames, filenames in os.walk(root):
        dirnames.sort()
        filenames.sort()
        relative = os.path.relpath(directory, root)
        prefix = "" if relative == "." else relative.replace(os.sep, "/") + "/"
        for name in dirnames + filenames:
            path = Path(directory) / name
            info = os.lstat(path)
            if stat.S_ISLNK(info.st_mode):
                expected[f"{TOP}/{prefix}{name}"] = ("symlink", 0o777, 0)
                continue
            kind = "dir" if stat.S_ISDIR(info.st_mode) else "file"
            key = f"{TOP}/{prefix}{name}" + ("/" if kind == "dir" else "")
            expected[key] = (kind, expected_mode(info), 0 if kind == "dir" else info.st_size)
    return expected


def test_roundtrip(checker: Checker, tree: Path) -> None:
    src = tree / "src"
    out = tree / "out-a"
    manifest_path = tree / "manifest-a.json"
    for required in (src, out, manifest_path):
        if not required.exists():
            checker.check("roundtrip.inputs", False, f"缺少 {required}")
            return

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = manifest["entries"]
    expected = walk_source(src)
    actual = {
        (entry["name"] + "/" if entry["kind"] == "dir" and not entry["name"].endswith("/") else entry["name"]): entry
        for entry in entries
    }
    checker.check(
        "roundtrip.file_list",
        set(actual) == set(expected),
        f"清单 {len(actual)} 条 vs 期望 {len(expected)} 条"
        + (
            ""
            if set(actual) == set(expected)
            else f"；多 {sorted(set(actual) - set(expected))[:3]} 少 {sorted(set(expected) - set(actual))[:3]}"
        ),
    )

    bad_mode = []
    bad_mtime = []
    for name, (kind, mode, size) in sorted(expected.items()):
        entry = actual.get(name)
        if entry is None:
            continue
        if entry["kind"] != kind or entry["mode"] != mode:
            bad_mode.append(f"{name}: {entry['kind']}/{oct(entry['mode'])} 期望 {kind}/{oct(mode)}")
        if entry["mtime"] != 0:
            bad_mtime.append(f"{name}: mtime={entry['mtime']}")
    checker.check(
        "roundtrip.manifest_modes",
        not bad_mode,
        "；".join(bad_mode[:3]) if bad_mode else "清单里的类型与权限位全部归一化正确",
    )

    # 直接看落盘结果（不信任清单）
    disk_mode = []
    disk_mtime = []
    for name, (kind, mode, _size) in sorted(expected.items()):
        path = out / name.rstrip("/")
        if not os.path.lexists(path):
            disk_mode.append(f"{name}: 不存在")
            continue
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode):
            if os.readlink(path) != os.readlink(src / name[len(TOP) + 1 :].rstrip("/")):
                disk_mode.append(f"{name}: 符号链接目标不一致")
            continue
        if (info.st_mode & 0o7777) != mode:
            disk_mode.append(f"{name}: {oct(info.st_mode & 0o7777)} 期望 {oct(mode)}")
        if int(info.st_mtime) != 0:
            disk_mtime.append(f"{name}: {int(info.st_mtime)}")
    checker.check(
        "roundtrip.disk_modes",
        not disk_mode,
        "；".join(disk_mode[:3]) if disk_mode else "解出文件的落盘权限位全部正确",
    )
    checker.check(
        "roundtrip.disk_mtime_zero",
        not disk_mtime,
        "；".join(disk_mtime[:3]) if disk_mtime else "解出文件与目录的 mtime 全为 0",
    )

    # 内容与符号链接
    content_bad = []
    for name, (kind, _mode, size) in sorted(expected.items()):
        if kind != "file" or size == 0:
            continue
        source_file = src / name[len(TOP) + 1 :]
        target_file = out / name
        if source_file.stat().st_size > 1 << 20 and not name.endswith("big.bin"):
            continue
        if source_file.read_bytes() != target_file.read_bytes():
            content_bad.append(name)
    checker.check(
        "roundtrip.contents",
        not content_bad,
        "；".join(content_bad[:3]) if content_bad else "普通文件内容逐字节一致",
    )

    # BOM 与容器字段
    bom = manifest["bom"]
    checker.check(
        "roundtrip.bom_fields",
        isinstance(bom, dict)
        and bom.get("entryCount") == len(entries)
        and bom.get("payloadFormat") == "ustar"
        and str(bom.get("topDir")) == TOP,
        f"BOM entryCount={bom.get('entryCount')}、payloadFormat={bom.get('payloadFormat')}",
    )
    frames = manifest["container"]["zstdFrames"]
    checker.check(
        "roundtrip.single_frame_with_checksum",
        len(frames) == 1 and frames[0]["hasChecksum"],
        f"zstd 帧 {len(frames)} 个，XXH64={'有' if frames and frames[0]['hasChecksum'] else '无'}",
    )
    if len(frames) == 1 and frames[0]["contentSize"] is not None:
        checker.check(
            "roundtrip.content_size_matches_bom",
            frames[0]["contentSize"] == bom.get("tarBytes"),
            f"帧内 contentSize={frames[0]['contentSize']}，BOM tarBytes={bom.get('tarBytes')}",
        )
    else:  # pragma: no cover - 单段帧/无 contentSize 时跳过
        checker.check("roundtrip.content_size_matches_bom", None, "帧头没有 content size 字段")


# --------------------------------------------------------------------- 入口


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--zstd-cmd", default="zstd", help="zstd 可执行文件，默认 zstd")
    parser.add_argument("--skip-zstd", action="store_true", help="只跑不需要 zstd 的纯 USTAR 层")
    parser.add_argument("--tree", help="pack/unpack 的工作目录（含 src/、out-a/、manifest-a.json）")
    args = parser.parse_args(argv)

    checker = Checker()
    print("== 纯 USTAR 层（不需要 zstd）")
    test_ustar_layer(checker)
    print("== 容器解析 / tar 检查（不需要 zstd）")
    test_container_parsing(checker)
    test_tar_checks(checker)

    program = None if args.skip_zstd else zstd_program(args.zstd_cmd)
    if program:
        print(f"== 容器层（zstd: {program}）")
        test_container_cases(checker, program)
    elif args.skip_zstd:
        print("== 容器层：--skip-zstd，跳过")
    else:
        checker.check(
            "container.zstd_available",
            False,
            f"找不到 zstd（{args.zstd_cmd}）：容器层无法自检。"
            "CI 里应先 apt-get install -y zstd，或用 --zstd-cmd 指定。",
        )

    if args.tree:
        print(f"== 往返断言（{args.tree}）")
        test_roundtrip(checker, Path(args.tree))

    return checker.summary()


if __name__ == "__main__":
    rompack._configure_stdio()
    sys.exit(main())