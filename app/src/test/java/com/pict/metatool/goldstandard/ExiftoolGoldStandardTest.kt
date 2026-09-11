package com.pict.metatool.goldstandard

import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.metadata.exif.ExifMetadataStore
import com.pict.metatool.data.metadata.imaging.CommonsImagingStore
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.plan.EditPlanExecutor
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * T2.12：exiftool 金标准（docs/00 §1 退出标准、docs/07 T2.12）。
 *
 * 与其它用例的分工：本类**不自己检查自己**。改完元数据后，字段的正确性一律由
 * exiftool 独立读回来判定，本工具只负责「写」。除此之外还钉三条：
 *
 * 1. **改到的字段 = 意图**——除目标字段外，任何存下来的字段都不许变
 *    （结构性/派生键在白名单里，见 [allowlist]）；
 * 2. **像素不动**——JPEG 断 SOS 之后逐字节相等、TIFF 断 strip 逐字节相等；
 * 3. **写不了的格式老实说不写**——HEIF/PNG/WebP/RAW 走 `canWrite == false`，
 *    由上游给出「需要重编码」的提示（docs/02 §5），而不是悄悄写坏。
 *
 * 期望值不取本工具的输出，来源只有两种：
 * - 测试里写死的常量（我们写进去的坐标、文本，或按规范换算出的度分秒）；
 * - **源文件经 exiftool 读出的值 + 文档化的变换**（时间平移 +1h；清除类用例的
 *   「该消失哪些键」直接由源文件里真实存在的键推出来）。
 * 后者是为了让断言不可能空转：清除用例会先要求该键在源文件里存在，再去要求它消失。
 *
 * 本类跑**两条写通道**（见 [Channel]）：
 * - [Channel.COMMONS]——commons-imaging 的 TIFF/JPEG 无损重写；
 * - [Channel.PROD]——生产真用的 [ExifMetadataStore]（JPEG/PNG/WebP 都走它）。
 *   `ExifInterface.setAttribute` 内部用 `android.util.Pair` 传值，而 mockable android.jar 里那个
 *   Pair 的构造器是空壳（字段恒为 null），所以本通道先要靠 `src/test/java/android/util/Pair.java`
 *   这个测试替身才能在 JVM 里跑起来——替身只补「构造器真的赋值」，语义与真机一致。
 *
 * 两条通道各跑各的产物，互不覆盖（PROD 的产物带 `.prod.` 中缀）；期望值口径完全相同，
 * 通道自己的已知偏差由 [prodDeviations] 逐条钉住，不许用白名单糊过去。
 *
 * 产物落在 `app/build/goldstandard/out/`：改好的图、exiftool 的 JSON 转储、
 * 期望清单 `*.checks.tsv`、实际改动清单 `*.changed.txt`。`tools/verify-with-exiftool.sh`
 * 会再拿 exiftool 把 `*.checks.tsv` 逐条对一遍（脚本侧的独立复核），并打印这些文件。
 */
class ExiftoolGoldStandardTest {

    private val store = CommonsImagingStore()

    /** 生产真用的写通道：JPEG/PNG/WebP 都走它（docs/02 §5 的路由表）。 */
    private val prodStore = ExifMetadataStore()

    /**
     * 写通道。同一个用例两条通道各跑一遍，产物用中缀区分，互不覆盖。
     *
     * [PROD] 能跑在 JVM 上不是理所当然：`ExifInterface.setAttribute` 依赖 `android.util.Pair`，
     * 而 AGP 的 mockable android.jar 里那个 Pair 是空壳（构造器不写字段），落了盘就会 NPE。
     * 测试源集里的 `app/src/test/java/android/util/Pair.java` 就是补这一刀的。
     */
    private enum class Channel(val label: String, val infix: String) {
        COMMONS("commons-imaging", ""),
        PROD("ExifInterface（生产通道）", "prod."),
    }

    /** 打开单测的 goldstandard 产物目录。 */
    private val outDir: File = File("build/goldstandard/out").apply { mkdirs() }

    @Before
    fun requireExiftool() {
        val exe = exiftool()
        assumeTrue(
            "未找到 exiftool：设 PICT_EXIFTOOL 指向 exiftool 可执行文件，或装到 " +
                "${CANDIDATES.joinToString(" / ")}；本次跳过（不算通过）",
            exe != null,
        )
    }

    // ---- 用例清单 -----------------------------------------------------------

    private val cases: List<GoldCase> = listOf(
        GoldCase(
            id = "jpeg-set-make-model",
            sample = "canon-40d.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(
                listOf(
                    EditOperation.SetField(TagKey.of("EXIF:Make"), TagValue.Text("PICT GOLD")),
                    EditOperation.SetField(TagKey.of("EXIF:Model"), TagValue.Text("PICT GOLD MODEL")),
                ),
            ),
            expectation = {
                Expectation(values = mapOf("IFD0:Make" to "PICT GOLD", "IFD0:Model" to "PICT GOLD MODEL"))
            },
        ),
        GoldCase(
            id = "jpeg-time-shift-plus-1h",
            sample = "canon-40d.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(listOf(EditOperation.TimeShift(deltaMillis = 3_600_000L))),
            expectation = { source -> shiftBy(source, 3_600_000L) },
        ),
        GoldCase(
            id = "jpeg-clear-time",
            sample = "canon-40d.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(listOf(EditOperation.ClearTargets(setOf(ClearTarget.TIME)))),
            expectation = { source -> goneWhere(source, TIME_TAGS) },
        ),
        GoldCase(
            id = "jpeg-clear-field",
            sample = "canon-40d.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(listOf(EditOperation.ClearField(TagKey.of("EXIF:Software")))),
            expectation = { source -> goneWhere(source, setOf(EXIF_SOFTWARE)) },
        ),
        GoldCase(
            id = "jpeg-set-iso-aperture",
            sample = "nikon-d70.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(
                listOf(
                    EditOperation.SetField(
                        TagKey.of("EXIF:ISOSpeedRatings"),
                        TagValue.IntList(listOf(400L)),
                    ),
                    EditOperation.SetField(
                        TagKey.of("EXIF:FNumber"),
                        TagValue.RationalValue(Rational(56L, 10L)),
                    ),
                ),
            ),
            expectation = {
                Expectation(
                    values = mapOf("ExifIFD:ISO" to "400", "ExifIFD:FNumber" to "5.6"),
                    numeric = setOf("ExifIFD:FNumber"),
                )
            },
        ),
        GoldCase(
            id = "jpeg-gps-north-east",
            sample = "gps-dscn0010.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(listOf(EditOperation.SetGps(latitude = 31.2304, longitude = 121.4737))),
            expectation = { source -> gpsExpectation(source, 31.2304, 121.4737, "N", "E") },
        ),
        GoldCase(
            id = "jpeg-gps-south-west",
            sample = "gps-dscn0010.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(listOf(EditOperation.SetGps(latitude = -33.8688, longitude = -151.2093))),
            expectation = { source -> gpsExpectation(source, -33.8688, -151.2093, "S", "W") },
        ),
        GoldCase(
            id = "jpeg-clear-gps",
            sample = "gps-dscn0010.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(listOf(EditOperation.ClearTargets(setOf(ClearTarget.GPS)))),
            expectation = { source ->
                // GPSVersionID 是号令字段，不在 ClearGroups 的清除口径里；
                // 它会因为上游库解析不到 GPS 目录的 tag 0 而丢 —— 那是 defaultGaps 里的缺口，
                // 不是这里要清的（见 docs/09 R-17）
                goneWhere(source, source.keys.filter { it.startsWith("GPS:") }.toSet() - "GPS:GPSVersionID")
            },
        ),
        GoldCase(
            id = "jpeg-iptc-untouched",
            sample = "iptc-canon.jpg",
            format = ImageFormatHint.JPEG,
            plan = EditPlan(
                listOf(
                    EditOperation.SetField(
                        TagKey.of("EXIF:ImageDescription"),
                        TagValue.Text("PICT GOLD IPTC"),
                    ),
                ),
            ),
            expectation = { source ->
                Expectation(
                    values = mapOf("IFD0:ImageDescription" to "PICT GOLD IPTC"),
                    intact = source.keys.filter { it.startsWith("IPTC:") }.toSet(),
                )
            },
        ),
        GoldCase(
            id = "tiff-set-description",
            sample = "pict-tiff-xp.tif",
            format = ImageFormatHint.TIFF,
            plan = EditPlan(
                listOf(
                    EditOperation.SetField(
                        TagKey.of("EXIF:ImageDescription"),
                        TagValue.Text("PICT GOLD TIFF"),
                    ),
                ),
            ),
            expectation = { Expectation(values = mapOf("IFD0:ImageDescription" to "PICT GOLD TIFF")) },
        ),
        GoldCase(
            id = "tiff-clear-xp-title",
            sample = "pict-tiff-xp.tif",
            format = ImageFormatHint.TIFF,
            plan = EditPlan(listOf(EditOperation.ClearField(TagKey.of("EXIF:XPTitle")))),
            expectation = { source -> goneWhere(source, setOf("IFD0:XPTitle")) },
        ),
    )

    // ---- 用例执行 -----------------------------------------------------------

    private fun runCase(
        case: GoldCase,
        channel: Channel = Channel.COMMONS,
        /** 通道已知偏差必须一条不少地出现：写通道行为一变就报错，而不是悄悄放宽。 */
        strictDeviations: Boolean = false,
    ) {
        val original = sample(case.sample)
        val info = SourceInfo(case.sample, case.mime, original.size.toLong(), case.format)
        if (channel == Channel.PROD) {
            assertTrue(
                "生产通道应当认领 ${case.format.label}，否则这条用例根本没跑到它（docs/02 §5）",
                prodStore.canWrite(info),
            )
        }
        val current = readFor(channel, original, info)

        val outcome = when (val folded = EditPlanExecutor.execute(case.plan, current)) {
            is PictResult.Success -> folded.value
            is PictResult.Failure -> throw AssertionError("折叠失败：${folded.error}（${folded.code}）")
        }
        assertTrue(
            "折叠结果没有任何改动，这个用例证明不了写通道",
            outcome.changedKeys.isNotEmpty() || outcome.segmentClears.isNotEmpty(),
        )

        val ext = case.format.extensions.first()
        val edited = when (channel) {
            Channel.COMMONS -> ByteArrayOutputStream()
                .also { write(original, outcome.target, it, case.format) }
                .toByteArray()

            // 生产通道：写进临时文件再读回来。文件留在产物目录里，崩了能直接验尸
            Channel.PROD -> writeProd(case, original, outcome.target)
        }
        val srcFile = artifact(case, channel, "src.$ext").apply { writeBytes(original) }
        val outFile = artifact(case, channel, ext).apply { writeBytes(edited) }

        // 1) 像素不动：无损通道必须把图像数据原样搬运
        when (case.format) {
            ImageFormatHint.JPEG -> assertArrayEquals(
                "JPEG 扫描数据必须逐字节不变",
                scanData(original),
                scanData(edited),
            )

            else -> assertArrayEquals(
                "TIFF 原始 strip 必须逐字节不变",
                tiffStrip(original),
                tiffStrip(edited),
            )
        }

        // 1b) 缩略图字节不动：R-16 修的正是这里 —— IFD1 的**图**不在 directoryEntries 里
        // （那里只有 ThumbnailOffset/Length 两个指针），得显式交给输出目录才留得住。
        thumbnailOf(original, case.format)?.let { want ->
            assertArrayEquals(
                "IFD1 缩略图必须逐字节不变（R-16：只改文本字段也不能把缩略图弄丢/重编码）",
                want,
                thumbnailOf(edited, case.format),
            )
        }

        // 2) 由 exiftool 独立读回
        val source = exiftoolDump(srcFile)
        val actual = exiftoolDump(outFile)
        val expect = case.expectation(source)
        assertTrue("期望清单为空", expect.values.isNotEmpty() || expect.gone.isNotEmpty())

        expect.values.forEach { (key, want) ->
            val got = actual[key]
            assertNotNull("exiftool 读不到 $key（期望 $want）", got)
            if (key in expect.numeric) {
                val wantNumber = want.toDouble()
                assertTrue(
                    "$key 期望 $wantNumber±，exiftool 读到 $got",
                    abs(got!!.toDouble() - wantNumber) <= 1e-6,
                )
            } else {
                assertEquals("$key 的值不对", want, got)
            }
            if (key !in expect.unchangedOk) {
                assertNotEquals("$key 与源文件的值相同，说明这次编辑没有真的落盘", want, source[key])
            }
        }

        expect.gone.forEach { key ->
            assertNull("$key 本该已被清除，exiftool 仍读到 ${actual[key]}", actual[key])
            assertNotNull("$key 在源文件里就不存在，这条断言证明不了任何事", source[key])
        }

        expect.intact.forEach { key ->
            assertEquals("$key 不该被动过", source[key], actual[key])
        }

        // 3) 已确认的缺口：写通道目前留不住这些字段，逐个钉住（详见 KNOWN_GAPS）
        val gaps = gapsFor(channel, case.format, source)
        gaps.forEach { key ->
            assertNull("$key 本该被保留，现在却丢了；缺口清单要跟着改（见 KNOWN_GAPS）", actual[key])
            assertNotNull("$key 在源文件里就不存在，缺口清单对它没意义", source[key])
        }

        // 4) 改到的字段只能落在意图内（意图 = 新值 + 该消失的键 + 已确认缺口 + 通道已知偏差）
        val deviations = deviationsOf(channel, case, source)
        val intent = expect.values.keys + expect.gone + gaps
        val changed = changedKeys(source, actual)
        val allowed = allowlist(changed) + deviations.flatMap { it.keys }
        val unexpected = (changed - intent - allowed).sorted()
        report(case, channel, srcFile, outFile, source, actual, changed, intent, gaps, allowed, deviations, expect)

        assertTrue(
            "除目标字段外还有 ${unexpected.size} 个字段被改动：" +
                unexpected.joinToString { "$it(${source[it]}→${actual[it]})" },
            unexpected.isEmpty(),
        )

        // 5) 通道已知偏差逐条钉住：不是「允许它变」，而是「必须变成说好的那个值」
        deviations.forEach { deviation -> deviation.check(actual) }
        if (strictDeviations) {
            val missed = prodDeviations.filter { it.sample == case.sample && !it.applies(case, source) }
            assertTrue(
                "本样本该命中的通道偏差有 ${missed.size} 条没命中（${missed.joinToString { it.keys.first() }}）：" +
                    "写通道行为变了，逐条复核 [prodDeviations] 与 docs/09 R-18",
                missed.isEmpty(),
            )
        }
    }

    /**
     * 缺口匹配器：写通道留不住的东西。
     *
     * [why] 是给人看的账：这些字段不是测试让掉的，是产品在丢数据。
     */
    private class GapRule(val why: String, val matches: (String) -> Boolean)

    /**
     * 一条通道偏差规则。
     *
     * [sample] 必填：样本是仓库里的固定文件，偏差是拿 exiftool 逐份量出来的，
     * 换个样本得重新量，不许把 A 样本的数字套到 B 样本上。
     * [keys] 是这条规则管住的键，用于「意外改动」判定；[check] 负责逐条钉住——
     * 不是「允许它变」，而是「必须变成说好的那样」。
     */
    private class Deviation(
        val sample: String,
        val keys: Set<String>,
        val why: String,
        val check: (Map<String, String>) -> Unit,
        val appliesNow: (Map<String, String>) -> Boolean = { true },
    ) {
        fun applies(case: GoldCase, source: Map<String, String>): Boolean =
            case.sample == sample && appliesNow(source)
    }

    /**
     * 生产通道的**已知写偏差**（docs/09 R-18）。只列确实会变的键，且必须写清机制。
     *
     * 与 [allowlist] 的区别：白名单是「本来就不算用户数据」（文件名、偏移量、派生值），
     * 这里是「本该保留却变了」——属于写通道的缺陷，钉住是为了修好之后立刻炸出来。
     */
    private val prodDeviations: List<Deviation> = listOf(
        // ---- 值被改写 --------------------------------------------------------
        rewritten(
            sample = CANON_40D,
            key = "ExifIFD:ComponentsConfiguration",
            expected = "63 63 63 0",
            why = RAW_BYTES_AS_STRING,
        ),
        rewritten(
            sample = GPS_NIKON,
            key = "ExifIFD:ComponentsConfiguration",
            expected = "63 63 63 0",
            why = RAW_BYTES_AS_STRING,
        ),
        rewritten(
            sample = GPS_NIKON,
            key = "ExifIFD:FileSource",
            expected = "?",
            why = RAW_BYTES_AS_STRING,
        ),
        rewritten(
            sample = GPS_NIKON,
            key = "ExifIFD:SceneType",
            expected = "?",
            why = RAW_BYTES_AS_STRING,
        ),
        // ---- R-19 那条「读侧默认值被写回」的偏差已于 2026-09-11 退休 -------------------
        // 原来这里钉着 `rewritten(CANON_40D, "ExifIFD:LightSource", expected = "0")`：源文件没有这个标签，
        // 读侧补默认值 0、写侧整表回写，于是凭空落盘。第八轮把判据换到字节层 IFD 目录（IfdTagIndex）
        // 之后，写侧不再产生这个标签，规则当场红（`expected:<0> but was:<null>`）——按金标准的纪律，
        // 偏差消失就该删条目，而不是把断言改松。
        // 注意：这条泄漏的证人**不在金标准里**——金标准比的是「我们自己读产物」和「exiftool 读产物」，
        // 产物真的多出一个标签时两边都会看到它、反而算「匹配」。真正的证人在 ExifWriteHonestyTest：
        // 诚实读出的字段集回写后，直接查产物字节的 IFD 目录里有没有 0x9208。
        // ---- IFD0 被补上尺寸/压缩 -------------------------------------------
        mirrored(
            sample = CANON_40D,
            key = "IFD0:ImageWidth",
            masterKey = "File:ImageWidth",
            why = "源文件只在 File 组（SOF 段）里记着宽高，重写后 IFD0 被补上一份",
        ),
        mirrored(
            sample = CANON_40D,
            key = "IFD0:ImageHeight",
            masterKey = "File:ImageHeight",
            why = "同上，IFD0 被补上高度",
        ),
        mirrored(
            sample = CANON_40D,
            key = "IFD0:Compression",
            masterKey = "IFD1:Compression",
            why = "IFD0 被补上缩略图那份 Compression",
        ),
        mirrored(
            sample = GPS_NIKON,
            key = "IFD0:ImageWidth",
            masterKey = "File:ImageWidth",
            why = "同 canon-40d.jpg：IFD0 被补上 SOF 里的宽",
        ),
        mirrored(
            sample = GPS_NIKON,
            key = "IFD0:ImageHeight",
            masterKey = "File:ImageHeight",
            why = "同上，IFD0 被补上高",
        ),
        mirrored(
            sample = GPS_NIKON,
            key = "IFD0:Compression",
            masterKey = "IFD1:Compression",
            why = "同上，IFD0 被补上缩略图那份 Compression",
        ),
        // ---- IFD1 被 IFD0 抹平 ----------------------------------------------
        // saveAttributes 把两个 IFD 汇成一张表再回写，于是两份不同的值被写成同一个。
        mirrored(sample = CANON_40D, key = "IFD1:Make", masterKey = "IFD0:Make", why = IFD1_MIRROR),
        mirrored(sample = CANON_40D, key = "IFD1:Model", masterKey = "IFD0:Model", why = IFD1_MIRROR),
        mirrored(sample = CANON_40D, key = "IFD1:Software", masterKey = "IFD0:Software", why = IFD1_MIRROR),
        mirrored(sample = CANON_40D, key = "IFD1:ModifyDate", masterKey = "IFD0:ModifyDate", why = IFD1_MIRROR),
        mirrored(
            sample = CANON_40D,
            key = "IFD1:YCbCrPositioning",
            masterKey = "IFD0:YCbCrPositioning",
            why = IFD1_MIRROR,
        ),
        mirrored(sample = GPS_NIKON, key = "IFD1:Make", masterKey = "IFD0:Make", why = IFD1_MIRROR),
        mirrored(sample = GPS_NIKON, key = "IFD1:Model", masterKey = "IFD0:Model", why = IFD1_MIRROR),
        mirrored(sample = GPS_NIKON, key = "IFD1:Software", masterKey = "IFD0:Software", why = IFD1_MIRROR),
        mirrored(sample = GPS_NIKON, key = "IFD1:ModifyDate", masterKey = "IFD0:ModifyDate", why = IFD1_MIRROR),
        mirrored(
            sample = GPS_NIKON,
            key = "IFD1:YCbCrPositioning",
            masterKey = "IFD0:YCbCrPositioning",
            why = IFD1_MIRROR,
        ),
        mirrored(sample = GPS_NIKON, key = "IFD1:XResolution", masterKey = "IFD0:XResolution", why = IFD1_MIRROR),
        mirrored(sample = GPS_NIKON, key = "IFD1:YResolution", masterKey = "IFD0:YResolution", why = IFD1_MIRROR),
    )

    /** 值被改写成说好的那个常量。 */
    private fun rewritten(sample: String, key: String, expected: String?, why: String): Deviation = Deviation(
        sample = sample,
        keys = setOf(key),
        why = why,
        check = { actual -> assertEquals("通道偏差 $key（$why）", expected, actual[key]) },
        appliesNow = { source -> source[key] != expected },
    )

    /**
     * 该键被「抹成」[masterKey] 的值（通常是 IFD1 被 IFD0 覆盖）。
     *
     * 只在两份**本来就不一致**时才要求被抹平：本来一致的话，抹没抹看不出来，
     * 硬要求就会变成「用一个恒等式假装在钉住什么东西」。
     */
    private fun mirrored(sample: String, key: String, masterKey: String, why: String): Deviation = Deviation(
        sample = sample,
        keys = setOf(key),
        why = why,
        check = { actual -> assertEquals("$key 该被 $masterKey 抹成同一个值（$why）", actual[masterKey], actual[key]) },
        appliesNow = { source -> source[masterKey] != source[key] },
    )

    /** 只留「本用例确实该管」的规则；[runCase] 的 `strictDeviations` 会核对有没有漏的。 */
    private fun deviationsOf(channel: Channel, case: GoldCase, source: Map<String, String>): List<Deviation> =
        if (channel == Channel.COMMONS) emptyList() else prodDeviations.filter { it.applies(case, source) }

    /**
     * 写通道**目前**留不住的字段（金标准跑出来的缺口，不是测试将就）。
     *
     * 每条都必须说清机制；修好之后这里会立刻失败，逼着删条目并更新 `docs/08` 的记录。
     */
    private fun defaultGaps(format: ImageFormatHint, source: Map<String, String>): Set<String> = buildSet {
        // 缺口：commons-imaging 的 GPS 目录解析**不暴露 tag 0（GPSVersionID）**，
        // 写回时输出集里压根没有这个字段，于是重写后就没了（JPEG/TIFF 都丢）。
        // 读不到就写不回 —— 属于上游库的限制，不是我们的映射表漏了：GPS 目录里其它
        // 字段（LatRef/Lat/LonRef/Lon…）都能原样 round-trip，只有 tag 0 例外。
        // 另有极端情况：canon-40d.jpg 的整张 GPS IFD（只有 VersionID 一个字段）
        // 被解析成 0 条目，那份 GPS 块只能整体丢。见 docs/09 R-17。
        add("GPS:GPSVersionID")
    }.filterTo(linkedSetOf()) { source.containsKey(it) }

    /**
     * 生产通道（[ExifMetadataStore]）留不住的字段。
     *
     * 与 [defaultGaps] 分表：缺口成因不同（一个是 commons-imaging 解不出 GPS tag 0，
     * 另一个是 ExifInterface 的标签表里压根没有），混成一张表就分不清是谁的锅了。
     *
     * 用匹配器而不是逐个键名：厂商私有 MakerNote 一动就是几十个标签，
     * 逐个列出来反而盖住了「整块写坏」这个事实。
     */
    private val prodGaps: List<GapRule> = listOf(
        GapRule("ExifInterface 的标签表里没有 InteropVersion，重写 APP1 时整条丢") { key ->
            key == "InteropIFD:InteropVersion"
        },
        GapRule("厂商私有 MakerNote 被整块写坏（exiftool 报 Bad MakerNotes directory），块里的标签全灭") { key ->
            key.startsWith("Nikon:")
        },
    )

    private fun gapsFor(channel: Channel, format: ImageFormatHint, source: Map<String, String>): Set<String> =
        when (channel) {
            Channel.COMMONS -> defaultGaps(format, source)
            Channel.PROD -> source.keys.filterTo(linkedSetOf()) { key -> prodGaps.any { it.matches(key) } }
        }

    @Test
    fun `JPEG 改文本字段`() = runCase(caseOf("jpeg-set-make-model"))

    // ---- 生产通道（ExifMetadataStore）------------------------------------------
    // 同一批样本再跑一遍。真机走的就是这条通道，commons-imaging 只是 TIFF 兜底
    // （docs/09 R-18：金标准以前只跑了兜底那条，等于没测到生产路径）。

    @Test
    fun `生产通道 JPEG 改文本字段`() =
        runCase(caseOf("jpeg-set-make-model"), Channel.PROD, strictDeviations = true)

    @Test
    fun `生产通道 JPEG 写北纬东经坐标`() =
        runCase(caseOf("jpeg-gps-north-east"), Channel.PROD, strictDeviations = true)

    @Test
    fun `生产通道 JPEG 按类别清空时间字段`() = runCase(caseOf("jpeg-clear-time"), Channel.PROD)

    @Test
    fun `生产通道 JPEG 删单个字段`() = runCase(caseOf("jpeg-clear-field"), Channel.PROD)

    @Test
    fun `生产通道认领 JPEG PNG WebP 而不认领 TIFF 与 HEIF`() {
        fun info(name: String, mime: String, format: ImageFormatHint) = SourceInfo(name, mime, 1L, format)

        assertTrue("JPEG 该归生产通道", prodStore.canWrite(info("a.jpg", "image/jpeg", ImageFormatHint.JPEG)))
        assertTrue("PNG 该归生产通道", prodStore.canWrite(info("a.png", "image/png", ImageFormatHint.PNG)))
        assertTrue("WebP 该归生产通道", prodStore.canWrite(info("a.webp", "image/webp", ImageFormatHint.WEBP)))
        assertFalse("TIFF 是 commons-imaging 的地盘", prodStore.canWrite(info("a.tif", "image/tiff", ImageFormatHint.TIFF)))
        assertFalse("HEIF 元数据在 ISO-BMFF box 里，写不了", prodStore.canWrite(info("a.heic", "image/heic", ImageFormatHint.HEIF)))
    }

    @Test
    fun `JPEG 时间整体平移一小时`() = runCase(caseOf("jpeg-time-shift-plus-1h"))

    @Test
    fun `JPEG 按类别清空时间字段`() = runCase(caseOf("jpeg-clear-time"))

    @Test
    fun `JPEG 删单个字段`() = runCase(caseOf("jpeg-clear-field"))

    @Test
    fun `JPEG 改 ISO 与光圈`() = runCase(caseOf("jpeg-set-iso-aperture"))

    @Test
    fun `JPEG 写北纬东经坐标`() = runCase(caseOf("jpeg-gps-north-east"))

    @Test
    fun `JPEG 写南纬西经坐标带 Ref 翻转`() = runCase(caseOf("jpeg-gps-south-west"))

    @Test
    fun `JPEG 按类别清空位置字段`() = runCase(caseOf("jpeg-clear-gps"))

    @Test
    fun `JPEG 改 EXIF 不动 IPTC`() = runCase(caseOf("jpeg-iptc-untouched"))

    @Test
    fun `TIFF 改字段`() = runCase(caseOf("tiff-set-description"))

    @Test
    fun `TIFF 清 XP 字段`() = runCase(caseOf("tiff-clear-xp-title"))

    /**
     * 边界：本通道写不了的格式必须明说写不了（docs/02 §5「HEIF 需重编码」）。
     * 只读样本 PNG/WebP 由 ExifInterface 通道负责，不在 commons-imaging 手里。
     */
    @Test
    fun `HEIF 与 PNG WebP RAW 都不走无损通道`() {
        listOf(
            "heic-tiny.heic" to ImageFormatHint.HEIF,
            "png-tiny.png" to ImageFormatHint.PNG,
            "webp-tiny.webp" to ImageFormatHint.WEBP,
            "canon-40d.jpg" to ImageFormatHint.RAW,
        ).forEach { (name, format) ->
                val bytes = sample(name)
                val info = SourceInfo(name, null, bytes.size.toLong(), format)
                assertTrue(
                    "$name（${format.label}）不该被 commons-imaging 通道认领：改了就得整段重编码",
                    !store.canWrite(info),
                )
            }
        assertTrue("JPEG 应当仍可写", store.canWrite(SourceInfo("a.jpg", "image/jpeg", 1L, ImageFormatHint.JPEG)))
        assertTrue("TIFF 应当仍可写", store.canWrite(SourceInfo("a.tif", "image/tiff", 1L, ImageFormatHint.TIFF)))
    }

    private fun caseOf(id: String): GoldCase =
        requireNotNull(cases.firstOrNull { it.id == id }) { "没有这个用例：$id" }

    // ---- 写通道与读通道 -----------------------------------------------------

    private fun read(bytes: ByteArray, info: SourceInfo): MetadataSet = when (info.format) {
        ImageFormatHint.JPEG -> store.readFrom(
            requireNotNull(store.exifMetadataOf(bytes)) { "样本 ${info.displayName} 应带 EXIF" },
            info,
        )

        ImageFormatHint.TIFF -> store.readFrom(
            Imaging.getMetadata(bytes) as TiffImageMetadata,
            info,
        )

        else -> throw AssertionError("${info.format.label} 不走本通道")
    }

    private fun write(bytes: ByteArray, target: MetadataSet, out: ByteArrayOutputStream, format: ImageFormatHint) {
        when (format) {
            ImageFormatHint.JPEG -> store.rewriteJpeg(bytes, target, out)
            ImageFormatHint.TIFF -> store.rewriteTiff(bytes, target, out)
            else -> throw AssertionError("$format 不走本通道")
        }
    }

    private fun readFor(channel: Channel, bytes: ByteArray, info: SourceInfo): MetadataSet = when (channel) {
        Channel.COMMONS -> read(bytes, info)
        Channel.PROD -> prodStore.readFrom(ExifInterface(bytes.inputStream()), info, bytes)
    }

    /**
     * 生产通道的写：字节落到工作文件 → 在文件上写 → 读回字节。
     *
     * 真机上是 `ContentResolver.openFileDescriptor` 之后 `ExifInterface(FileDescriptor)` + 落盘，
     * 这里换成 `ExifInterface(File)`——同一个 `writeTo`，同一份 `saveAttributes` 代码路径。
     * 工作文件留在产物目录里（`.prod.working.jpg`），写入/丢弃清单另存 `.prod.write.tsv`。
     */
    private fun writeProd(case: GoldCase, original: ByteArray, target: MetadataSet): ByteArray {
        val file = artifact(case, Channel.PROD, "working.${case.format.extensions.first()}")
        file.writeBytes(original)
        val result = prodStore.writeTo(ExifInterface(file), target)
        artifact(case, Channel.PROD, "write.tsv").writeText(
            buildString {
                appendLine("写入\t${result.writtenKeys.size}\t${result.writtenKeys.joinToString()}")
                appendLine("丢弃\t${result.droppedKeys.size}\t${result.droppedKeys.joinToString()}")
            },
            Charsets.UTF_8,
        )
        return file.readBytes()
    }

    /** 取 IFD1（缩略图 IFD）里内嵌的缩略图字节；样本没缩略图就返回 null。 */
    private fun thumbnailOf(bytes: ByteArray, format: ImageFormatHint): ByteArray? {
        val metadata = when (format) {
            ImageFormatHint.JPEG -> store.exifMetadataOf(bytes)
            else -> Imaging.getMetadata(bytes) as? TiffImageMetadata
        } ?: return null
        val ifd1 = metadata.contents.directories.firstOrNull { it.type == THUMBNAIL_IFD } ?: return null
        return ifd1.jpegImageData?.data ?: ifd1.tiffImageData?.imageData?.firstOrNull()?.data
    }

    // ---- exiftool ----------------------------------------------------------

    private fun exiftool(): String? {
        System.getenv("PICT_EXIFTOOL")?.takeIf { it.isNotBlank() && File(it).exists() }?.let { return it }
        CANDIDATES.firstOrNull { File(it).exists() }?.let { return it }
        return runCatching {
            val process = ProcessBuilder("exiftool", "-ver").redirectErrorStream(true).start()
            val ok = process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
            process.inputStream.close()
            if (ok) "exiftool" else null
        }.getOrNull()
    }

    /** `-a -G1 -s -n` 的长格式：`[组] 标签 : 值` → `组:标签` → 值。 */
    private fun exiftoolDump(file: File): Map<String, String> {
        val exe = requireNotNull(exiftool())
        val process = ProcessBuilder(exe, "-a", "-G1", "-s", "-n", file.absolutePath).start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue("exiftool 读取 ${file.name} 超时", process.waitFor(120, TimeUnit.SECONDS))
        assertEquals("exiftool 读取 ${file.name} 失败：$stderr", 0, process.exitValue())

        val dump = linkedMapOf<String, String>()
        stdout.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val match = TAG_LINE.matchEntire(line) ?: return@forEach
            dump["${match.groupValues[1]}:${match.groupValues[2]}"] = match.groupValues[3].trim()
        }
        assertTrue("exiftool 没吐出任何字段：${file.name}", dump.isNotEmpty())
        return dump
    }

    private fun exiftoolJson(file: File): String {
        val exe = requireNotNull(exiftool())
        val process = ProcessBuilder(exe, "-j", "-a", "-G1", "-s", "-n", "-struct", file.absolutePath).start()
        return process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also {
            process.errorStream.close()
            process.waitFor(120, TimeUnit.SECONDS)
        }
    }

    // ---- 期望值构造 ---------------------------------------------------------

    private fun gpsExpectation(
        source: Map<String, String>,
        lat: Double,
        lon: Double,
        latRef: String,
        lonRef: String,
    ): Expectation = Expectation(
        values = mapOf(
            "GPS:GPSLatitudeRef" to latRef,
            "GPS:GPSLongitudeRef" to lonRef,
            "GPS:GPSLatitude" to abs(lat).toString(),
            "GPS:GPSLongitude" to abs(lon).toString(),
        ),
        numeric = setOf("GPS:GPSLatitude", "GPS:GPSLongitude"),
        // 样本本来就是北纬东经：写同一象限时 Ref 不该变，也就不能要求它「必须变了」
        unchangedOk = buildSet {
            if (source["GPS:GPSLatitudeRef"] == latRef) add("GPS:GPSLatitudeRef")
            if (source["GPS:GPSLongitudeRef"] == lonRef) add("GPS:GPSLongitudeRef")
        },
    )

    /** 时间平移：期望值 = 源文件经 exiftool 读出的值 + 偏移量，不参考本工具的输出。 */
    private fun shiftBy(source: Map<String, String>, deltaMillis: Long): Expectation {
        val values = TIME_TAGS.mapNotNull { key ->
            val raw = source[key] ?: return@mapNotNull null
            // SubSecTime* 读出来是 "00" 这种小数秒，不是时间戳，不参与平移
            if (!EXIF_TIME_LIKE.matches(raw)) return@mapNotNull null
            val shifted = LocalDateTime.parse(raw, EXIF_TIME).plusNanos(deltaMillis * 1_000_000L)
            key to shifted.format(EXIF_TIME)
        }.toMap()
        assertTrue("源文件里没有可平移的时间字段", values.isNotEmpty())
        return Expectation(values = values)
    }

    /** 清除类用例：源文件里真有的那些键才要求消失（空集会被 [runCase] 挡住）。 */
    private fun goneWhere(source: Map<String, String>, candidates: Set<String>): Expectation =
        Expectation(gone = candidates.filter { source.containsKey(it) }.toSet())

    // ---- 报告与白名单 -------------------------------------------------------

    private fun report(
        case: GoldCase,
        channel: Channel,
        srcFile: File,
        outFile: File,
        source: Map<String, String>,
        actual: Map<String, String>,
        changed: Set<String>,
        intent: Set<String>,
        gaps: Set<String>,
        allowed: Set<String>,
        deviations: List<Deviation>,
        expect: Expectation,
    ) {
        artifact(case, channel, "checks.tsv").writeText(
            buildString {
                expect.values.forEach { (key, value) -> appendLine("value\t$key\t$value") }
                expect.gone.sorted().forEach { appendLine("gone\t$it\t") }
                expect.intact.sorted().forEach { appendLine("intact\t$it\t${source[it].orEmpty()}") }
                // 缺口：脚本自己也用 exiftool 确认一遍「确实丢了」，但不计入失败
                gaps.sorted().forEach { appendLine("gap\t$it\t") }
            },
            Charsets.UTF_8,
        )
        artifact(case, channel, "gaps.txt").writeText(
            buildString {
                if (gaps.isEmpty()) {
                    // 一律写盘：只写非空的话，上一轮遗留的 gaps.txt 会假装现在还缺字段
                    appendLine("# ${case.id}（${channel.label}）：写通道没有留不住的字段（本条用例无缺口）")
                } else {
                    appendLine("# ${case.id}（${channel.label}）：写通道目前留不住的字段（不是测试将就，是产品缺口）")
                    gaps.sorted().forEach { key ->
                        appendLine("$key\t${source[key]} -> (无)")
                    }
                }
            },
            Charsets.UTF_8,
        )
        artifact(case, channel, "changed.txt").writeText(
            buildString {
                appendLine(
                    "# ${case.id}  通道 ${channel.label}  样本 ${case.sample}  " +
                        "意图 ${intent.size - gaps.size} 项  缺口 ${gaps.size} 项  偏差 ${deviations.size} 条",
                )
                appendLine("# 源 ${srcFile.name}  改后 ${outFile.name}")
                changed.sorted().forEach { key ->
                    // 缺口排在意图前面：缺口是产品在丢数据，写成「意图」就成了「我们故意的」
                    val mark = when (key) {
                        in gaps -> "缺口"
                        in intent -> "意图"
                        in deviations.flatMap { it.keys } -> "偏差"
                        in allowed -> "白名单"
                        else -> "其它"
                    }
                    appendLine("[$mark] $key\t${source[key] ?: "(无)"} -> ${actual[key] ?: "(无)"}")
                }
            },
            Charsets.UTF_8,
        )
        artifact(case, channel, "exiftool.json").writeText(exiftoolJson(outFile), Charsets.UTF_8)
    }

    /**
     * 产物文件名：PROD 通道带 `.prod.` 中缀，两条通道跑同一个用例时互不覆盖
     * （`jpeg-set-make-model.jpg` 与 `jpeg-set-make-model.prod.jpg`）。
     */
    private fun artifact(case: GoldCase, channel: Channel, suffix: String): File =
        File(outDir, "${case.id}.${channel.infix}$suffix")

    /**
     * 白名单：不是「用户数据」的键，改元数据时本来就该跟着动。
     * 列进这里必须能说清理由，否则就是在放水。
     *
     * 注意传进来的是**改动集**而不是读回结果：字段消失时它不在读回结果里，
     * 按读回结果算白名单会把这些键误判成「意外改动」。
     */
    private fun allowlist(changed: Set<String>): Set<String> = buildSet {
        changed.filterTo(this) { it.startsWith("System:") }        // 文件名/大小/时间戳/权限
        changed.filterTo(this) { it.startsWith("ExifTool:") }      // 工具版本号
        // 派生量：exiftool 现算的，不落盘；真要出问题，被派生的真实字段会先报出来
        changed.filterTo(this) { it.startsWith("Composite:") }
        // 结构偏移量：元数据变长就跟着挪，图像数据本身由 scanData/tiffStrip 逐字节校验
        add("IFD1:ThumbnailOffset")
        add("IFD0:StripOffsets")
        // exiftool 现算的 MakerNote 字节序标记：MakerNote 被重写后按新布局重新解读，不是用户数据
        add("File:MakerNoteByteOrder")
    }

    private fun changedKeys(source: Map<String, String>, actual: Map<String, String>): Set<String> =
        (source.keys + actual.keys).filterTo(linkedSetOf()) { source[it] != actual[it] }

    // ---- 字节级工具 ---------------------------------------------------------

    private fun sample(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/samples/$name")) { "缺少测试样张 /samples/$name" }
            .use { it.readBytes() }

    /** 从 SOS 段起到文件末尾：压缩后的图像数据，无损重写时应当逐字节不变。 */
    private fun scanData(jpeg: ByteArray): ByteArray {
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == MARKER_SOS) return jpeg.copyOfRange(pos, jpeg.size)
            if (marker == MARKER_SOI || marker == MARKER_TEM || marker in MARKER_RST_FIRST..MARKER_RST_LAST) {
                pos += 2
                continue
            }
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            pos += 2 + length
        }
        throw AssertionError("样本里没有找到 SOS 段")
    }

    /** TIFF 的原始 strip 字节，用来证明像素没被重新编码。 */
    private fun tiffStrip(tiff: ByteArray): ByteArray {
        val metadata = Imaging.getMetadata(tiff) as TiffImageMetadata
        val offset = requireNotNull(metadata.findField(TiffTagConstants.TIFF_TAG_STRIP_OFFSETS)).intValue
        val count = requireNotNull(metadata.findField(TiffTagConstants.TIFF_TAG_STRIP_BYTE_COUNTS)).intValue
        return tiff.copyOfRange(offset, offset + count)
    }

    // ---- 类型 ---------------------------------------------------------------

    private data class Expectation(
        val values: Map<String, String> = emptyMap(),
        val gone: Set<String> = emptySet(),
        val intact: Set<String> = emptySet(),
        val numeric: Set<String> = emptySet(),
        /** 允许「值跟源文件一样」的键：比如写同象限坐标时 Ref 本来就不该变。 */
        val unchangedOk: Set<String> = emptySet(),
    )

    private data class GoldCase(
        val id: String,
        val sample: String,
        val format: ImageFormatHint,
        val plan: EditPlan,
        val expectation: (Map<String, String>) -> Expectation,
    ) {
        val mime: String?
            get() = when (format) {
                ImageFormatHint.JPEG -> "image/jpeg"
                ImageFormatHint.TIFF -> "image/tiff"
                else -> null
            }
    }

    private companion object {
        /** 生产通道偏差表里的样本名：偏差是一份样本一份量出来的，不许跨样本套用。 */
        const val CANON_40D = "canon-40d.jpg"
        const val GPS_NIKON = "gps-dscn0010.jpg"

        /** IFD1 被 IFD0 抹平的统一说法——机制一样，理由写一处就够。 */
        const val IFD1_MIRROR: String =
            "saveAttributes 把 IFD0/IFD1 汇成一张表再回写，IFD1 里跟 IFD0 不一样的取值被抹成了 IFD0 那份"

        /** 原始字节型标签被当字符串写的统一说法：每个非零字节都变成 0x3F（也就是 '?'）。 */
        const val RAW_BYTES_AS_STRING: String =
            "ExifInterface 把这类标签当字符串处理，原始字节被写成 0x3F（也就是 '?'）"

        /** IFD1（缩略图 IFD）的 directoryType。 */
        const val THUMBNAIL_IFD = 1

        const val MARKER_SOI = 0xD8
        const val MARKER_SOS = 0xDA
        const val MARKER_TEM = 0x01
        const val MARKER_RST_FIRST = 0xD0
        const val MARKER_RST_LAST = 0xD7
        const val TIFF_TAG_STRIP_OFFSETS = 273
        const val TIFF_TAG_STRIP_BYTE_COUNTS = 279

        val TAG_LINE = Regex("^\\[([^\\]]+)]\\s+(\\S+)\\s*:\\s?(.*)$")

        /** `yyyy:MM:dd HH:mm:ss`：用来把 SubSecTime 这种小数秒从时间类字段里筛掉。 */
        val EXIF_TIME_LIKE = Regex("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$")
        val EXIF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        /** 时间类字段（EXIF 与 XMP 两处都算），清除用例据此推导「该消失的键」。 */
        val TIME_TAGS: Set<String> = setOf(
            "IFD0:ModifyDate",
            "ExifIFD:DateTimeOriginal",
            "ExifIFD:CreateDate",
            "ExifIFD:OffsetTime",
            "ExifIFD:OffsetTimeOriginal",
            "ExifIFD:OffsetTimeDigitized",
            "ExifIFD:SubSecTime",
            "ExifIFD:SubSecTimeOriginal",
            "ExifIFD:SubSecTimeDigitized",
            "XMP:CreateDate",
            "XMP:ModifyDate",
            "XMP:DateTimeOriginal",
        )

        const val EXIF_SOFTWARE = "IFD0:Software"

        val CANDIDATES: List<String> = listOf(
            "D:/tools/exiftool/exiftool.exe",
            "D:/tools/exiftool/exiftool-13.59_64/exiftool.exe",
        )
    }
}
