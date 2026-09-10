package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.GpsEditor
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 把预设规则变成具体值（docs/07 T3.4、docs/03 §5/§6）。
 *
 * 三条设计约束：
 * 1. **种子决定一切**：所有随机都走构造时传入的 [random]（推荐 `java.util.Random(seed)`，
 *    其线性同余算法由 JDK 规范固定）。抽样顺序按字段全名字典序固定，同种子必然同结果（T3.6）。
 * 2. **不自己写 GPS 坐标**：经纬度输出成 `SetGps` 操作，交给 `GpsEditor` 连 `*Ref` 一起写。
 *    只往 `GPSLatitude` 塞值不改 Ref，会把南纬读成北纬——这条规则在 T2.8 定过一次，
 *    这里不重新实现一遍。
 * 3. **不假装成功**：字段目录没登记、目录标了只读、源里没有可沿用的值……一律记进
 *    [Result.skipped] 并给出中文原因，由调用方展示给用户；静默跳过会让人以为改了。
 *
 * `clamp` 约束的实现口径与 docs/03 §4.3 的字面描述有一处偏差：文档说「按 `FieldSpec.range`
 * 收敛」，而字段目录只登记类型/可写性/分组，没有区间字段。因此这里收敛到**该字段在预设里
 * 自己的 range 规则区间**；对池/固定值不做范围推断（猜一个上限不如不猜）。
 */
class MetadataRandomizer(private val random: Random) {

    /** 一个字段为什么没被填。 */
    data class Skip(val key: TagKey, val reason: String) {
        override fun toString(): String = "${key.full}：$reason"
    }

    /**
     * @param values 已定值的非坐标字段（含 XMP 里的坐标副本）
     * @param gpsOperations 坐标写操作（`SetGps`），由折叠器交给 `GpsEditor`
     * @param skipped 没能填的字段与原因
     * @param keptKeys 因为「只填空缺」而保留原值的字段
     */
    data class Result(
        val values: Map<TagKey, TagValue>,
        val gpsOperations: List<EditOperation>,
        val skipped: List<Skip>,
        val keptKeys: Set<TagKey>,
    ) {

        val isEmpty: Boolean get() = values.isEmpty() && gpsOperations.isEmpty()

        /** 展开成可交给 `EditPlanExecutor` 折叠的操作序列（坐标在前，便于定位失败原因）。 */
        fun toOperations(): List<EditOperation> =
            gpsOperations + values.map { (key, value) -> EditOperation.SetField(key, value) }
    }

    /**
     * @param base 源集合：`fromSource` / `jitter` / 成组约束判空都看它
     * @param keys 只填这些字段（null = 预设声明的全部）；不在预设里的键会被记进 [Result.skipped]
     * @param onlyMissing true = 只填源里没有的字段，已有值原样保留（`ApplyPreset` 的默认口径）
     */
    fun fill(
        preset: Preset,
        base: MetadataSet,
        keys: Set<TagKey>? = null,
        onlyMissing: Boolean = false,
    ): Result {
        val explicit = keys?.toSet()
        val candidates: Set<TagKey> = (explicit ?: preset.fields.keys).intersect(preset.fields.keys)
        val skipped = mutableListOf<Skip>()
        val kept = mutableSetOf<TagKey>()

        if (explicit != null) {
            (explicit - preset.fields.keys).sortedBy { it.full }.forEach { key ->
                skipped += Skip(key, "预设 ${preset.id} 没有为这个字段定义规则")
            }
        }

        val context = Context(preset, base, explicit, onlyMissing, skipped, kept)
        val values = linkedMapOf<TagKey, TagValue>()
        val gpsOperations = mutableListOf<EditOperation>()

        // 1) 坐标：同一圆心只采一个点，EXIF 与 XMP 副本拿到同一个坐标
        fillCoordinates(context, candidates, values, gpsOperations)

        // 2) 其余字段：按全名字典序抽样（顺序固定，种子才可复现）
        val derived = derivedKeys(preset, candidates)
        candidates.filter { it !in derived }.sortedBy { it.full }.forEach { key ->
            val rule = preset.rule(key) ?: return@forEach
            val spec = context.writableSpec(key) ?: return@forEach
            val value = sample(rule, key, spec, context) ?: return@forEach
            values[key] = value
        }

        // 3) 字段间约束：clamp → requires → mutex → datetimeOrder（docs/03 §4.3 的执行顺序）
        preset.constraints.forEach { constraint -> applyConstraint(constraint, context, candidates, values) }

        return Result(values, gpsOperations, skipped, kept)
    }

    /** 一次填充的共享上下文，避免把六个参数在内部函数间传来传去。 */
    private inner class Context(
        val preset: Preset,
        val base: MetadataSet,
        val explicit: Set<TagKey>?,
        val onlyMissing: Boolean,
        val skipped: MutableList<Skip>,
        val kept: MutableSet<TagKey>,
    ) {

        /**
         * 字段能否写入；不能则记录原因。
         * 「只填空缺」保留原值时记进 [kept]（不是故障，但用户该知道哪些没动）。
         */
        fun writableSpec(key: TagKey): FieldSpec? {
            if (onlyMissing && base[key] != null) {
                kept += key
                return null
            }
            val spec = FieldCatalog.spec(key)
            if (spec == null) {
                skipped += Skip(key, "字段目录未登记，写入通道不认识这个键")
                return null
            }
            if (!spec.canEdit) {
                skipped += Skip(key, "字段目录标记为「${spec.writability.label}」，不允许写入")
                return null
            }
            if (spec.privacySensitive && explicit?.contains(key) != true) {
                skipped += Skip(key, "隐私字段（${spec.label}）：需要在「添加字段」里显式选择才会写入")
                return null
            }
            return spec
        }
    }

    // ---------- 坐标 ----------

    private data class GpsCenter(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val jitter: Boolean,
    )

    private fun fillCoordinates(
        context: Context,
        candidates: Set<TagKey>,
        values: MutableMap<TagKey, TagValue>,
        operations: MutableList<EditOperation>,
    ) {
        val preset = context.preset
        if (candidates.none { it in COORDINATE_KEYS }) return

        val groups = linkedMapOf<GpsCenter, MutableList<Pair<TagKey, Boolean>>>()
        candidates.sortedBy { it.full }.forEach { key ->
            val rule = preset.rule(key) ?: return@forEach
            val isJitter = rule is FieldRule.Jitter
            val isGps = rule is FieldRule.Gps
            if (!isGps && !isJitter) return@forEach
            if (key == GpsEditor.LATITUDE_REF || key == GpsEditor.LONGITUDE_REF) {
                context.skipped += Skip(key, "经纬度 Ref 由坐标一并写入，忽略预设里的独立取值")
                return@forEach
            }
            // 抖动也用在数值字段上（±5%），只有经纬度名字才走坐标通道
            val isLatitude = when {
                key.name.contains("Latitude", ignoreCase = true) -> true
                key.name.contains("Longitude", ignoreCase = true) -> false
                else -> {
                    if (isJitter) return@forEach
                    context.skipped += Skip(key, "gps 模式只支持经纬度字段")
                    return@forEach
                }
            }
            val center = when (rule) {
                is FieldRule.Gps -> GpsCenter(rule.latitude, rule.longitude, rule.radiusMeters, jitter = false)
                is FieldRule.Jitter -> GpsCenter(
                    latitude = Double.NaN,
                    longitude = Double.NaN,
                    radiusMeters = rule.radiusMeters ?: GpsEditor.DEFAULT_JITTER_METERS,
                    jitter = true,
                )
                else -> return@forEach
            }
            groups.getOrPut(center) { mutableListOf() } += key to isLatitude
        }

        for ((center, members) in groups) {
            val memberKeys = members.map { it.first }
            // 只填空缺时：源里只要有一个方向的坐标，就整组不动（SetGps 会连另一个方向一起重写）
            if (context.onlyMissing && memberKeys.any { context.base[it] != null }) {
                context.kept += memberKeys
                continue
            }
            val writable = members.filter { context.writableSpec(it.first) != null }
            if (writable.isEmpty()) continue

            val point = if (center.jitter) {
                val origin = GpsEditor.position(context.base)
                if (origin == null) {
                    writable.forEach { context.skipped += Skip(it.first, "源文件没有坐标，无法就近抖动") }
                    continue
                }
                GpsEditor.sampleNear(origin.latitude, origin.longitude, center.radiusMeters, random)
            } else {
                GpsEditor.sampleNear(center.latitude, center.longitude, center.radiusMeters, random)
            }

            operations += EditOperation.SetGps(point.first, point.second, altitudeFor(context))
            // EXIF 坐标交给 GpsEditor；其它命名空间的坐标副本（XMP）在这里落值
            writable.filter { it.first.namespace != GpsEditor.LATITUDE.namespace }.forEach { (key, isLatitude) ->
                val spec = FieldCatalog.spec(key) ?: return@forEach
                val component = if (isLatitude) point.first else point.second
                PresetValues.number(component, component.toString(), spec)?.let { values[key] = it }
            }
        }
    }

    /** 预设若给 `GPSAltitude` 定规则，采样后交给 `GpsEditor` 一起写（含 `GPSAltitudeRef`）。 */
    private fun altitudeFor(context: Context): Double? {
        if (!context.preset.fields.containsKey(GpsEditor.ALTITUDE)) return null
        if (context.onlyMissing && context.base[GpsEditor.ALTITUDE] != null) {
            context.kept += GpsEditor.ALTITUDE
            return null
        }
        val spec = FieldCatalog.spec(GpsEditor.ALTITUDE)
        val rule = context.preset.rule(GpsEditor.ALTITUDE) ?: return null
        val value = sample(rule, GpsEditor.ALTITUDE, spec, context) ?: return null
        return numericOf(value)
    }

    /** 由坐标 / 海拔派生、不能单独填的键。 */
    private fun derivedKeys(preset: Preset, candidates: Set<TagKey>): Set<TagKey> = buildSet {
        add(GpsEditor.LATITUDE_REF)
        add(GpsEditor.LONGITUDE_REF)
        add(GpsEditor.ALTITUDE_REF)
        val hasCoordinateRule = candidates.any { key ->
            when (val rule = preset.rule(key)) {
                is FieldRule.Gps -> true
                is FieldRule.Jitter -> key.name.contains("Latitude", ignoreCase = true) ||
                    key.name.contains("Longitude", ignoreCase = true)
                else -> false
            }
        }
        if (hasCoordinateRule) {
            add(GpsEditor.LATITUDE)
            add(GpsEditor.LONGITUDE)
        }
    }

    // ---------- 抽样 ----------

    private fun sample(
        rule: FieldRule,
        key: TagKey,
        spec: FieldSpec?,
        context: Context,
    ): TagValue? = when (rule) {
        is FieldRule.Fixed -> PresetValues.toTagValue(rule.value, spec)
        is FieldRule.Pool -> PresetValues.toTagValue(weighted(rule.candidates), spec)
        is FieldRule.Range -> {
            val raw = rule.min + random.nextDouble() * (rule.max - rule.min)
            PresetValues.number(roundTo(raw, rule.precision), format(roundTo(raw, rule.precision)), spec)
        }
        is FieldRule.DateTimeRule -> PresetValues.moment(sampleMoment(rule), spec)
        is FieldRule.Gps -> {
            context.skipped += Skip(key, "坐标字段需要成对处理，已跳过单点取值")
            null
        }
        is FieldRule.FromSource -> {
            val value = context.base[rule.sourceKey]
            if (value == null) {
                context.skipped += Skip(key, "源文件里没有 ${rule.sourceKey.full}，无法沿用")
            }
            value
        }
        is FieldRule.Jitter -> {
            val current = numericOf(context.base[key])
            if (current == null) {
                context.skipped += Skip(key, "源文件里没有可抖动的数值")
                null
            } else {
                val factor = 1.0 + (random.nextDouble() * 2 - 1) * rule.percent
                val jittered = current * factor
                PresetValues.number(jittered, format(jittered), spec)
            }
        }
    }

    /** 加权抽样：阈值法走一遍累计权重，浮点误差由末尾兜底。 */
    private fun weighted(entries: List<PoolEntry>): PresetValue {
        val total = entries.sumOf { it.weight }
        var threshold = random.nextDouble() * total
        entries.forEach { entry ->
            threshold -= entry.weight
            if (threshold < 0.0) return entry.value
        }
        return entries.last().value
    }

    private fun weightedIndex(weights: List<Double>): Int {
        val total = weights.sum()
        var threshold = random.nextDouble() * total
        weights.forEachIndexed { index, weight ->
            threshold -= weight
            if (threshold < 0.0) return index
        }
        return weights.lastIndex
    }

    private fun sampleMoment(rule: FieldRule.DateTimeRule): LocalDateTime {
        val startEpoch = rule.start.toEpochSecond(ZoneOffset.UTC)
        val endEpoch = rule.end.toEpochSecond(ZoneOffset.UTC)
        val span = (endEpoch - startEpoch).coerceAtLeast(1L)
        var moment = LocalDateTime.ofEpochSecond(
            startEpoch + (random.nextDouble() * span).toLong(),
            0,
            ZoneOffset.UTC,
        )
        rule.hourWeights?.let { moment = moment.withHour(weightedIndex(it)) }
        rule.minuteWeights?.let { moment = moment.withMinute(weightedIndex(it)) }
        // 换过小时/分钟后可能越过边界，按天推回来（确定性，可复现）
        return when {
            moment.isBefore(rule.start) -> moment.plusDays(1)
            !moment.isBefore(rule.end) -> moment.minusDays(1)
            else -> moment
        }
    }

    // ---------- 约束 ----------

    private fun applyConstraint(
        constraint: PresetConstraint,
        context: Context,
        candidates: Set<TagKey>,
        values: MutableMap<TagKey, TagValue>,
    ) {
        val keys = constraintKeys(constraint)
        when (constraint) {
            is PresetConstraint.Clamp -> keys.forEach { key ->
                if (key !in candidates || key in context.kept) return@forEach
                val value = values[key] ?: return@forEach
                val range = context.preset.rule(key) as? FieldRule.Range ?: return@forEach
                val number = numericOf(value) ?: return@forEach
                val clamped = number.coerceIn(range.min, range.max)
                if (clamped == number) return@forEach
                val rounded = roundTo(clamped, range.precision)
                PresetValues.number(rounded, format(rounded), FieldCatalog.spec(key))?.let { values[key] = it }
            }

            is PresetConstraint.Requires -> {
                val present = keys.filter { values.containsKey(it) || context.base[it] != null }
                if (present.isEmpty() || present.size == keys.size) return
                val anchor = present.sortedBy { it.full }.first()
                keys.filter { it !in present }.sortedBy { it.full }.forEach { key ->
                    // 约束优先于「只填我勾的字段」：要求成组出现，就得把同伴补上
                    val rule = context.preset.rule(key)
                    if (rule == null) {
                        context.skipped += Skip(key, "与 ${anchor.full} 成组出现，但预设没有为它定义规则")
                        return@forEach
                    }
                    val spec = context.writableSpec(key) ?: return@forEach
                    sample(rule, key, spec, context)?.let { values[key] = it }
                        ?: run { context.skipped += Skip(key, "成组补齐 ${anchor.full} 时拿不到取值") }
                }
            }

            is PresetConstraint.Mutex -> {
                val present = keys.filter { values.containsKey(it) || context.base[it] != null }
                if (present.size <= 1) return
                val keeper = present.sortedBy { it.full }.first()
                present.filter { it != keeper && values.containsKey(it) }.sortedBy { it.full }.forEach { key ->
                    values.remove(key)
                    context.skipped += Skip(key, "与 ${keeper.full} 互斥，已移除本次填充的值")
                }
            }

            is PresetConstraint.DatetimeOrder -> {
                var previous = momentOf(values[keys.first()] ?: context.base[keys.first()])
                keys.drop(1).forEach { key ->
                    val current = momentOf(values[key] ?: context.base[key]) ?: return@forEach
                    val floor = previous ?: run { previous = current; return@forEach }
                    if (!current.isBefore(floor)) {
                        previous = current
                        return@forEach
                    }
                    if (key in context.kept) return@forEach
                    val spec = FieldCatalog.spec(key)
                    val rule = context.preset.rule(key)
                    var candidate: LocalDateTime? = null
                    if (rule is FieldRule.DateTimeRule) {
                        var attempt = 0
                        while (attempt < MAX_CONSTRAINT_ROUNDS && candidate == null) {
                            val sampled = sampleMoment(rule)
                            if (!sampled.isBefore(floor)) candidate = sampled
                            attempt++
                        }
                    }
                    val resolved = candidate ?: floor.plusSeconds(1)
                    PresetValues.moment(resolved, spec)?.let { values[key] = it }
                    previous = resolved
                }
            }
        }
    }

    private fun constraintKeys(constraint: PresetConstraint): List<TagKey> = when (constraint) {
        is PresetConstraint.Clamp -> constraint.keys
        is PresetConstraint.DatetimeOrder -> constraint.keys
        is PresetConstraint.Mutex -> constraint.keys
        is PresetConstraint.Requires -> constraint.keys
    }

    // ---------- 小工具 ----------

    private fun numericOf(value: TagValue?): Double? = when (value) {
        is TagValue.IntValue -> value.value.toDouble()
        is TagValue.DecimalValue -> value.value
        is TagValue.RationalValue -> value.value.asDouble
        is TagValue.IntList -> value.values.firstOrNull()?.toDouble()
        is TagValue.DecimalList -> value.values.firstOrNull()
        is TagValue.RationalList -> value.values.firstOrNull()?.asDouble
        else -> null
    }

    private fun momentOf(value: TagValue?): LocalDateTime? = when (value) {
        is TagValue.Timestamp -> value.value
        is TagValue.DateValue -> value.value.atStartOfDay()
        is TagValue.TimeValue -> LocalDateTime.of(1970, 1, 1, value.value.hour, value.value.minute, value.value.second)
        is TagValue.Text -> PresetValues.moment(value.value)
        else -> null
    }

    private fun roundTo(value: Double, precision: Int): Double {
        if (precision <= 0) return value.roundToLong().toDouble()
        val factor = 10.0.pow(precision)
        return (value * factor).roundToLong() / factor
    }

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    companion object {

        /** `datetimeOrder` 约束重采上限（docs/03 §4.3：最多 8 轮）。 */
        const val MAX_CONSTRAINT_ROUNDS: Int = 8

        /** 走坐标通道的键：EXIF 经纬度 + 各命名空间的坐标副本。 */
        private val COORDINATE_KEYS: Set<TagKey> = setOf(GpsEditor.LATITUDE, GpsEditor.LONGITUDE)
    }
}
