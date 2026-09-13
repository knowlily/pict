package com.pict.metatool.ui.preset

import com.pict.metatool.data.preset.MergedPresetCatalog
import com.pict.metatool.data.preset.UserPresetStore
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.preset.FieldMode
import com.pict.metatool.domain.preset.FieldRule
import com.pict.metatool.domain.preset.PoolEntry
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetConstraint
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetTestSupport
import com.pict.metatool.domain.preset.PresetValue
import com.pict.metatool.domain.preset.UserFieldInput
import com.pict.metatool.domain.preset.UserPresetInput
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 预设管理页（设置 → 预设管理）。
 *
 * 这一页只做三件事：把「内置 + 自建」都摆出来、点开能看清楚一份预设写了什么、
 * 自建的那几份能删。钉住的是：四栏分组顺序、展开出来的字段清单、删的边界
 * （内置删不动，自建删得掉而且要立刻从列表里消失）。
 */
class PresetManageViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")

    private val builtinDevice = Preset(
        id = "device.iphone-16-pro",
        kind = PresetKind.DEVICE,
        name = "iPhone 16 Pro",
        description = "苹果最近几代里的主力机型。",
        tags = listOf("apple", "手机"),
        fields = mapOf(
            make to FieldRule.Fixed(PresetValue.Text("Apple")),
            model to FieldRule.Pool(listOf(PoolEntry(PresetValue.Text("iPhone 16 Pro")))),
        ),
        constraints = listOf(PresetConstraint.Requires(listOf(make, model))),
    )

    private val builtinLocation = Preset(
        id = "location.beijing",
        kind = PresetKind.LOCATION,
        name = "北京",
        fields = mapOf(TagKey.of("GPS:GPSLatitude") to FieldRule.Gps(39.9, 116.4, 500.0)),
    )

    private fun dir(): File = File(folder.root, UserPresetStore.DIR_NAME)

    private fun catalog(): MergedPresetCatalog = MergedPresetCatalog(
        builtin = PresetTestSupport.catalog(builtinDevice, builtinLocation),
        store = UserPresetStore(dir()),
    )

    private fun viewModel() = PresetManageViewModel(catalog())

    private fun input(name: String = "我的机型", value: String = "OnePlus") = UserPresetInput(
        name = name,
        kind = PresetKind.DEVICE,
        rows = listOf(UserFieldInput(make, value)),
    )

    @Test
    fun `四栏分组：自建排在内置前面，计数按来源分开`() {
        val catalog = catalog()
        catalog.save(input("自建机型"))

        val state = PresetManageViewModel(catalog).state.value

        assertEquals(2, state.builtinCount)
        assertEquals(1, state.userCount)
        assertEquals(listOf(PresetKind.DEVICE, PresetKind.LOCATION), state.groups.map { it.kind })

        val device = state.groups.first().presets
        assertEquals(listOf("user.preset", "device.iphone-16-pro"), device.map { it.id })
    }

    @Test
    fun `展开一份预设：字段按字段目录顺序排，中文名和规则摘要都在`() {
        val viewModel = viewModel()

        viewModel.toggle(builtinDevice)

        val detail = viewModel.state.value.expanded
        assertNotNull("点一下就该展开", detail)
        assertEquals("device.iphone-16-pro", detail!!.id)
        assertEquals("字段清单跟着 FieldCatalog 的顺序", listOf(make, model), detail.fields.map { it.key })
        assertEquals(FieldCatalog.spec(make)?.label, detail.fields.first().label)
        assertTrue("字段中文名不能空", detail.fields.all { it.label.isNotBlank() })
        assertEquals(
            "${FieldMode.FIXED.label}：Apple",
            detail.fields.single { it.key == make }.rule,
        )
        assertEquals(
            "${FieldMode.POOL.label}（1 项）",
            detail.fields.single { it.key == model }.rule,
        )
        assertEquals("成组出现：EXIF:Make、EXIF:Model", detail.constraints.single())
    }

    @Test
    fun `同一份再点一下就收起来`() {
        val viewModel = viewModel()

        viewModel.toggle(builtinDevice)
        assertNotNull(viewModel.state.value.expanded)

        viewModel.toggle(builtinDevice)
        assertNull(viewModel.state.value.expanded)
    }

    @Test
    fun `删的边界：内置连确认框都不弹，自建的弹了能取消`() {
        val catalog = catalog()
        catalog.save(input("自建机型"))
        val user = requireNotNull(catalog.byId("user.preset"))
        val viewModel = PresetManageViewModel(catalog)

        viewModel.askDelete(builtinDevice)
        assertNull("内置删不掉，连问都不问", viewModel.state.value.pendingDelete)

        viewModel.askDelete(user)
        assertEquals(user.id, viewModel.state.value.pendingDelete?.id)

        viewModel.dismissDelete()
        assertNull(viewModel.state.value.pendingDelete)
        assertTrue("取消不该动文件", catalog.byId(user.id) != null)
    }

    @Test
    fun `删掉自建那份：列表立刻少一份，回执带名字`() {
        val catalog = catalog()
        catalog.save(input("临时机型"))
        val user = requireNotNull(catalog.byId("user.preset"))
        val viewModel = PresetManageViewModel(catalog)

        viewModel.askDelete(user)
        viewModel.confirmDelete()

        val state = viewModel.state.value
        assertEquals(PresetManageEvent.Deleted("临时机型"), state.event)
        assertNull(state.pendingDelete)
        assertEquals(0, state.userCount)
        assertNull("删完不该还在列表里", catalog.byId(user.id))
        assertTrue("内置那份不受影响", catalog.byId("device.iphone-16-pro") != null)
    }

    @Test
    fun `文件已经不在了也算删成功（幂等），列表照样刷新`() {
        val catalog = catalog()
        catalog.save(input("手滑删了文件"))
        val user = requireNotNull(catalog.byId("user.preset"))
        val viewModel = PresetManageViewModel(catalog)

        viewModel.askDelete(user)
        assertTrue("模拟用户在文件管理器里删掉", File(dir(), "user-preset.json").delete())

        viewModel.confirmDelete()

        val state = viewModel.state.value
        assertEquals(PresetManageEvent.Deleted("手滑删了文件"), state.event)
        assertEquals("列表跟着刷新，不该留个幽灵", 0, state.userCount)
    }

    @Test
    fun `真删不动的时候：如实报，不假装成功`() {
        val catalog = catalog()
        catalog.save(input("删不掉的那份"))
        val user = requireNotNull(catalog.byId("user.preset"))
        val viewModel = PresetManageViewModel(catalog)

        viewModel.askDelete(user)
        // 把文件换成一个非空目录：删不动的典型样子（被占用、没权限同理）。
        val target = File(dir(), "user-preset.json")
        assertTrue(target.delete())
        assertTrue(target.mkdirs())
        File(target, "占位.txt").writeText("删不动")

        viewModel.confirmDelete()

        assertEquals(PresetManageEvent.DeleteFailed("删不掉的那份"), viewModel.state.value.event)
    }

    @Test
    fun `回执只弹一次`() {
        val catalog = catalog()
        catalog.save(input())
        val user = requireNotNull(catalog.byId("user.preset"))
        val viewModel = PresetManageViewModel(catalog)

        viewModel.askDelete(user)
        viewModel.confirmDelete()
        assertNotNull(viewModel.state.value.event)

        viewModel.consumeEvent()
        assertNull(viewModel.state.value.event)
    }

    @Test
    fun `重读目录：手工塞进来的自建预设也能冒出来`() {
        val catalog = catalog()
        val viewModel = PresetManageViewModel(catalog)
        assertEquals(0, viewModel.state.value.userCount)

        UserPresetStore(dir()).save(
            Preset(
                id = "user.hand-made",
                kind = PresetKind.TIME,
                name = "手写的",
                fields = mapOf(make to FieldRule.Fixed(PresetValue.Text("x"))),
                origin = PresetOrigin.USER,
            ),
        ).getOrNull()

        viewModel.reload()

        val state = viewModel.state.value
        assertEquals(1, state.userCount)
        assertEquals(
            listOf(PresetKind.DEVICE, PresetKind.LOCATION, PresetKind.TIME),
            state.groups.map { it.kind },
        )
        assertEquals("user.hand-made", state.groups.last().presets.single().id)
    }

    @Test
    fun `用户预设坏了只报问题，内置的照样列出来`() {
        val catalog = catalog()
        dir().mkdirs()
        File(dir(), "user-broken.json").writeText("{ 坏掉的自建预设 ")

        val state = PresetManageViewModel(catalog).state.value

        assertEquals(2, state.builtinCount)
        assertEquals(1, state.userIssues.size)
        assertTrue(state.builtinIssues.isEmpty())
    }

    @Test
    fun `规则摘要：每种模式都说人话`() {
        assertEquals(
            "${FieldMode.RANGE.label} 1~2.8",
            PresetManageViewModel.ruleSummary(FieldRule.Range(1.0, 2.8)),
        )
        assertEquals(
            "${FieldMode.DATETIME.label} 2025-01-01~2025-12-31",
            PresetManageViewModel.ruleSummary(
                FieldRule.DateTimeRule(
                    LocalDateTime.of(2025, 1, 1, 0, 0),
                    LocalDateTime.of(2025, 12, 31, 0, 0),
                ),
            ),
        )
        assertEquals(
            "${FieldMode.GPS.label}（半径 500 米）",
            PresetManageViewModel.ruleSummary(FieldRule.Gps(39.9, 116.4, 500.0)),
        )
        assertEquals(
            "${FieldMode.FROM_SOURCE.label}（取自 EXIF:Make）",
            PresetManageViewModel.ruleSummary(FieldRule.FromSource(make)),
        )
        assertEquals(
            "${FieldMode.JITTER.label}（±25%）",
            PresetManageViewModel.ruleSummary(FieldRule.Jitter(percent = 0.25)),
        )
        assertEquals(
            "${FieldMode.JITTER.label}（半径 300 米）",
            PresetManageViewModel.ruleSummary(FieldRule.Jitter(radiusMeters = 300.0)),
        )
        assertEquals(
            "${FieldMode.FIXED.label}：空",
            PresetManageViewModel.ruleSummary(FieldRule.Fixed(PresetValue.Null)),
        )
    }

    @Test
    fun `约束摘要：四种约束各有说法`() {
        assertEquals(
            "时间先后：EXIF:DateTimeOriginal、GPS:GPSLatitude",
            PresetManageViewModel.constraintSummary(
                PresetConstraint.DatetimeOrder(listOf(TagKey.of("EXIF:DateTimeOriginal"), TagKey.of("GPS:GPSLatitude"))),
            ),
        )
        assertEquals(
            "互斥：EXIF:Make",
            PresetManageViewModel.constraintSummary(PresetConstraint.Mutex(listOf(make))),
        )
        assertEquals(
            "随机型收敛：EXIF:Make",
            PresetManageViewModel.constraintSummary(PresetConstraint.Clamp(listOf(make))),
        )
    }

    @Test
    fun `空目录也不崩：给界面一份空分组`() {
        val viewModel = PresetManageViewModel(
            MergedPresetCatalog(builtin = PresetTestSupport.catalog(), store = UserPresetStore(dir())),
        )

        val state = viewModel.state.value
        assertTrue(state.groups.isEmpty())
        assertEquals(0, state.builtinCount)
        assertEquals(0, state.userCount)
        assertFalse(state.groups.isNotEmpty())
    }
}
