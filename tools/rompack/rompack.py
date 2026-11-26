#!/usr/bin/env python3
"""twoyi ROM 打包 / 解包 / 结构校验工具（阶段二）。

产出物是一个 ``rootfs.tar.zst`` 容器，结构与官方最新版 ``assets/rootfs.tar.zst``
一致（实测记录见 ``docs/ROM_FORMAT.md``）：

     [skippable frame 0: BOM JSON] [可选 skippable frame 1: 填充] [单个 zstd 帧]

zstd 帧里是归一化 USTAR 载荷：

* 顶层只有一个目录 ``rootfs/``；
* uid/gid 全 0、uname/gname 为空；
* mtime 全 0（即 1970-01-01T00:00:00Z）；
* 常规文件 0644，带可执行位的文件 0755，目录 0755，符号链接 0777 且保留目标；
* 条目按名称（字节序）排序，因此同一棵输入树两次打包**逐字节一致**。

只用 Python 标准库；压缩/解压调用系统 ``zstd`` CLI（可用 ``--zstd-cmd`` 换成
任意等价实现，例如 ``--zstd-cmd "/usr/local/bin/zstd"``）。

用法与与官方格式的差异见 ``docs/ROMPACK.md``。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

VERSION = "0.1.0"
GENERATOR = "dsh-rompack/" + VERSION

#: tar 内的唯一顶层目录名（顺带作为它的条目前缀）
TOP_NAME = b"rootfs"
DEFAULT_BOM_VERSION = "dsh-rompack-1"

BLOCK_SIZE = 512
RECORD_SIZE = 10240  # GNU tar 默认记录大小（20 块），用于尾部对齐
NORMALIZED_MTIME = 0  # 1970-01-01T00:00:00Z，与官方实测一致
MODE_REGULAR = 0o644
MODE_EXECUTABLE = 0o755
MODE_DIRECTORY = 0o755
MODE_SYMLINK = 0o777

DEFAULT_LEVEL = 19
#: zstd ``--long=27`` 即 windowLog=27（128 MiB）。官方实测是 64 MiB（windowLog=26），
#: 见 docs/ROMPACK.md 的差异表；用 ``--window-log 26`` 可与官方窗口对齐。
DEFAULT_WINDOW_LOG = 27
#: 默认单线程：多线程压缩的输出依赖 CPU 数量/版本行为，单线程才谈得上跨机可复现。
DEFAULT_THREADS = 1

ZSTD_MAGIC = 0xFD2FB528
SKIPPABLE_MAGIC = 0x184D2A50
SKIPPABLE_MAGIC_LAST = 0x184D2A5F

ZERO_BLOCK = b"\0" * BLOCK_SIZE
ZERO_TRAILER = ZERO_BLOCK * 2

_READ_CHUNK = 1 << 20


class RomPackError(Exception):
    """工具内部可预期的失败（CLI 捕获后打印原因并以非 0 退出）。"""


# --------------------------------------------------------------------------- 杂项


def sha256_file(path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(_READ_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def format_bytes(count: int) -> str:
    if count >= 1 << 20 and count % (1 << 20) == 0:
        return f"{count} 字节（{count // (1 << 20)} MiB）"
    return f"{count} 字节"


def format_name(name: bytes) -> str:
    return os.fsdecode(name)


# ------------------------------------------------------------------- USTAR 写入


def _octal_field(value: int, width: int, what: str) -> bytes:
    """USTAR 数值字段：``width-1`` 位八进制数字 + NUL。"""
    if value < 0:
        raise RomPackError(f"{what} 不能为负数：{value}")
    digits = format(value, "o")
    if len(digits) > width - 1:
        raise RomPackError(f"{what}={value} 超出 {width} 字节八进制字段的表示范围")
    return b"0" * (width - 1 - len(digits)) + digits.encode("ascii") + b"\0"


def _parse_octal_field(field: bytes, what: str) -> int:
    text = field.split(b"\0")[0].strip()
    if not text:
        return 0
    if any(byte not in b"01234567" for byte in text):
        raise RomPackError(f"{what} 字段不是八进制数：{field!r}")
    return int(text, 8)


def split_name(name: bytes):
    """把长路径切成 USTAR 的 ``name``(100) + ``prefix``(155)。"""
    if len(name) <= 100:
        return name, b""
    for index, byte in enumerate(name):
        if byte != 0x2F:  # '/'
            continue
        if index > 155:
            break
        if len(name) - index - 1 <= 100:
            return name[index + 1 :], name[:index]
    raise RomPackError(
        f"路径超过 USTAR 名字上限（100 字节 + prefix 155 字节）：{format_name(name)}"
    )


def ustar_header(
    name: bytes,
    mode: int,
    size: int,
    typeflag: bytes = b"0",
    linkname: bytes = b"",
    mtime: int = NORMALIZED_MTIME,
    uid: int = 0,
    gid: int = 0,
) -> bytes:
    """生成归一化 USTAR 头（uname/gname 留空，dev 号留 0）。"""
    if len(typeflag) != 1:
        raise RomPackError(f"typeflag 必须是 1 字节：{typeflag!r}")
    if len(linkname) > 100:
        raise RomPackError(f"符号链接目标超过 100 字节：{format_name(linkname)}")
    name_field, prefix_field = split_name(name)

    header = bytearray(BLOCK_SIZE)
    header[0 : len(name_field)] = name_field
    header[100:108] = _octal_field(mode, 8, "mode")
    header[108:116] = _octal_field(uid, 8, "uid")
    header[116:124] = _octal_field(gid, 8, "gid")
    header[124:136] = _octal_field(size, 12, "size")
    header[136:148] = _octal_field(mtime, 12, "mtime")
    header[148:156] = b" " * 8  # 校验和占位：先全空格再计算
    header[156:157] = typeflag
    header[157 : 157 + len(linkname)] = linkname
    header[257:263] = b"ustar\0"
    header[263:265] = b"00"
    # uname(265..297) / gname(297..329) 保持 NUL：不写入宿主机用户名
    header[329:337] = _octal_field(0, 8, "devmajor")
    header[337:345] = _octal_field(0, 8, "devminor")
    header[345 : 345 + len(prefix_field)] = prefix_field

    checksum = sum(header)
    if checksum > 0o777777:
        raise RomPackError("USTAR 校验和溢出")
    header[148:156] = format(checksum, "06o").encode("ascii") + b"\0 "
    return bytes(header)


def header_checksum(header: bytes) -> int:
    """按“校验和字段视为 8 个空格”的规则重算 USTAR 校验和。"""
    return sum(header[0:148]) + 8 * 0x20 + sum(header[156:512])


# --------------------------------------------------------------------- 扫描源树


def scan_source(src) -> list:
    """把 ``src`` 归一化成待写入的条目列表（已按名称字节序排序）。"""
    root = Path(src)
    if root.is_symlink():
        raise RomPackError(f"源目录不能是符号链接：{root}")
    if not root.is_dir():
        raise RomPackError(f"源目录不是目录：{root}")

    entries = [
        {
            "name": TOP_NAME + b"/",
            "kind": "dir",
            "mode": MODE_DIRECTORY,
            "size": 0,
            "linkname": b"",
            "source": None,
        }
    ]
    stack = [(b"", root)]
    while stack:
        relative, directory = stack.pop()
        with os.scandir(directory) as iterator:
            children = sorted(iterator, key=lambda item: os.fsencode(item.name))
        for child in children:
            name = os.fsencode(child.name)
            if b"\0" in name:
                raise RomPackError(f"文件名含 NUL：{child.path}")
            entry_name = TOP_NAME + b"/" + (relative + b"/" + name if relative else name)
            path = Path(child.path)
            info = os.lstat(path)
            # 不跟随符号链接：既保留链接本身，也避免目录环
            if child.is_symlink():
                target = os.fsencode(os.readlink(path))
                if not target:
                    raise RomPackError(f"符号链接目标为空：{path}")
                entries.append(
                    {
                        "name": entry_name,
                        "kind": "symlink",
                        "mode": MODE_SYMLINK,
                        "size": 0,
                        "linkname": target,
                        "source": path,
                    }
                )
            elif child.is_dir(follow_symlinks=False):
                entries.append(
                    {
                        "name": entry_name + b"/",
                        "kind": "dir",
                        "mode": MODE_DIRECTORY,
                        "size": 0,
                        "linkname": b"",
                        "source": path,
                    }
                )
                stack.append((relative + b"/" + name if relative else name, path))
            elif os.path.isfile(path) and not path.is_symlink():
                mode = MODE_EXECUTABLE if info.st_mode & 0o111 else MODE_REGULAR
                entries.append(
                    {
                        "name": entry_name,
                        "kind": "file",
                        "mode": mode,
                        "size": info.st_size,
                        "linkname": b"",
                        "source": path,
                    }
                )
            else:
                raise RomPackError(
                    "不支持的条目类型（管道/套接字/设备文件不能进 ROM）："
                    f"{path}（st_mode=0o{info.st_mode:o}）"
                )

    entries.sort(key=lambda item: item["name"])
    return entries


def build_tar(entries, handle) -> dict:
    """把条目写成 USTAR 流，返回统计信息。"""
    written = 0
    for entry in entries:
        name = entry["name"]
        if entry["kind"] == "dir":
            handle.write(ustar_header(name, MODE_DIRECTORY, 0, b"5"))
            written += BLOCK_SIZE
        elif entry["kind"] == "symlink":
            handle.write(
                ustar_header(name, MODE_SYMLINK, 0, b"2", linkname=entry["linkname"])
            )
            written += BLOCK_SIZE
        elif entry["kind"] == "file":
            size = entry["size"]
            handle.write(ustar_header(name, entry["mode"], size, b"0"))
            written += BLOCK_SIZE
            copied = 0
            with open(entry["source"], "rb") as payload:
                for chunk in iter(lambda: payload.read(_READ_CHUNK), b""):
                    handle.write(chunk)
                    copied += len(chunk)
            if copied != size:
                raise RomPackError(
                    f"打包期间文件大小发生变化：{format_name(name)}"
                    f"（头部记录 {size} 字节，实际读到 {copied} 字节）"
                )
            padding = (-size) % BLOCK_SIZE
            if padding:
                handle.write(b"\0" * padding)
                written += padding
            written += size
        else:  # pragma: no cover - scan_source 不会产出别的类型
            raise RomPackError(f"未知条目类型：{entry['kind']}")

    handle.write(ZERO_TRAILER)
    written += len(ZERO_TRAILER)
    padding = (-written) % RECORD_SIZE
    if padding:
        handle.write(b"\0" * padding)
        written += padding

    return {
        "tarBytes": written,
        "entryCount": len(entries),
        "fileCount": sum(1 for entry in entries if entry["kind"] == "file"),
        "dirCount": sum(1 for entry in entries if entry["kind"] == "dir"),
        "symlinkCount": sum(1 for entry in entries if entry["kind"] == "symlink"),
        "contentBytes": sum(entry["size"] for entry in entries if entry["kind"] == "file"),
    }


# ----------------------------------------------------------------------- zstd


def zstd_program(zstd_cmd: str) -> list:
    """解析 ``--zstd-cmd``，确认可执行。支持带参数的命令行。"""
    words = shlex.split(zstd_cmd or "zstd")
    if not words:
        raise RomPackError("--zstd-cmd 为空")
    program = shutil.which(words[0])
    if program is None:
        raise RomPackError(
            f"找不到 zstd 可执行文件：{words[0]}。"
            "请安装（apt-get install -y zstd），或用 --zstd-cmd 指定路径。"
        )
    return [program] + words[1:]


def compress_payload(program, tar_path, zst_path, level, window_log, threads) -> None:
    args = program + [
        f"-{level}",
        f"--long={window_log}",
        f"-T{threads}",
        "-q",
        "--check",  # 帧内 XXH64 内容校验（zstd CLI 默认即开，显式写出便于审计）
        "-f",
        "-o",
        os.fspath(zst_path),
        os.fspath(tar_path),
    ]
    try:
        subprocess.run(args, check=True, capture_output=True)
    except subprocess.CalledProcessError as error:
        message = error.stderr.decode("utf-8", "replace").strip() if error.stderr else ""
        raise RomPackError(
            f"zstd 压缩失败（退出码 {error.returncode}）：{message or '无错误输出'}\n"
            f"命令：{' '.join(args)}"
        ) from error


class PayloadStream:
    """``zstd -d -c`` 子进程的 stdout，附带退出码/错误信息收集。"""

    def __init__(self, program, source):
        self._stderr = tempfile.TemporaryFile()
        args = program + ["-d", "-c", "-q", os.fspath(source)]
        self._process = subprocess.Popen(
            args, stdout=subprocess.PIPE, stderr=self._stderr
        )
        self.bytes_read = 0
        self._closed = False

    def read(self, count: int) -> bytes:
        data = self._process.stdout.read(count)
        self.bytes_read += len(data)
        return data

    def close(self):
        """返回 ``(退出码, stderr 文本)``；不抛异常，由调用方决定如何报告。"""
        if self._closed:  # pragma: no cover - 防御重复关闭
            return 0, ""
        self._closed = True
        self._process.stdout.close()
        code = self._process.wait()
        self._stderr.seek(0)
        message = self._stderr.read().decode("utf-8", "replace").strip()
        self._stderr.close()
        return code, message


def _read_exact(stream, count: int, what: str) -> bytes:
    data = stream.read(count)
    if len(data) == count:
        return data
    chunks = [data]
    remaining = count - len(data)
    while remaining > 0:
        chunk = stream.read(remaining)
        if not chunk:
            raise RomPackError(f"{what}被截断（还差 {remaining} 字节）")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


# ------------------------------------------------------- skippable frame / BOM


def encode_skippable(payload: bytes, index: int = 0) -> bytes:
    """zstd skippable frame：magic(0x184D2A50|index, LE) + 长度(LE) + 载荷。"""
    if not 0 <= index <= 15:
        raise RomPackError(f"skippable frame 序号必须是 0..15：{index}")
    if len(payload) > 0xFFFFFFFF:
        raise RomPackError("skippable frame 载荷超过 4 GiB")
    return struct.pack("<II", SKIPPABLE_MAGIC | index, len(payload)) + payload


def bom_default(stats: dict, level: int, window_log: int) -> dict:
    """自动生成的 BOM：字段固定、无时间戳，保证逐字节可复现。"""
    return {
        "bomVersion": DEFAULT_BOM_VERSION,
        "generator": GENERATOR,
        "payloadFormat": "ustar",
        "topDir": os.fsdecode(TOP_NAME),
        "entryCount": stats["entryCount"],
        "fileCount": stats["fileCount"],
        "dirCount": stats["dirCount"],
        "symlinkCount": stats["symlinkCount"],
        "totalBytes": stats["contentBytes"],
        "tarBytes": stats["tarBytes"],
        "mtimeEpoch": NORMALIZED_MTIME,
        "ownerUid": 0,
        "ownerGid": 0,
        "zstdLevel": level,
        "zstdWindowLog": window_log,
    }


def serialize_bom(bom: dict) -> bytes:
    # sort_keys + 紧凑分隔符 + ensure_ascii：只要 BOM 内容相同，字节就相同
    return json.dumps(
        bom, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def load_bom_file(path) -> dict:
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError as error:
        raise RomPackError(f"读不到 BOM 文件：{path}（{error}）") from error
    try:
        bom = json.loads(text)
    except json.JSONDecodeError as error:
        raise RomPackError(f"BOM 文件不是合法 JSON：{path}（{error}）") from error
    if not isinstance(bom, dict):
        raise RomPackError(f"BOM 必须是 JSON 对象（不是 {type(bom).__name__}）：{path}")
    if not isinstance(bom.get("bomVersion"), str) or not bom["bomVersion"]:
        raise RomPackError(f"BOM 缺少字符串字段 bomVersion：{path}")
    return bom


def find_boms(container: dict) -> list:
    """返回所有能被解析成 JSON 对象的 skippable frame。"""
    found = []
    for frame in container["skippables"]:
        payload = frame["payload"]
        try:
            value = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if isinstance(value, dict):
            found.append({"frame": frame, "bom": value})
    return found


# ------------------------------------------------------------ zstd 容器解析


def parse_zstd_frame(handle, offset: int, file_size: int) -> dict:
    """只靠帧/块头遍历一个 zstd 帧（不解压数据），返回其边界与参数。"""
    handle.seek(offset)
    if _read_exact(handle, 4, "zstd 帧魔数") != struct.pack("<I", ZSTD_MAGIC):
        raise RomPackError(f"偏移 {offset} 处不是 zstd 帧")
    descriptor = _read_exact(handle, 1, "zstd 帧头描述符")[0]
    fcs_flag = descriptor >> 6
    single_segment = bool(descriptor & 0x20)
    if descriptor & 0x10:
        raise RomPackError("zstd 帧头保留位（bit4）非 0")
    if descriptor & 0x08:
        raise RomPackError("zstd 帧头保留位（bit3）非 0")
    has_checksum = bool(descriptor & 0x04)
    dict_flag = descriptor & 0x03

    window_log = None
    window_size = None
    if not single_segment:
        descriptor_byte = _read_exact(handle, 1, "zstd 窗口描述符")[0]
        exponent = descriptor_byte >> 3
        mantissa = descriptor_byte & 0x07
        window_log = 10 + exponent
        window_size = (1 << window_log) + ((1 << window_log) // 8) * mantissa
    dict_size = (0, 1, 2, 4)[dict_flag]
    if dict_size:
        _read_exact(handle, dict_size, "zstd 字典 ID")

    if fcs_flag == 0:
        fcs_size = 1 if single_segment else 0
    elif fcs_flag == 1:
        fcs_size = 2
    elif fcs_flag == 2:
        fcs_size = 4
    else:
        fcs_size = 8
    content_size = None
    if fcs_size:
        raw = _read_exact(handle, fcs_size, "zstd 内容长度字段")
        content_size = int.from_bytes(raw, "little")
        if fcs_size == 2:
            content_size += 256
    if single_segment:
        # 单段帧省略窗口描述符：窗口就是内容长度
        window_size = content_size
        window_log = None

    blocks = 0
    while True:
        header = _read_exact(handle, 3, "zstd 块头")
        value = int.from_bytes(header, "little")
        last_block = bool(value & 1)
        block_type = (value >> 1) & 0x03
        block_size = value >> 3
        if block_type == 3:
            raise RomPackError("zstd 块类型 3（保留值）")
        payload = 1 if block_type == 1 else block_size
        position = handle.tell() + payload
        if position > file_size:
            raise RomPackError(f"zstd 块超出文件末尾（偏移 {handle.tell()}）")
        handle.seek(payload, os.SEEK_CUR)
        blocks += 1
        if blocks > 1 << 24:
            raise RomPackError("zstd 块数量异常，疑似损坏")
        if last_block:
            break
    if has_checksum:
        position = handle.tell() + 4
        if position > file_size:
            raise RomPackError("zstd XXH64 校验字段超出文件末尾")
        handle.seek(4, os.SEEK_CUR)

    end = handle.tell()
    return {
        "offset": offset,
        "end": end,
        "length": end - offset,
        "single_segment": single_segment,
        "has_checksum": has_checksum,
        "window_log": window_log,
        "window_size": window_size,
        "content_size": content_size,
        "blocks": blocks,
    }


def parse_container(handle, file_size: int) -> dict:
    """顺序解析容器：skippable frame 与 zstd 帧，任何残余都视为错误。"""
    skippables = []
    frames = []
    position = 0
    while position < file_size:
        handle.seek(position)
        magic_bytes = handle.read(4)
        if len(magic_bytes) < 4:
            raise RomPackError(f"偏移 {position} 处有不足 4 字节的残片")
        magic = struct.unpack("<I", magic_bytes)[0]
        if SKIPPABLE_MAGIC <= magic <= SKIPPABLE_MAGIC_LAST:
            length_bytes = _read_exact(handle, 4, "skippable frame 长度")
            length = struct.unpack("<I", length_bytes)[0]
            if position + 8 + length > file_size:
                raise RomPackError(f"偏移 {position} 处的 skippable frame 被截断")
            payload = _read_exact(handle, length, "skippable frame 载荷")
            skippables.append(
                {
                    "offset": position,
                    "index": magic - SKIPPABLE_MAGIC,
                    "length": length,
                    "payload": payload,
                }
            )
            position += 8 + length
        elif magic == ZSTD_MAGIC:
            frame = parse_zstd_frame(handle, position, file_size)
            frames.append(frame)
            position = frame["end"]
        else:
            raise RomPackError(
                f"偏移 {position} 处既不是 zstd 帧（0xFD2FB528）"
                f"也不是 skippable frame（0x184D2A50..5F）：magic=0x{magic:08X}"
            )
    return {"skippables": skippables, "frames": frames, "size": file_size}


def parse_container_file(path) -> dict:
    with open(path, "rb") as handle:
        return parse_container(handle, os.fstat(handle.fileno()).st_size)


# --------------------------------------------------------------- tar 读取


def _kind_of(typeflag: bytes) -> str:
    if typeflag in (b"0", b"\0", b"7"):
        return "file"
    if typeflag == b"5":
        return "dir"
    if typeflag == b"2":
        return "symlink"
    if typeflag == b"1":
        return "hardlink"
    if typeflag in (b"3", b"4"):
        return "device"
    if typeflag == b"6":
        return "fifo"
    return "unsupported"


def _parse_tar_header(header: bytes, index: int) -> dict:
    name = header[0:100].split(b"\0")[0]
    prefix = header[345:500].split(b"\0")[0]
    full = (prefix + b"/" + name) if prefix else name
    entry = {
        "index": index,
        "name": full,
        "mode": _parse_octal_field(header[100:108], "mode"),
        "uid": _parse_octal_field(header[108:116], "uid"),
        "gid": _parse_octal_field(header[116:124], "gid"),
        "size": _parse_octal_field(header[124:136], "size"),
        "mtime": _parse_octal_field(header[136:148], "mtime"),
        "checksum": _parse_octal_field(header[148:156], "checksum"),
        "computed_checksum": header_checksum(header),
        "typeflag": header[156:157],
        "linkname": header[157:257].split(b"\0")[0],
        "magic": header[257:263],
        "version": header[263:265],
        "uname": header[265:297].split(b"\0")[0],
        "gname": header[297:329].split(b"\0")[0],
    }
    entry["kind"] = _kind_of(entry["typeflag"])
    return entry


class TarReader:
    """顺序读取 USTAR 流；未读取的数据在下次取条目时自动跳过。"""

    def __init__(self, stream):
        self._stream = stream
        self._data_remaining = 0
        self._padding = 0
        self._done = False
        self.index = 0
        self.terminated = False
        self.eof_without_terminator = False
        self.bytes_read = 0

    def _read(self, count: int) -> bytes:
        data = self._stream.read(count)
        self.bytes_read += len(data)
        return data

    def _skip(self, count: int) -> None:
        while count > 0:
            chunk = self._read(min(count, _READ_CHUNK))
            if not chunk:
                raise RomPackError("tar 数据区被截断")
            count -= len(chunk)

    def _drain(self) -> None:
        if self._data_remaining:
            self._skip(self._data_remaining)
            self._data_remaining = 0
        if self._padding:
            self._skip(self._padding)
            self._padding = 0

    def _read_block(self):
        data = self._read(BLOCK_SIZE)
        if not data:
            return None
        while len(data) < BLOCK_SIZE:
            chunk = self._read(BLOCK_SIZE - len(data))
            if not chunk:
                raise RomPackError("tar 头被截断")
            data += chunk
        return data

    def next_entry(self):
        if self._done:
            return None
        self._drain()
        header = self._read_block()
        if header is None:
            self.eof_without_terminator = True
            self._done = True
            return None
        if header == ZERO_BLOCK:
            self._consume_trailer()
            self._done = True
            return None
        entry = _parse_tar_header(header, self.index)
        self.index += 1
        self._data_remaining = entry["size"]
        self._padding = (-entry["size"]) % BLOCK_SIZE
        return entry

    def _consume_trailer(self) -> None:
        # 结束块之后只允许补零（GNU tar 的记录对齐）
        while True:
            block = self._read_block()
            if block is None:
                self.terminated = True
                return
            if block.strip(b"\0"):
                raise RomPackError("tar 结束块之后仍有非零数据")

    def iter_data(self, chunk_size: int = _READ_CHUNK):
        remaining = self._data_remaining
        while remaining > 0:
            chunk = self._read(min(remaining, chunk_size))
            if not chunk:
                raise RomPackError("tar 数据区被截断")
            remaining -= len(chunk)
            yield chunk
        self._data_remaining = 0
        self._drain()

    def read_data(self) -> bytes:
        return b"".join(self.iter_data())


# --------------------------------------------------------------- 检查报告


class Report:
    STATUS_PASS = "ok"
    STATUS_FAIL = "fail"
    STATUS_WARN = "warn"
    STATUS_SKIP = "skip"

    def __init__(self, verbose: bool = True):
        self.checks = []
        self.verbose = verbose

    def add(self, name: str, status: str, detail: str) -> None:
        self.checks.append({"name": name, "status": status, "detail": detail})
        if self.verbose:
            label = {
                self.STATUS_PASS: "[ok]  ",
                self.STATUS_FAIL: "[FAIL]",
                self.STATUS_WARN: "[warn]",
                self.STATUS_SKIP: "[skip]",
            }[status]
            print(f"{label} {name}: {detail}")

    @property
    def failures(self) -> int:
        return sum(1 for check in self.checks if check["status"] == self.STATUS_FAIL)

    @property
    def passes(self) -> int:
        return sum(1 for check in self.checks if check["status"] == self.STATUS_PASS)

    def as_list(self) -> list:
        return [dict(check) for check in self.checks]


# ------------------------------------------------------------------- pack


def pack(
    srcdir,
    output,
    bom_file=None,
    level=DEFAULT_LEVEL,
    window_log=DEFAULT_WINDOW_LOG,
    threads=DEFAULT_THREADS,
    zstd_cmd="zstd",
    pad_skippable=0,
    keep_tar=None,
) -> dict:
    source = Path(srcdir)
    destination = Path(output)
    program = zstd_program(zstd_cmd)

    entries = scan_source(source)
    stats_entries = {
        "entryCount": len(entries),
        "fileCount": sum(1 for entry in entries if entry["kind"] == "file"),
        "dirCount": sum(1 for entry in entries if entry["kind"] == "dir"),
        "symlinkCount": sum(1 for entry in entries if entry["kind"] == "symlink"),
    }
    print(
        "pack: 源目录 {src}：{n} 个条目（文件 {f}、目录 {d}、符号链接 {l}）".format(
            src=source,
            n=stats_entries["entryCount"],
            f=stats_entries["fileCount"],
            d=stats_entries["dirCount"],
            l=stats_entries["symlinkCount"],
        )
    )

    with tempfile.TemporaryDirectory(prefix="rompack-") as temp_dir:
        tar_path = Path(temp_dir) / "payload.tar"
        with open(tar_path, "wb") as handle:
            stats = build_tar(entries, handle)
        if keep_tar:
            shutil.copyfile(tar_path, keep_tar)
            print(f"pack: 中间 tar 已保留：{keep_tar}")

        zst_path = Path(temp_dir) / "payload.tar.zst"
        compress_payload(program, tar_path, zst_path, level, window_log, threads)

        if bom_file:
            bom = load_bom_file(bom_file)
            bom_source = f"来自 {bom_file}"
        else:
            bom = bom_default(stats, level, window_log)
            bom_source = "自动生成"
        bom_payload = serialize_bom(bom)
        print(f"pack: BOM {format_bytes(len(bom_payload))}（{bom_source}）")
        print(
            "pack: 载荷 tar {bytes}，zstd -{level} --long={wl} -T{threads}".format(
                bytes=format_bytes(stats["tarBytes"]),
                level=level,
                wl=window_log,
                threads=threads,
            )
        )

        destination.parent.mkdir(parents=True, exist_ok=True)
        temp_out = destination.parent / (destination.name + ".tmp")
        try:
            with open(temp_out, "wb") as handle:
                handle.write(encode_skippable(bom_payload, 0))
                if pad_skippable:
                    handle.write(encode_skippable(b"\0" * pad_skippable, 1))
                with open(zst_path, "rb") as payload:
                    shutil.copyfileobj(payload, handle, _READ_CHUNK)
            os.replace(temp_out, destination)
        finally:
            if temp_out.exists():
                temp_out.unlink()

    digest = sha256_file(destination)
    size = destination.stat().st_size
    print(
        f"pack: 输出 {destination} {format_bytes(size)} sha256={digest} "
        f"entries={stats['entryCount']}"
    )
    return {
        "output": str(destination),
        "sha256": digest,
        "size": size,
        "bom": bom,
        "stats": stats,
    }


# ----------------------------------------------------------------- unpack


def _safe_target(outdir: Path, name: bytes) -> Path:
    """把 tar 内的相对名解析成 outdir 下的路径。

    只做**词法**判断（不 resolve 符号链接）：含 ``..`` 或绝对路径的名字直接拒绝，
    其余按路径分量拼到 outdir 下，因此不可能写到 outdir 之外。这里刻意不跟随
    符号链接——官方 ROM 里 ``/system/bin/sh`` 一类绝对链接很常见，跟随会把合法
    条目误判成逃逸。
    """
    if not name:
        raise RomPackError("tar 条目名为空")
    if name.startswith(b"/"):
        raise RomPackError(f"tar 条目是绝对路径，拒绝解包：{format_name(name)}")
    parts = [part for part in name.split(b"/") if part not in (b"", b".")]
    if not parts:
        raise RomPackError(f"tar 条目名为空：{format_name(name)}")
    if any(part == b".." for part in parts):
        raise RomPackError(f"tar 条目含 '..'，拒绝解包：{format_name(name)}")
    target = outdir
    for part in parts:
        target = target / os.fsdecode(part)
    return target


def unpack(source, outdir, zstd_cmd="zstd", manifest=None, force=False) -> dict:
    program = zstd_program(zstd_cmd)
    source_path = Path(source)
    if not source_path.is_file():
        raise RomPackError(f"输入不是文件：{source_path}")
    destination = Path(outdir)
    if destination.exists():
        if not destination.is_dir():
            raise RomPackError(f"输出路径已存在且不是目录：{destination}")
        if any(True for _ in destination.iterdir()) and not force:
            raise RomPackError(
                f"输出目录非空：{destination}（加 --force 可覆盖写入）"
            )
    destination.mkdir(parents=True, exist_ok=True)

    digest = sha256_file(source_path)
    container = parse_container_file(source_path)
    boms = find_boms(container)
    if boms:
        print(f"unpack: BOM（skippable frame #{boms[0]['frame']['index']}）")
        print(json.dumps(boms[0]["bom"], ensure_ascii=False, indent=2, sort_keys=True))
        if len(boms) > 1:
            print(f"unpack: 注意：共发现 {len(boms)} 个 JSON skippable frame")
    else:
        print("unpack: 注意：没有可解析的 BOM skippable frame")
    for frame in container["skippables"]:
        print(
            f"unpack: skippable frame #{frame['index']} 偏移 {frame['offset']} "
            f"长度 {frame['length']}"
        )
    for frame in container["frames"]:
        window = frame["window_size"]
        print(
            f"unpack: zstd 帧 偏移 {frame['offset']} 长度 {frame['length']} "
            f"窗口 {window} 字节 XXH64={'有' if frame['has_checksum'] else '无'}"
        )
    print(f"unpack: 容器 sha256={digest}")

    stream = PayloadStream(program, source_path)
    reader = TarReader(stream)
    extracted = []
    directories = []
    hardlinks = []
    try:
        while True:
            entry = reader.next_entry()
            if entry is None:
                break
            name = entry["name"]
            target = _safe_target(destination, name)
            kind = entry["kind"]
            if kind == "unsupported":
                raise RomPackError(
                    f"不支持的 tar 条目类型 {entry['typeflag']!r}：{format_name(name)}"
                )
            if kind == "device" or kind == "fifo":
                raise RomPackError(f"拒绝解出设备/管道条目：{format_name(name)}")
            if kind == "dir":
                target.mkdir(parents=True, exist_ok=True)
                directories.append((target, entry))
            elif kind == "symlink":
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.is_symlink() or target.exists():
                    target.unlink()
                os.symlink(os.fsdecode(entry["linkname"]), target)
            elif kind == "hardlink":
                hardlinks.append((target, entry))
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.is_symlink():
                    target.unlink()
                with open(target, "wb") as output:
                    for chunk in reader.iter_data():
                        output.write(chunk)
                os.chmod(target, entry["mode"] & 0o7777)
                os.utime(target, (entry["mtime"], entry["mtime"]))
            extracted.append(
                {
                    "name": format_name(name),
                    "kind": kind,
                    "mode": entry["mode"],
                    "size": entry["size"],
                    "mtime": entry["mtime"],
                    "linkname": format_name(entry["linkname"]) if entry["linkname"] else "",
                }
            )
    finally:
        code, message = stream.close()
    if code != 0:
        raise RomPackError(
            f"zstd 解压失败（退出码 {code}）：{message or '无错误输出'}"
        )

    # 硬链接放在常规文件之后：按链接目标（tar 内路径）建立
    for target, entry in hardlinks:
        origin = _safe_target(destination, entry["linkname"])
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() or target.is_symlink():
            target.unlink()
        try:
            os.link(origin, target)
        except OSError as error:
            raise RomPackError(
                f"硬链接条目无法建立：{format_name(entry['name'])} → "
                f"{format_name(entry['linkname'])}（{error}）"
            ) from error

    # 目录的权限/时间戳最后统一设置：写子项会刷新父目录的 mtime
    for target, entry in sorted(directories, key=lambda item: len(str(item[0])), reverse=True):
        os.chmod(target, entry["mode"] & 0o7777)
        os.utime(target, (entry["mtime"], entry["mtime"]))

    print(f"unpack: 解出 {len(extracted)} 个条目 → {destination}")
    if manifest:
        document = {
            "source": str(source_path),
            "sha256": digest,
            "size": source_path.stat().st_size,
            "container": {
                "skippableFrames": [
                    {"index": frame["index"], "offset": frame["offset"], "length": frame["length"]}
                    for frame in container["skippables"]
                ],
                "zstdFrames": [
                    {
                        "offset": frame["offset"],
                        "length": frame["length"],
                        "windowSize": frame["window_size"],
                        "windowLog": frame["window_log"],
                        "hasChecksum": frame["has_checksum"],
                        "contentSize": frame["content_size"],
                    }
                    for frame in container["frames"]
                ],
            },
            "bom": boms[0]["bom"] if boms else None,
            "entries": extracted,
        }
        Path(manifest).write_text(
            json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"unpack: 清单已写出：{manifest}")
    return {"sha256": digest, "entries": extracted, "bom": boms[0]["bom"] if boms else None}


# ----------------------------------------------------------------- verify


def _first_component(name: bytes) -> bytes:
    return name.split(b"/", 1)[0]


def _verify_payload(stream, report: Report, bom: dict) -> dict:
    reader = TarReader(stream)
    problems = {
        "format": [],
        "checksum": [],
        "absolute": [],
        "parent": [],
        "duplicate": [],
        "device": [],
        "empty_link": [],
        "top": [],
    }
    seen = {}
    top_components = set()
    top_dir_present = False
    counts = {"file": 0, "dir": 0, "symlink": 0, "hardlink": 0, "other": 0}
    owner_bad = []
    mtime_bad = []

    while True:
        entry = reader.next_entry()
        if entry is None:
            break
        counts[entry["kind"] if entry["kind"] in counts else "other"] += 1
        name = entry["name"]
        if not name:
            problems["format"].append("存在空条目名")
            continue
        if entry["magic"] != b"ustar\0" or entry["version"] != b"00":
            problems["format"].append(
                f"{format_name(name)}：magic/version={entry['magic']!r}/{entry['version']!r}"
            )
        if entry["checksum"] != entry["computed_checksum"]:
            problems["checksum"].append(
                f"{format_name(name)}：头部 {entry['checksum']:o} vs 计算 "
                f"{entry['computed_checksum']:o}"
            )
        if entry["uid"] != 0 or entry["gid"] != 0:
            owner_bad.append(f"{format_name(name)}={entry['uid']}/{entry['gid']}")
        if entry["mtime"] != NORMALIZED_MTIME:
            mtime_bad.append(f"{format_name(name)}={entry['mtime']}")
        if name.startswith(b"/"):
            problems["absolute"].append(format_name(name))
        parts = [part for part in name.split(b"/") if part]
        if any(part == b".." for part in parts):
            problems["parent"].append(format_name(name))
        normalized = name.rstrip(b"/")
        if normalized in seen:
            problems["duplicate"].append(
                f"{format_name(name)}（第 {seen[normalized]} 与第 {entry['index']} 条）"
            )
        else:
            seen[normalized] = entry["index"]
        if entry["kind"] in ("device", "fifo", "unsupported"):
            problems["device"].append(f"{format_name(name)}（typeflag {entry['typeflag']!r}）")
        if entry["kind"] == "symlink" and not entry["linkname"]:
            problems["empty_link"].append(format_name(name))

        component = _first_component(name)
        top_components.add(component)
        if normalized == TOP_NAME:
            if entry["kind"] == "dir":
                top_dir_present = True
            else:
                problems["top"].append(f"{format_name(name)} 是顶层条目但不是目录")
        if component != TOP_NAME:
            problems["top"].append(f"顶层条目不是 rootfs/：{format_name(name)}")

    if not top_dir_present:
        problems["top"].append("缺少 rootfs/ 目录条目")
    if len(top_components) != 1:
        problems["top"].append(
            "顶层目录不止一个：" + "、".join(sorted(format_name(c) for c in top_components))
        )

    entries = sum(counts.values())
    if entries == 0:
        report.add("tar.entries", Report.STATUS_FAIL, "载荷里没有任何条目")
    else:
        report.add(
            "tar.entries",
            Report.STATUS_PASS,
            f"{entries} 个条目（文件 {counts['file']}、目录 {counts['dir']}、"
            f"符号链接 {counts['symlink']}、硬链接 {counts['hardlink']}、其他 {counts['other']}）",
        )
    report.add(
        "tar.ustar_format",
        Report.STATUS_FAIL if problems["format"] else Report.STATUS_PASS,
        ("非 USTAR/字段异常：" + "；".join(problems["format"][:3]))
        if problems["format"]
        else "所有条目都是 ustar\\0 + 00 格式",
    )
    report.add(
        "tar.header_checksums",
        Report.STATUS_FAIL if problems["checksum"] else Report.STATUS_PASS,
        ("校验和不符：" + "；".join(problems["checksum"][:3]))
        if problems["checksum"]
        else "所有头部校验和正确",
    )
    report.add(
        "tar.top_level_rootfs",
        Report.STATUS_FAIL if problems["top"] else Report.STATUS_PASS,
        ("；".join(problems["top"][:3]) if problems["top"] else "顶层只有一个目录 rootfs/"),
    )
    report.add(
        "tar.no_absolute_path",
        Report.STATUS_FAIL if problems["absolute"] else Report.STATUS_PASS,
        ("绝对路径：" + "；".join(problems["absolute"][:3]))
        if problems["absolute"]
        else "没有绝对路径条目",
    )
    report.add(
        "tar.no_parent_component",
        Report.STATUS_FAIL if problems["parent"] else Report.STATUS_PASS,
        ("含 '..'：" + "；".join(problems["parent"][:3]))
        if problems["parent"]
        else "没有 '..' 路径分量",
    )
    report.add(
        "tar.no_duplicate_entries",
        Report.STATUS_FAIL if problems["duplicate"] else Report.STATUS_PASS,
        ("重复条目：" + "；".join(problems["duplicate"][:3]))
        if problems["duplicate"]
        else "没有重复条目",
    )
    report.add(
        "tar.no_device_or_special",
        Report.STATUS_FAIL if problems["device"] else Report.STATUS_PASS,
        ("设备/特殊/非法类型：" + "；".join(problems["device"][:3]))
        if problems["device"]
        else "没有设备文件、管道、硬链接或未知类型",
    )
    report.add(
        "tar.symlink_targets",
        Report.STATUS_FAIL if problems["empty_link"] else Report.STATUS_PASS,
        ("空目标符号链接：" + "；".join(problems["empty_link"][:3]))
        if problems["empty_link"]
        else "符号链接都带目标（目标可以是绝对路径，这是 ROM 的常态）",
    )
    report.add(
        "tar.normalized_owner",
        Report.STATUS_FAIL if owner_bad else Report.STATUS_PASS,
        ("uid/gid 不为 0：" + "；".join(owner_bad[:3]))
        if owner_bad
        else "uid/gid 全为 0",
    )
    report.add(
        "tar.normalized_mtime",
        Report.STATUS_FAIL if mtime_bad else Report.STATUS_PASS,
        ("mtime 不为 0：" + "；".join(mtime_bad[:3]))
        if mtime_bad
        else "mtime 全为 0（1970-01-01T00:00:00Z）",
    )
    if reader.terminated:
        report.add("tar.terminator", Report.STATUS_PASS, "两个 512 字节零块结束，尾部为记录对齐补零")
    elif reader.eof_without_terminator:
        report.add(
            "tar.terminator",
            Report.STATUS_WARN,
            "载荷在最后一个数据块处直接结束，没有 2 个零块结束符",
        )

    # BOM 与载荷的一致性（官方 BOM 的字段名未知，缺字段就跳过）
    if isinstance(bom, dict) and isinstance(bom.get("entryCount"), int):
        status = Report.STATUS_PASS if bom["entryCount"] == entries else Report.STATUS_FAIL
        report.add(
            "bom.entry_count_matches",
            status,
            f"BOM entryCount={bom['entryCount']}，载荷实际 {entries}",
        )
    else:
        report.add(
            "bom.entry_count_matches",
            Report.STATUS_SKIP,
            "BOM 里没有整数 entryCount 字段，跳过一致性比对",
        )
    expected_bytes = None
    for key in ("tarBytes", "payloadBytes"):
        if isinstance(bom.get(key), int):
            expected_bytes = bom[key]
            break
    if expected_bytes is None:
        report.add(
            "bom.tar_bytes_match",
            Report.STATUS_SKIP,
            "BOM 里没有 tarBytes/payloadBytes 字段，跳过一致性比对",
        )
    else:
        actual = reader.bytes_read
        report.add(
            "bom.tar_bytes_match",
            Report.STATUS_PASS if expected_bytes == actual else Report.STATUS_FAIL,
            f"BOM 记录 {expected_bytes} 字节，解压后实际 {actual} 字节",
        )

    return {
        "entries": entries,
        "counts": counts,
        "bytes": reader.bytes_read,
        "terminated": reader.terminated,
    }


def verify(source, zstd_cmd="zstd", report_path=None) -> int:
    path = Path(source)
    if not path.is_file():
        raise RomPackError(f"输入不是文件：{path}")
    program = zstd_program(zstd_cmd)
    digest = sha256_file(path)
    size = path.stat().st_size
    print(f"verify: 输入 {path} {format_bytes(size)} sha256={digest}")

    report = Report()
    container = parse_container_file(path)
    skippables = container["skippables"]
    frames = container["frames"]
    boms = find_boms(container)

    if skippables:
        report.add(
            "container.skippable_frames",
            Report.STATUS_PASS,
            f"{len(skippables)} 个 skippable frame（长度 "
            + "、".join(str(frame["length"]) for frame in skippables)
            + "）",
        )
    else:
        report.add(
            "container.skippable_frames", Report.STATUS_FAIL, "没有 skippable frame，缺 BOM"
        )

    if boms:
        bom = boms[0]["bom"]
        print("verify: BOM JSON =")
        print(json.dumps(bom, ensure_ascii=False, indent=2, sort_keys=True))
        report.add(
            "container.bom_json",
            Report.STATUS_PASS,
            f"skippable frame #{boms[0]['frame']['index']} 是可解析的 JSON 对象",
        )
        if isinstance(bom.get("bomVersion"), str) and bom["bomVersion"]:
            report.add(
                "container.bom_version",
                Report.STATUS_PASS,
                f"bomVersion={bom['bomVersion']}",
            )
        else:
            report.add("container.bom_version", Report.STATUS_FAIL, "BOM 缺少 bomVersion 字段")
    else:
        bom = None
        report.add(
            "container.bom_json", Report.STATUS_FAIL, "skippable frame 里没有可解析的 JSON 对象"
        )
        report.add("container.bom_version", Report.STATUS_FAIL, "无法检查 bomVersion（没有 BOM）")

    if len(frames) == 1:
        frame = frames[0]
        report.add(
            "container.single_zstd_frame",
            Report.STATUS_PASS,
            f"恰好 1 个 zstd 帧，偏移 {frame['offset']}，长度 {frame['length']} 字节",
        )
    else:
        frame = frames[0] if frames else None
        report.add(
            "container.single_zstd_frame",
            Report.STATUS_FAIL,
            f"发现 {len(frames)} 个 zstd 帧（要求恰好 1 个）"
            if frames
            else "没有 zstd 帧",
        )

    if frames and all(s["offset"] + 8 + s["length"] <= frames[0]["offset"] for s in skippables):
        report.add(
            "container.bom_before_payload",
            Report.STATUS_PASS,
            "所有 skippable frame 都在 zstd 帧之前（与官方布局一致）",
        )
    elif skippables:
        report.add(
            "container.bom_before_payload",
            Report.STATUS_WARN,
            "有 skippable frame 出现在 zstd 帧之后",
        )
    else:
        report.add("container.bom_before_payload", Report.STATUS_SKIP, "没有 skippable frame")

    if frame is not None:
        if frame["has_checksum"]:
            report.add(
                "container.zstd_checksum",
                Report.STATUS_PASS,
                "帧头声明 XXH64 内容校验（与官方一致）",
            )
        else:
            report.add(
                "container.zstd_checksum", Report.STATUS_FAIL, "帧头没有声明 XXH64 内容校验"
            )
        window_note = (
            f"窗口 {format_bytes(frame['window_size']) if frame['window_size'] else '未知'}"
            + (f"（windowLog={frame['window_log']}）" if frame["window_log"] else "（单段帧）")
            + f"，内容长度 {frame['content_size']}，块数 {frame['blocks']}"
        )
        report.add("container.window", Report.STATUS_PASS, window_note)
        if frames[-1]["end"] != size:
            report.add(
                "container.no_trailing_bytes",
                Report.STATUS_FAIL,
                f"最后一个 zstd 帧结束于 {frames[-1]['end']}，文件长 {size} 字节",
            )
        else:
            report.add(
                "container.no_trailing_bytes", Report.STATUS_PASS, "zstd 帧恰好覆盖到文件末尾"
            )

    payload = {"entries": 0, "counts": {}, "bytes": 0, "terminated": False}
    if frames:
        stream = PayloadStream(program, path)
        try:
            payload = _verify_payload(stream, report, bom or {})
        except RomPackError as error:
            report.add("payload.readable", Report.STATUS_FAIL, str(error))
        finally:
            code, message = stream.close()
        if code == 0:
            report.add(
                "payload.zstd_integrity",
                Report.STATUS_PASS,
                "zstd 能完整解压到结尾（若帧头声明了 XXH64，校验也在这一步做）",
            )
        else:
            report.add(
                "payload.zstd_integrity",
                Report.STATUS_FAIL,
                f"zstd 解压失败（退出码 {code}）：{message or '无错误输出'}",
            )

    if report_path:
        document = {
            "source": str(path),
            "sha256": digest,
            "size": size,
            "passed": report.failures == 0,
            "summary": {
                "checks": len(report.checks),
                "ok": report.passes,
                "failed": report.failures,
                "entries": payload["entries"],
                "bytes": payload["bytes"],
            },
            "checks": report.as_list(),
            "bom": bom,
            "container": {
                "skippableFrames": [
                    {"index": frame["index"], "offset": frame["offset"], "length": frame["length"]}
                    for frame in skippables
                ],
                "zstdFrames": [
                    {
                        "offset": frame["offset"],
                        "length": frame["length"],
                        "windowSize": frame["window_size"],
                        "windowLog": frame["window_log"],
                        "hasChecksum": frame["has_checksum"],
                        "contentSize": frame["content_size"],
                        "blocks": frame["blocks"],
                    }
                    for frame in frames
                ],
            },
        }
        Path(report_path).write_text(
            json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"verify: 报告已写出：{report_path}")

    print(
        f"verify: 结论：{report.passes} 项通过、{report.failures} 项失败"
        f"（{len(report.checks)} 项检查）"
    )
    if report.failures:
        print("verify: 该容器不合格（见上面的 [FAIL] 行）")
        return 1
    print("verify: 结构校验通过")
    return 0


# --------------------------------------------------------------------- CLI


DESCRIPTION = """\
twoyi ROM 打包工具：把一棵目录树打成与官方 assets/rootfs.tar.zst 结构一致的容器。

没有 zstd CLI 时用 --zstd-cmd 指定替代实现（例如 pip 装的 zstd 或自定义脚本）；
本工具本身只依赖 Python 标准库。
"""

EPILOG = """\
示例：
  # 打包（自动生成 BOM）
  python3 tools/rompack/rompack.py pack my-rootfs/ rootfs.tar.zst

  # 打包并用自己的 BOM JSON、窗口对齐官方 64 MiB
  python3 tools/rompack/rompack.py pack my-rootfs/ rootfs.tar.zst \\
      --bom bom.json --window-log 26

  # 结构校验（不合格退出码 1，并逐条说明原因）
  python3 tools/rompack/rompack.py verify rootfs.tar.zst

  # 解包，同时写出清单 JSON 供后续比对
  python3 tools/rompack/rompack.py unpack rootfs.tar.zst out/ --manifest out.json
"""


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rompack",
        description=DESCRIPTION,
        epilog=EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--version", action="version", version=GENERATOR)
    parser.add_argument(
        "--zstd-cmd",
        default=argparse.SUPPRESS,
        help="zstd 可执行文件（可带参数），默认 zstd",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    pack_parser = subparsers.add_parser("pack", help="把 <srcdir> 打成 rootfs.tar.zst")
    pack_parser.add_argument("srcdir", help="源目录；其内容成为容器里的 rootfs/")
    pack_parser.add_argument("output", help="输出 .tar.zst 路径")
    pack_parser.add_argument("--bom", help="BOM JSON 文件（默认自动生成）")
    pack_parser.add_argument("--level", type=int, default=DEFAULT_LEVEL, help="zstd 级别（默认 19）")
    pack_parser.add_argument(
        "--window-log",
        type=int,
        default=DEFAULT_WINDOW_LOG,
        help="zstd --long=N 的 N（默认 27=128 MiB；官方实测 26=64 MiB）",
    )
    pack_parser.add_argument(
        "--threads",
        type=int,
        default=DEFAULT_THREADS,
        help="zstd 线程数（默认 1；0=用满所有核，但跨机不再保证逐字节一致）",
    )
    pack_parser.add_argument(
        "--pad-skippable",
        type=int,
        default=0,
        metavar="BYTES",
        help="在 BOM 之后再加一个零填充 skippable frame（默认不加）",
    )
    pack_parser.add_argument("--keep-tar", metavar="PATH", help="把中间 tar 另存一份便于排查")
    pack_parser.add_argument("--zstd-cmd", default=argparse.SUPPRESS, help="同上")

    unpack_parser = subparsers.add_parser("unpack", help="打印 BOM 并解出载荷")
    unpack_parser.add_argument("source", help="输入 .tar.zst")
    unpack_parser.add_argument("outdir", help="输出目录（保留 rootfs/ 这一层）")
    unpack_parser.add_argument("--manifest", help="把条目清单写成 JSON")
    unpack_parser.add_argument("--force", action="store_true", help="允许写入非空目录")
    unpack_parser.add_argument("--zstd-cmd", default=argparse.SUPPRESS, help="同上")

    verify_parser = subparsers.add_parser("verify", help="只做结构校验，不合格退出码 1")
    verify_parser.add_argument("source", help="输入 .tar.zst")
    verify_parser.add_argument("--report", help="把检查结果写成 JSON")
    verify_parser.add_argument("--zstd-cmd", default=argparse.SUPPRESS, help="同上")

    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    zstd_cmd = getattr(args, "zstd_cmd", "zstd")
    try:
        if args.command == "pack":
            pack(
                args.srcdir,
                args.output,
                bom_file=args.bom,
                level=args.level,
                window_log=args.window_log,
                threads=args.threads,
                zstd_cmd=zstd_cmd,
                pad_skippable=args.pad_skippable,
                keep_tar=args.keep_tar,
            )
            return 0
        if args.command == "unpack":
            unpack(
                args.source,
                args.outdir,
                zstd_cmd=zstd_cmd,
                manifest=args.manifest,
                force=args.force,
            )
            return 0
        if args.command == "verify":
            return verify(args.source, zstd_cmd=zstd_cmd, report_path=args.report)
    except RomPackError as error:
        print(f"rompack: 错误：{error}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"rompack: 系统错误：{error}", file=sys.stderr)
        return 1
    parser.error(f"未知子命令：{args.command}")  # pragma: no cover
    return 2


def _configure_stdio() -> None:
    """CI 的 locale 可能是 C/POSIX，避免中文输出触发 UnicodeEncodeError。"""
    for stream in (sys.stdout, sys.stderr):
        encoding = (getattr(stream, "encoding", "") or "").lower()
        if encoding not in ("utf-8", "utf8"):
            try:
                stream.reconfigure(encoding="utf-8", errors="backslashreplace")
            except (AttributeError, ValueError):  # pragma: no cover
                pass


if __name__ == "__main__":
    _configure_stdio()
    sys.exit(main())