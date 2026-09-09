#!/usr/bin/env python3
"""生成带 IPTC 的测试样张（T1.5 用）。

公开样本集里没有中英混合的 IPTC 样张，所以用 Canon_40D.jpg 作底，
按 IPTC IIM 规范手工拼一个 APP13/8BIM 块插到 SOI 之后：

    APP13 (FFED)
      "Photoshop 3.0\0"
      8BIM + 0x0404 (IPTC-NAA) + Pascal 名(空) + 长度 + IPTC 数据(偶数对齐)
        └─ 数据集序列 1C <record> <dataset> <len:2> <data>
            1:90 = ESC % G  → 声明后续文本是 UTF-8（metadata-extractor 会据此解码）

用法：python tools/make-iptc-sample.py
输出：app/src/test/resources/samples/iptc-canon.jpg
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/test/resources/samples/canon-40d.jpg"
DST = ROOT / "app/src/test/resources/samples/iptc-canon.jpg"

# (record, dataset, 文本) —— 覆盖目录里的 8 个 IPTC 字段 + 两个关键词
DATASETS: list[tuple[int, int, str]] = [
    (1, 90, "\x1b%G"),            # Coded Character Set = UTF-8
    (2, 5, "Pict 测试标题"),       # Object Name
    (2, 25, "关键词甲"),           # Keywords（重复出现 = 多值）
    (2, 25, "keyword-b"),
    (2, 55, "20260909"),          # Date Created
    (2, 60, "101530+0800"),       # Time Created
    (2, 80, "测试作者"),           # By-line
    (2, 90, "南京"),               # City
    (2, 101, "中国"),              # Country/Primary Location Name
    (2, 116, "© 2026 测试版权"),   # Copyright Notice
    (2, 120, "一段中文说明，用于验证 IPTC 读取。"),  # Caption/Abstract
]


def dataset(record: int, ds: int, text: str) -> bytes:
    data = text.encode("utf-8")
    if len(data) > 0xFFFF:
        raise ValueError(f"数据集过长：{record}:{ds}")
    return bytes((0x1C, record, ds)) + struct.pack(">H", len(data)) + data


def app13(iim: bytes) -> bytes:
    resource = (
        b"8BIM"
        + struct.pack(">H", 0x0404)      # IPTC-NAA
        + b"\x00\x00"                    # 空 Pascal 名
        + struct.pack(">I", len(iim))
        + iim
    )
    if len(iim) % 2:                     # 8BIM 资源要求偶数长度
        resource += b"\x00"
    payload = b"Photoshop 3.0\x00" + resource
    return b"\xFF\xED" + struct.pack(">H", len(payload) + 2) + payload


def main() -> int:
    if not SRC.exists():
        print(f"缺少底图：{SRC}", file=sys.stderr)
        return 1
    raw = SRC.read_bytes()
    if not raw.startswith(b"\xFF\xD8"):
        print("底图不是 JPEG", file=sys.stderr)
        return 1

    iim = b"".join(dataset(r, d, t) for r, d, t in DATASETS)
    segment = app13(iim)
    # APP13 插在 SOI 之后（EXIF APP1 之前），顺序不影响解析
    out = raw[:2] + segment + raw[2:]
    DST.write_bytes(out)
    print(f"写入 {DST}（{len(out)} 字节，APP13 {len(segment)} 字节，{len(DATASETS)} 个数据集）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
