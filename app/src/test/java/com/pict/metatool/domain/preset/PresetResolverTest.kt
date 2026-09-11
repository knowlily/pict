package com.pict.metatool.domain.preset

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.plan.EditPlanExecutor
import com.pict.metatool.domain.plan.GpsEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T3.4：预设类操作 → 原子操作的展开与折叠（`PresetResolver`）。
 *
 * 这里守的是「粘合层」的契约：执行器不认识预设，预设展开后又必须能被执行器原样折叠，
 * 于是断言都落在「展开出来的操作序列 + 折叠后的目标集合」上，而不是中间实现细节。
 */
class PresetResolverTest {

    private val make = PresetTestSupport.key("EXIF:Make")
    private val model = PresetTestSupport.key("EXIF:Model")
    private val artist = PresetTestSupport.key("EXIF:Artist")

    private val device = PresetTestSupport.preset("device.iphone-16-pro")
    private val location = PresetTestSupport.preset("location.shanghai")
    private val catalog = PresetTestSupport.catalog(device, location)

    private fun fold(operations: List<EditOperation>, source: com.pict.metatool.domain.model.MetadataSet) =
        EditPlanExecutor.execute(EditPlan(operations, dryRun = true), source)

    private fun expand(plan: EditPlan, source: com.pict.metatool.domain.model.MetadataSet) =
        PresetResolver.expand(plan, source, catalog).getOrNull()!!

    // ---------- fill ----------

    @Test
    fun `fill 给出目标集合与真正变化的键`() {
        val source = PresetTestSupport.emptySource()
        val fill = PresetResolver.fill(device, source, seed = 1L).getOrNull()!!

        assertNotNull(fill.target[make])
        assertNotNull(fill.target[model])
        assertTrue("变化的键应包含制造商与机型", fill.changedKeys.containsAll(setOf(make, model)))
        assertTrue("填充应有内容", fill.changedKeys.isNotEmpty())
    }

    @Test
    fun `fill 的目标集合与按操作折叠的结果一致`() {
        val source = PresetTestSupport.emptySource()
        val fill = PresetResolver.fill(device, source, seed = 8L).getOrNull()!!
        val expanded = expand(EditPlan(listOf(EditOperation.ApplyPreset(device.id, seed = 8L))), source)
        val folded = fold(expanded.operations, source).getOrNull()!!

        assertEquals(folded.target[make], fill.target[make])
        assertEquals(folded.target[model], fill.target[model])
        assertEquals(folded.target.changedKeys(source), fill.changedKeys)
    }

    @Test
    fun `只填缺失时不覆盖源里已有的机型`() {
        val source = PresetTestSupport.emptySource().with(model, TagValue.Text("我的机器"))
        val fill = PresetResolver.fill(device, source, onlyMissing = true, seed = 1L).getOrNull()!!

        assertEquals(TagValue.Text("我的机器"), fill.target[model])
        assertTrue("被保留的键要点名", model in fill.keptKeys)
        assertFalse("保留的键不算变化", model in fill.changedKeys)
    }

    // ---------- expand ----------

    @Test
    fun `expand 把预设操作展开成原子操作`() {
        val plan = EditPlan(listOf(EditOperation.ApplyPreset(device.id, seed = 1L)))
        val expanded = expand(plan, PresetTestSupport.emptySource())

        assertTrue("展开后不该再有预设类操作", expanded.operations.none {
            it is EditOperation.ApplyPreset || it is EditOperation.RandomFill
        })
        assertTrue("展开结果应全是字段写入", expanded.operations.all { it is EditOperation.SetField })
        assertTrue(expanded.operations.isNotEmpty())
    }

    @Test
    fun `展开时只填缺失看到的是前面操作之后的结果`() {
        val source = PresetTestSupport.emptySource()
        val plan = EditPlan(
            listOf(
                EditOperation.SetField(make, TagValue.Text("自定义厂商")),
                EditOperation.ApplyPreset(device.id, overwriteExisting = false, seed = 1L),
            ),
        )
        val expanded = expand(plan, source)
        val folded = fold(expanded.operations, source).getOrNull()!!

        assertEquals("前面的显式操作优先，预设不该覆盖它", TagValue.Text("自定义厂商"), folded.target[make])
        assertNotEquals(null, folded.target[model])
    }

    @Test
    fun `覆盖模式会把预设值写到已有字段上`() {
        val source = PresetTestSupport.emptySource().with(make, TagValue.Text("自定义厂商"))
        val plan = EditPlan(listOf(EditOperation.ApplyPreset(device.id, overwriteExisting = true, seed = 1L)))
        val folded = fold(expand(plan, source).operations, source).getOrNull()!!

        assertNotEquals(TagValue.Text("自定义厂商"), folded.target[make])
    }

    @Test
    fun `随机填充只动勾选的字段并点名未覆盖的键`() {
        val source = PresetTestSupport.emptySource()
        val plan = EditPlan(
            listOf(
                EditOperation.RandomFill(
                    fields = setOf(model, artist),
                    seed = 3L,
                    presetId = device.id,
                ),
            ),
        )
        val expanded = expand(plan, source)
        val written = expanded.operations.map { (it as EditOperation.SetField).key }.toSet()

        assertTrue("勾选的机型要在写入范围内", written.contains(model))
        // 预设给 Make/Model 配了 requires 约束：成组出现优先于「只填勾选项」，于是 Make 被带上
        assertTrue("requires 约束应把制造商一起带上", written.contains(make))
        assertTrue(
            "不在预设里的键要说明原因",
            expanded.skipped.any { it.key == artist && it.reason.contains("没有为这个字段定义规则") },
        )
    }

    @Test
    fun `随机填充缺少预设时明确失败而不是瞎编值`() {
        val plan = EditPlan(listOf(EditOperation.RandomFill(fields = setOf(model), seed = 1L)))
        val failure = PresetResolver.expand(plan, PresetTestSupport.emptySource(), catalog) as PictResult.Failure

        assertEquals("E-FIELD-INVALID", failure.code)
        assertTrue("原因要指向预设", failure.failure.detail!!.contains("预设"))
    }

    @Test
    fun `预设不存在时明确失败`() {
        val plan = EditPlan(listOf(EditOperation.ApplyPreset("device-not-exist")))
        val failure = PresetResolver.expand(plan, PresetTestSupport.emptySource(), catalog) as PictResult.Failure

        assertTrue(failure.failure.detail!!.contains("预设不存在"))
    }

    @Test
    fun `空目录里任何预设都解析不到`() {
        val plan = EditPlan(listOf(EditOperation.ApplyPreset(device.id)))
        val result = PresetResolver.expand(plan, PresetTestSupport.emptySource(), PresetCatalog.EMPTY)
        assertTrue(result is PictResult.Failure)
    }

    // ---------- 内置预设端到端 ----------

    @Test
    fun `位置预设填出的坐标落在预设圆面内且带 Ref`() {
        val rule = location.rule(GpsEditor.LATITUDE) as FieldRule.Gps
        val source = PresetTestSupport.emptySource()
        val plan = EditPlan(listOf(EditOperation.ApplyPreset(location.id, seed = 2L)))
        val folded = fold(expand(plan, source).operations, source).getOrNull()!!

        val position = GpsEditor.position(folded.target)
        assertNotNull("折叠后应能读出完整坐标", position)
        val distance = GpsEditor.sphericalDistanceMeters(
            rule.latitude,
            rule.longitude,
            position!!.latitude,
            position.longitude,
        )
        assertTrue("超出圆面：$distance m", distance <= rule.radiusMeters + 1.0)

        assertEquals(TagValue.Text("N"), folded.target[GpsEditor.LATITUDE_REF])
        assertEquals(TagValue.Text("E"), folded.target[GpsEditor.LONGITUDE_REF])
    }

    @Test
    fun `位置预设沿用源坐标时只填空缺不会连 Ref 一起改写`() {
        val source = GpsEditor.set(PresetTestSupport.emptySource(), 22.5, 114.0).getOrNull()!!
        val plan = EditPlan(listOf(EditOperation.ApplyPreset(location.id, overwriteExisting = false, seed = 2L)))
        val expanded = expand(plan, source)
        val folded = fold(expanded.operations, source).getOrNull()!!

        val position = GpsEditor.position(folded.target)!!
        assertTrue("只填空缺时坐标不该被改", kotlin.math.abs(position.latitude - 22.5) < 1e-6)
        assertTrue(position.longitude - 114.0 < 1e-6)
        assertTrue("坐标被保留时要汇报", expanded.keptKeys.any { it == GpsEditor.LATITUDE })
    }

    // ---------- applyPlan：计划路径的完整执行 ----------

    @Test
    fun `applyPlan 让计划路径的随机填充真正落地`() {
        // 执行器本身不认识预设（遇到 RandomFill 直接报「尚未实现」），而批量任务在计划里
        // 写下的偏偏就是这类操作——applyPlan 是这条路的唯一入口（T5.2 之前它没有任何调用点）。
        val plan = EditPlan(
            operations = listOf(
                EditOperation.RandomFill(fields = setOf(make, model), seed = 3L, presetId = device.id),
            ),
            dryRun = true,
        )

        val outcome = PresetResolver.applyPlan(plan, PresetTestSupport.emptySource(), catalog).getOrNull()!!

        assertNotNull(outcome.target[make])
        assertNotNull(outcome.target[model])
        assertTrue("随机填充要报出变化", outcome.changedKeys.containsAll(setOf(make, model)))
    }

    @Test
    fun `applyPlan 与 fill 对同一预设同一种子给出相同结果`() {
        val source = PresetTestSupport.emptySource()
        val filled = PresetResolver.fill(device, source, seed = 5L).getOrNull()!!
        val outcome = PresetResolver.applyPlan(
            EditPlan(listOf(EditOperation.ApplyPreset(device.id, seed = 5L))),
            source,
            catalog,
        ).getOrNull()!!

        assertEquals(filled.target[make], outcome.target[make])
        assertEquals(filled.target[model], outcome.target[model])
        assertEquals(filled.changedKeys, outcome.changedKeys)
    }

    @Test
    fun `applyPlan 里字段操作与预设操作按顺序叠加`() {
        val plan = EditPlan(
            listOf(
                EditOperation.SetField(artist, TagValue.Text("先写的人")),
                EditOperation.ApplyPreset(device.id, overwriteExisting = false, seed = 1L),
            ),
        )

        val outcome = PresetResolver.applyPlan(plan, PresetTestSupport.emptySource(), catalog).getOrNull()!!

        assertEquals(TagValue.Text("先写的人"), outcome.target[artist])
        assertNotNull("预设那部分照旧生效", outcome.target[model])
        assertTrue(artist in outcome.changedKeys)
    }

    @Test
    fun `applyPlan 遇到不认识的预设当场失败而不是静默跳过`() {
        val result = PresetResolver.applyPlan(
            EditPlan(listOf(EditOperation.ApplyPreset("device.nope"))),
            PresetTestSupport.emptySource(),
            catalog,
        )

        assertTrue(result is PictResult.Failure)
        assertEquals(PictError.FIELD_INVALID, (result as PictResult.Failure).error)
    }
}
