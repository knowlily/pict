package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.ImageFormatHint

/**
 * 文件字节层真有的 IFD 标签号目录 —— 「文件里到底有没有这个标签」的唯一真源（docs/09 R-19）。
 *
 * 为什么需要它：androidx ExifInterface 解析完会调 `addDefaultValuesForCompatibility()` 补一批
 * 兼容默认值（`LightSource=0` 就是这么冒出来的），这些值和文件里真有的标签长得一模一样。
 * `getAttributeRange()` 的负偏移**不能**当判据——本工具写过的文件里 `LightSource` 物理存在
 * （exiftool 可见），库报的区间**还是** [-1, 4]（docs/09 第七轮实测）。所以只能回到字节层，
 * 自己把 IFD 目录读出来：**只看有没有，不看值**（值仍然由 ExifInterface 给，它读值是对的，错只错在凭空补值）。
 *
 * 判定规则（保守，宁可多留不可错杀）：
 * - 目录里没有这个 tag 号 → [provesAbsence] 成立 → 读取层丢弃（这正是 R-19 要的）；
 * - 容器扫干净但一个元数据块都没有 → 空目录 → 文件里任何 EXIF 标签都是库补的，全部证伪；
 * - 容器不支持 / 结构读不通 → [of] 返回 null → 证不了，一律保留（HEIF/BMP 目前在这一档，见 KDoc 末尾）。
 *
 * 全程不做越界读：任何结构异常都退化成「证不了」，绝不抛异常。
 */
internal class IfdTagIndex private constructor(
    private val tagNumbers: Set<Int>,
    /** 库能从容器头（而不是元数据块）推出来的标签号，这些「不在 IFD 里」不等于文件没有它。 */
    private val fromContainerHeader: Set<Int>,
) {

    /** 能不能证明「文件里没有 [tagName] 这个标签」。证不了（未知标签号/索引不可用）返回 false。 */
    fun provesAbsence(tagName: String): Boolean {
        val ids = ExifTagNumbers.idsOf(tagName) ?: return false
        return ids.none { tagNumbers.contains(it) || fromContainerHeader.contains(it) }
    }

    /** 文件字节层的 IFD 目录里有没有这个 tag 号（写侧回归用）。 */
    fun contains(tagNumber: Int): Boolean = tagNumbers.contains(tagNumber)

    companion object {

        /** JPEG 的 SOF 段里就带着像素尺寸，库把它当 ImageWidth/ImageLength 报出来，不是凭空补的。 */
        private val JPEG_DIMENSIONS_FROM_SOF = setOf(0x0100, 0x0101)

        private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        private val WEBP = byteArrayOf(0x57, 0x45, 0x42, 0x50)

        private const val TAG_EXIF_IFD = 0x8769
        private const val TAG_GPS_IFD = 0x8825
        private const val TAG_INTEROP_IFD = 0xA005

        /** 单个 IFD 的条目数上限（Exif 规范 1 个目录最多 65535，真实文件远小于此，超了就是结构有问题）。 */
        private const val MAX_IFD_ENTRIES = 512
        private const val MAX_IFDS = 16
        private const val MAX_TAGS = 4096

        /**
         * 扫容器、收 IFD 目录里的 tag 号。
         *
         * 返回 null 表示**证不了**：容器不支持（HEIF 的 EXIF 在 ISO-BMFF 的 meta/iloc 里，暂不解析；
         * BMP/未知格式根本没有 EXIF 块）、结构读不通、或 IFD0 本身不自洽。调用方拿到 null 时一律保留字段。
         */
        fun of(format: ImageFormatHint, bytes: ByteArray): IfdTagIndex? {
            val block = when (format) {
                ImageFormatHint.JPEG -> scanJpeg(bytes)
                ImageFormatHint.PNG -> scanPng(bytes)
                ImageFormatHint.WEBP -> scanWebp(bytes)
                ImageFormatHint.TIFF, ImageFormatHint.RAW -> scanTiff(bytes)
                ImageFormatHint.HEIF, ImageFormatHint.BMP, ImageFormatHint.UNKNOWN -> null
            } ?: return null

            val numbers = if (block.isEmpty) emptySet() else {
                val collected = HashSet<Int>()
                if (!walkTiff(block, collected)) return null
                collected
            }
            return IfdTagIndex(
                tagNumbers = numbers,
                fromContainerHeader = if (format == ImageFormatHint.JPEG) {
                    JPEG_DIMENSIONS_FROM_SOF
                } else {
                    emptySet()
                },
            )
        }

        /** 扫描结果：要么是「扫干净了，元数据块在这里（可能为空 = 一个都没有）」，要么是「没读通」。 */
        private class MetadataBlock(val bytes: ByteArray, val start: Int, val end: Int) {
            val isEmpty: Boolean get() = start == end
            val length: Long get() = (end - start).toLong()

            /** 块内 [offset, offset + size) 是否都在块里。 */
            fun covers(offset: Long, size: Int): Boolean = offset >= 0 && offset + size <= length

            fun u8(offset: Long): Int = bytes[start + offset.toInt()].toInt() and 0xFF

            fun u16(offset: Long, little: Boolean): Int {
                val a = u8(offset)
                val b = u8(offset + 1)
                return if (little) a or (b shl 8) else (a shl 8) or b
            }

            fun u32(offset: Long, little: Boolean): Long {
                var value = 0L
                for (i in 0 until 4) {
                    val shift = if (little) i else 3 - i
                    value = value or (u8(offset + i).toLong() shl (8 * shift))
                }
                return value
            }
        }

        // ---- 容器扫描：只找 EXIF 载体，别的段/块一概跳过 ----

        /** JPEG：段序列里找带 "Exif\0\0" 的 APP1，扫到 SOS/EOI 为止（再往后是压缩数据）。 */
        private fun scanJpeg(bytes: ByteArray): MetadataBlock? {
            if (bytes.size < 4 || be8(bytes, 0) != 0xFF || be8(bytes, 1) != 0xD8) return null
            var i = 2L
            while (i + 4 <= bytes.size) {
                if (be8(bytes, i) != 0xFF) return null
                var marker = be8(bytes, i + 1)
                while (marker == 0xFF && i + 3 <= bytes.size) { // 段前允许填充 0xFF
                    i++
                    marker = be8(bytes, i + 1)
                }
                when {
                    marker == 0xD9 || marker == 0xDA -> return emptyBlock() // EOI / SOS
                    marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7 -> i += 2 // 无长度字段
                    else -> {
                        val length = be16(bytes, i + 2)
                        if (length < 2 || i + 2 + length > bytes.size) return null
                        val payloadEnd = i + 2 + length
                        if (marker == 0xE1) {
                            val dataStart = i + 4
                            if (startsWith(bytes, dataStart, payloadEnd, EXIF_HEADER)) {
                                return blockOf(bytes, dataStart + EXIF_HEADER.size, payloadEnd)
                            }
                        }
                        i = payloadEnd
                    }
                }
            }
            return null // 没走到 SOS/EOI 就结束了：结构不像 JPEG，不判定
        }

        /** PNG：块序列里找 eXIf（[长度 4][类型 4][数据][CRC 4]，大端），走到 IEND 为止。 */
        private fun scanPng(bytes: ByteArray): MetadataBlock? {
            if (!startsWith(bytes, 0, bytes.size.toLong(), PNG_SIGNATURE)) return null
            var i = 8L
            while (i + 12 <= bytes.size) {
                val length = be32(bytes, i)
                val typeEnd = i + 8
                if (typeEnd + length + 4 > bytes.size) return null
                if (ascii(bytes, i + 4) == "eXIf") {
                    val dataStart = i + 8
                    val dataEnd = dataStart + length
                    return blockOf(bytes, exifHeaderEnd(bytes, dataStart, dataEnd), dataEnd)
                }
                if (ascii(bytes, i + 4) == "IEND") return emptyBlock()
                i = i + 12 + length
            }
            return null
        }

        /** WebP：RIFF 块序列里找 EXIF（[四字码 4][长度 4, 小端][数据]，块按偶数字节对齐）。 */
        private fun scanWebp(bytes: ByteArray): MetadataBlock? {
            if (!startsWith(bytes, 0, bytes.size.toLong(), RIFF)) return null
            if (!startsWith(bytes, 8, bytes.size.toLong(), WEBP)) return null
            var i = 12L
            while (i + 8 <= bytes.size) {
                val length = le32(bytes, i + 4)
                val dataStart = i + 8
                if (dataStart + length > bytes.size) return null
                if (ascii(bytes, i) == "EXIF") {
                    val dataEnd = dataStart + length
                    return blockOf(bytes, exifHeaderEnd(bytes, dataStart, dataEnd), dataEnd)
                }
                i = dataStart + length + (length and 1L)
            }
            return emptyBlock() // RIFF 的块走完了，里面没有 EXIF 块
        }

        /** TIFF/DNG：整个文件就是 IFD 结构。 */
        private fun scanTiff(bytes: ByteArray): MetadataBlock? =
            if (bytes.size >= 8) MetadataBlock(bytes, 0, bytes.size) else null

        /** 「一个元数据块都没有」：用空块表示（start == end）。 */
        private fun emptyBlock(): MetadataBlock = MetadataBlock(ByteArray(0), 0, 0)

        private fun blockOf(bytes: ByteArray, start: Long, end: Long): MetadataBlock =
            MetadataBlock(bytes, start.toInt(), end.toInt())

        /** eXIf/EXIF 块有时带 "Exif\0\0" 前缀（规范上不该有，写过 PNG 的都能见到），有就跳过。 */
        private fun exifHeaderEnd(bytes: ByteArray, start: Long, end: Long): Long =
            if (startsWith(bytes, start, end, EXIF_HEADER)) start + EXIF_HEADER.size else start

        // ---- IFD 目录遍历 ----

        /**
         * 走 IFD 链：IFD0 必须读通（读不通就返回 false = 证不了），
         * ExifIFD/GPS/Interop 子目录和 IFD1 缩略图目录尽力而为（读坏了只是少收几个号，不影响已有结论）。
         */
        private fun walkTiff(block: MetadataBlock, out: MutableSet<Int>): Boolean {
            if (!block.covers(0, 8)) return false
            val little = when {
                block.u8(0) == 0x49 && block.u8(1) == 0x49 -> true // "II"
                block.u8(0) == 0x4D && block.u8(1) == 0x4D -> false // "MM"
                else -> return false
            }
            if (block.u16(2, little) != 42) return false

            val visited = HashSet<Long>()
            val queue = ArrayDeque<Long>()
            val first = block.u32(4, little)
            // IFD0 偏移必须落在块里，否则整个目录不可信（宁可证不了，也不能报个空目录把字段全证伪）
            if (first <= 0 || !block.covers(first, 2)) return false
            queue.addLast(first)
            var strict = true
            while (queue.isNotEmpty()) {
                val offset = queue.removeFirst()
                if (offset <= 0 || !visited.add(offset)) continue
                if (visited.size > MAX_IFDS || out.size > MAX_TAGS) break
                val ok = readIfd(block, offset, little, out, queue)
                if (!ok) {
                    if (strict) return false
                    continue
                }
                strict = false
            }
            return true
        }

        /** 读一个 IFD：收 tag 号，把子目录指针和下一个 IFD 排进队列。 */
        private fun readIfd(
            block: MetadataBlock,
            offset: Long,
            little: Boolean,
            out: MutableSet<Int>,
            queue: ArrayDeque<Long>,
        ): Boolean {
            if (!block.covers(offset, 2)) return false
            val count = block.u16(offset, little)
            if (count > MAX_IFD_ENTRIES) return false
            val entriesStart = offset + 2
            val afterEntries = entriesStart + count * 12L
            if (!block.covers(afterEntries, 4)) return false // 目录 + 下一个 IFD 偏移都要在块内

            for (k in 0 until count) {
                val entry = entriesStart + k * 12L
                if (!block.covers(entry, 12)) return false
                val tag = block.u16(entry, little)
                out += tag
                if (tag == TAG_EXIF_IFD || tag == TAG_GPS_IFD || tag == TAG_INTEROP_IFD) {
                    val pointer = block.u32(entry + 8, little)
                    if (pointer > 0 && pointer < block.length) queue.addLast(pointer)
                }
            }

            val next = block.u32(afterEntries, little)
            if (next > 0 && next < block.length) queue.addLast(next)
            return true
        }

        // ---- 字节读取（越界一律由调用方的 covers 挡住）----

        private fun be8(bytes: ByteArray, offset: Long): Int = bytes[offset.toInt()].toInt() and 0xFF

        private fun be16(bytes: ByteArray, offset: Long): Long =
            (be8(bytes, offset).toLong() shl 8) or be8(bytes, offset + 1).toLong()

        private fun be32(bytes: ByteArray, offset: Long): Long {
            var value = 0L
            for (i in 0 until 4) value = (value shl 8) or be8(bytes, offset + i).toLong()
            return value
        }

        private fun le32(bytes: ByteArray, offset: Long): Long {
            var value = 0L
            for (i in 3 downTo 0) value = (value shl 8) or be8(bytes, offset + i).toLong()
            return value
        }

        private fun ascii(bytes: ByteArray, offset: Long): String =
            String(bytes, offset.toInt(), 4, Charsets.US_ASCII)

        private fun startsWith(bytes: ByteArray, from: Long, toExclusive: Long, needle: ByteArray): Boolean {
            if (from + needle.size > toExclusive) return false
            for (i in needle.indices) {
                if (bytes[from.toInt() + i] != needle[i]) return false
            }
            return true
        }
    }
}
