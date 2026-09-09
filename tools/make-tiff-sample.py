#!/usr/bin/env python3
"""生成 T1.6 用的 TIFF 测试样张（`app/src/test/resources/samples/pict-tiff-xp.tif`）。

为什么自己造：公开样张集（ianare/exif-samples、metadata-extractor 的 Tests/Data）里
找不到同时带 **Windows XP\\* 标签**（0x9c9b–0x9c9f）与 **IFD0/IFD1 同名标签**
（Compression、XResolution 各带不同值）的 TIFF。这两个特性正好是本任务的验证重点：

- XP\\* 是 commons-imaging 相对 androidx exifinterface 1.4.2 的独有覆盖（后者无常量）；
- IFD0 与 IFD1（缩略图）会重复出现同一 tag 号，读取必须按 (tag, directoryType) 定位，
  否则缩略图的值会盖掉主图的值——样张里 IFD0.Compression=1 / IFD1.Compression=6、
  IFD0.XResolution=72/1 / IFD1.XResolution=300/1 就是为此埋的探针。

产物是 1x1、未压缩、灰度的小 TIFF（<1 KB），结构：header → IFD0 → IFD1 → Exif IFD
→ GPS IFD → 像素。手写 IFD 是刻意的：不依赖 Pillow / exiftool 等外部工具，字节级确定。

用法：python tools/make-tiff-sample.py
"""

from __future__ import annotations

import struct
from pathlib import Path

# TIFF 字段类型
BYTE, ASCII, SHORT, LONG, RATIONAL, UNDEFINED, SRATIONAL = 1, 2, 3, 4, 5, 7, 10

# TiffDirectoryType：IFD0=0、IFD1=1、Exif IFD=-2、GPS IFD=-3（javap 实测）
OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/samples/pict-tiff-xp.tif"


def utf16le(text: str) -> bytes:
    """XP\\* 标签是 UTF-16LE 字节串，规范要求以 NUL 结尾。"""
    return text.encode("utf-16-le") + b"\x00\x00"


def entry(tag: int, typ: int, count: int, raw: bytes) -> tuple:
    return (tag, typ, count, raw)


def ifd_bytes(entries: list, offset: int, next_ifd: int) -> tuple[bytes, bytes, int]:
    """把条目列表序列化成 IFD；>4 字节的值放到紧跟 IFD 的数据区。返回 (ifd, data, end)。"""
    data_start = offset + 2 + 12 * len(entries) + 4
    out = bytearray(struct.pack("<H", len(entries)))
    data = bytearray()
    for tag, typ, count, raw in entries:
        if len(raw) <= 4:
            value = raw + b"\x00" * (4 - len(raw))
        else:
            value = struct.pack("<I", data_start + len(data))
            data += raw
        out += struct.pack("<HHI", tag, typ, count) + value
    out += struct.pack("<I", next_ifd)
    return bytes(out), bytes(data), data_start + len(data)


def build() -> bytes:
    ifd0 = [
        entry(256, SHORT, 1, struct.pack("<H", 1)),        # ImageWidth
        entry(257, SHORT, 1, struct.pack("<H", 1)),        # ImageLength
        entry(258, SHORT, 1, struct.pack("<H", 8)),        # BitsPerSample
        entry(259, SHORT, 1, struct.pack("<H", 1)),        # Compression（IFD1 里是 6）
        entry(262, SHORT, 1, struct.pack("<H", 1)),        # PhotometricInterpretation
        entry(271, ASCII, 9, b"PictTest\x00"),             # Make
        entry(272, ASCII, 17, b"Pict TIFF Sample\x00"),    # Model
        entry(274, SHORT, 1, struct.pack("<H", 1)),        # Orientation
        entry(277, SHORT, 1, struct.pack("<H", 1)),        # SamplesPerPixel
        entry(278, LONG, 1, struct.pack("<I", 1)),         # RowsPerStrip
        entry(282, RATIONAL, 1, struct.pack("<II", 72, 1)),    # XResolution
        entry(283, RATIONAL, 1, struct.pack("<II", 72, 1)),    # YResolution
        entry(296, SHORT, 1, struct.pack("<H", 2)),        # ResolutionUnit
        entry(305, ASCII, 22, b"Pict Sample Generator\x00"),   # Software
        entry(306, ASCII, 20, b"2026:09:09 10:15:30\x00"),     # DateTime
        entry(40091, BYTE, len(utf16le("示例标题 Pict")), utf16le("示例标题 Pict")),  # XPTitle
        entry(40092, BYTE, len(utf16le("示例备注")), utf16le("示例备注")),           # XPComment
        entry(40093, BYTE, len(utf16le("示例作者")), utf16le("示例作者")),           # XPAuthor
        entry(40094, BYTE, len(utf16le("测试;元数据")), utf16le("测试;元数据")),     # XPKeywords
        entry(40095, BYTE, len(utf16le("示例主题")), utf16le("示例主题")),           # XPSubject
        entry(18246, SHORT, 1, struct.pack("<H", 4)),      # Rating（Microsoft 0x4746）
        entry(273, LONG, 1, b"\x00\x00\x00\x00"),          # StripOffsets（回填）
        entry(279, LONG, 1, struct.pack("<I", 1)),         # StripByteCounts
        entry(34665, LONG, 1, b"\x00\x00\x00\x00"),        # ExifOffset（回填）
        entry(34853, LONG, 1, b"\x00\x00\x00\x00"),        # GPSInfo（回填）
    ]
    ifd1 = [  # 缩略图目录：同名 tag 用不同值，用来验证 (tag, dir) 定位
        entry(259, SHORT, 1, struct.pack("<H", 6)),        # Compression = 6（JPEG 老式）
        entry(282, RATIONAL, 1, struct.pack("<II", 300, 1)),   # XResolution = 300/1
        entry(283, RATIONAL, 1, struct.pack("<II", 300, 1)),   # YResolution = 300/1
        entry(296, SHORT, 1, struct.pack("<H", 2)),        # ResolutionUnit
    ]
    exif = [
        entry(33434, RATIONAL, 1, struct.pack("<II", 1, 250)),        # ExposureTime = 1/250
        entry(33437, RATIONAL, 1, struct.pack("<II", 28, 10)),        # FNumber = 2.8
        entry(34850, SHORT, 1, struct.pack("<H", 3)),                 # ExposureProgram
        entry(34855, SHORT, 1, struct.pack("<H", 400)),               # ISOSpeedRatings
        entry(36864, UNDEFINED, 4, b"0232"),                          # ExifVersion
        entry(36867, ASCII, 20, b"2026:09:09 10:15:30\x00"),          # DateTimeOriginal
        entry(36868, ASCII, 20, b"2026:09:09 10:15:30\x00"),          # DateTimeDigitized
        entry(37377, SRATIONAL, 1, struct.pack("<ii", 7965784, 1000000)),  # ShutterSpeedValue
        entry(37378, RATIONAL, 1, struct.pack("<II", 2970854, 1000000)),   # ApertureValue
        entry(37380, SRATIONAL, 1, struct.pack("<ii", 0, 1)),         # ExposureBiasValue
        entry(37383, SHORT, 1, struct.pack("<H", 5)),                 # MeteringMode
        entry(37385, SHORT, 1, struct.pack("<H", 16)),                # Flash
        entry(37386, RATIONAL, 1, struct.pack("<II", 50, 1)),         # FocalLength
        entry(40961, SHORT, 1, struct.pack("<H", 1)),                 # ColorSpace（无常量，手写）
        entry(40962, LONG, 1, struct.pack("<I", 1)),                  # PixelXDimension
        entry(40963, LONG, 1, struct.pack("<I", 1)),                  # PixelYDimension
        entry(41986, SHORT, 1, struct.pack("<H", 0)),                 # ExposureMode
        entry(41987, SHORT, 1, struct.pack("<H", 0)),                 # WhiteBalance
        entry(41990, SHORT, 1, struct.pack("<H", 0)),                 # SceneCaptureType
        # 以下 5 个在 commons-imaging 1.0.0-alpha6 里 directoryType 为 null，
        # store 按 EXIF 规范补成 -2（见 CommonsImagingStore.DIR_OVERRIDE），
        # 埋进样张是为了让这条分支有回归保护。
        entry(37387, RATIONAL, 1, struct.pack("<II", 15, 1)),         # FlashEnergy
        entry(37392, SHORT, 1, struct.pack("<H", 3)),                 # FocalPlaneResolutionUnit = cm
        entry(37397, RATIONAL, 1, struct.pack("<II", 200, 1)),        # ExposureIndex
        entry(37399, SHORT, 1, struct.pack("<H", 2)),                 # SensingMethod = one-chip color area
        entry(41995, UNDEFINED, 2, b"\x01\x02"),                      # DeviceSettingDescription
    ]
    gps = [
        entry(0, BYTE, 4, b"\x02\x03\x00\x00"),                       # GPSVersionID
        entry(1, ASCII, 2, b"N\x00"),                                 # GPSLatitudeRef
        entry(2, RATIONAL, 3, struct.pack("<IIIIII", 31, 1, 14, 1, 1200, 100)),  # GPSLatitude
        entry(3, ASCII, 2, b"E\x00"),                                 # GPSLongitudeRef
        entry(4, RATIONAL, 3, struct.pack("<IIIIII", 121, 1, 28, 1, 1200, 100)),  # GPSLongitude
        entry(5, BYTE, 1, b"\x00"),                                   # GPSAltitudeRef
        entry(6, RATIONAL, 1, struct.pack("<II", 12, 1)),             # GPSAltitude
        entry(7, RATIONAL, 3, struct.pack("<IIIIII", 2, 1, 15, 1, 30, 1)),  # GPSTimeStamp
        entry(11, RATIONAL, 1, struct.pack("<II", 3, 1)),             # GPSDOP
        entry(18, ASCII, 7, b"WGS-84\x00"),                           # GPSMapDatum
        entry(29, ASCII, 11, b"2026:09:09\x00"),                      # GPSDateStamp
    ]

    # 第一遍：占位指针，得到各 IFD 与像素区的偏移
    b0, d0, end0 = ifd_bytes(ifd0, 8, 0)
    ifd1_off = end0
    b1, d1, end1 = ifd_bytes(ifd1, ifd1_off, 0)
    exif_off = end1
    b2, d2, end2 = ifd_bytes(exif, exif_off, 0)
    gps_off = end2
    b3, d3, end3 = ifd_bytes(gps, gps_off, 0)
    pixel_off = end3

    # 第二遍：回填 IFD0 的 next / ExifOffset / GPSInfo / StripOffsets
    ifd0 = [
        (t, ty, c, struct.pack("<I", pixel_off) if t == 273
         else struct.pack("<I", exif_off) if t == 34665
         else struct.pack("<I", gps_off) if t == 34853
         else raw)
        for t, ty, c, raw in ifd0
    ]
    b0, d0, end0 = ifd_bytes(ifd0, 8, ifd1_off)
    assert end0 == ifd1_off, "回填后 IFD0 长度变化，偏移失效"

    return (
        b"II" + struct.pack("<HI", 42, 8)
        + b0 + d0 + b1 + d1 + b2 + d2 + b3 + d3
        + b"\x80"  # 1x1 灰度像素
    )


if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(build())
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
