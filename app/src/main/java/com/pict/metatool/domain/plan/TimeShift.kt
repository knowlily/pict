package com.pict.metatool.domain.plan

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import java.time.DateTimeException
import java.time.temporal.ChronoUnit

/**
 * 时间整体平移（docs/07 T2.7）。
 *
 * 语义依据 docs/04 §6.8：EXIF 日期不带时区，所以按**本地时间算术**处理、不跨时区换算；
 * 存在 `OffsetTime*` 时原样保留——它记的是「拍摄时相机本地偏移多少」，平移改变的是
 * 「几点拍的」，不是「相机在哪个时区」，跟着一起改反而会让两者自相矛盾。
 * `SubSecTime*` 同理保留：平移量最小到毫秒，不足以改写亚秒字段的语义。
 *
 * 同步范围是 EXIF 的 `DateTime` / `DateTimeOriginal` / `DateTimeDigitized`
 * ——docs/03 §3 的 TIME 组里，这三个是「同一时刻的三种表述」。源里缺哪个就跳过哪个，
 * 不凭空造值；三个都缺则显式失败，免得用户以为改了。
 *
 * 溢出：平移后超出 `LocalDateTime` 表示范围时返回失败而不是截断——截断会静默写出一个
 * 错误时间，比报错更糟。
 */
object TimeShift {

    /**
     * 参与同步的日期字段，顺序固定为「修改 → 拍摄 → 数字化」。
     * 顺序只影响 diff 的呈现顺序，不影响结果。
     */
    val SYNCED_KEYS: List<TagKey> = listOf(
        TagKey("EXIF", "DateTime"),
        TagKey("EXIF", "DateTimeOriginal"),
        TagKey("EXIF", "DateTimeDigitized"),
    )

    /**
     * 把 [set] 里的日期字段整体平移 [deltaMillis] 毫秒（正值向未来、负值向过去）。
     *
     * @return 平移后的新集合；无字段可平移、或任一字段溢出时返回失败
     */
    fun apply(set: MetadataSet, deltaMillis: Long): PictResult<MetadataSet> {
        if (deltaMillis == 0L) return successOf(set)

        val present = SYNCED_KEYS.mapNotNull { key ->
            (set[key] as? TagValue.Timestamp)?.let { key to it }
        }
        if (present.isEmpty()) {
            return failureOf(
                PictError.FIELD_INVALID,
                "源文件没有可平移的日期字段（${SYNCED_KEYS.joinToString("、") { it.full }}）",
            )
        }

        var shifted = set
        for ((key, value) in present) {
            val moved = try {
                value.value.plus(deltaMillis, ChronoUnit.MILLIS)
            } catch (e: DateTimeException) {
                return overflowFailure(key, e)
            } catch (e: ArithmeticException) {
                return overflowFailure(key, e)
            }
            shifted = shifted.with(key, value.copy(value = moved))
        }
        return successOf(shifted)
    }

    private fun overflowFailure(key: TagKey, cause: Throwable): PictResult<MetadataSet> =
        failureOf(
            PictError.FIELD_INVALID,
            "平移后 ${key.full} 超出可表示范围，未写入",
            cause,
        )
}
