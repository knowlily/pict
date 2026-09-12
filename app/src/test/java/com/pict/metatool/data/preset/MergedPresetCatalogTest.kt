package com.pict.metatool.data.preset

import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.preset.FieldRule
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetTestSupport
import com.pict.metatool.domain.preset.PresetValue
import com.pict.metatool.domain.preset.UserFieldInput
import com.pict.metatool.domain.preset.UserPresetInput
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 内置预设 + 用户自建预设合起来的目录（T3.x）。
 *
 * 界面上的「四栏」必须同时看到两份：安装包里那 20 来份，加上用户自己加的。
 * 这里钉住合并顺序、id 分配与缓存刷新——缓存忘了刷新，用户会看到
 * 「刚保存的预设列表里没有」，那是最气人的一种 bug。
 */
class MergedPresetCatalogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val make = TagKey.of("EXIF:Make")

    private val builtinDevice = Preset(
        id = "device.iphone-16-pro",
        kind = PresetKind.DEVICE,
        name = "iPhone 16 Pro",
        fields = mapOf(make to FieldRule.Fixed(PresetValue.Text("Apple"))),
    )
    private val builtinLocation = Preset(
        id = "location.beijing",
        kind = PresetKind.LOCATION,
        name = "北京",
        fields = mapOf(TagKey.of("GPS:GPSLatitude") to FieldRule.Fixed(PresetValue.Text("31.23"))),
    )

    private fun store(dir: File = File(folder.root, UserPresetStore.DIR_NAME)) = UserPresetStore(dir)

    private fun catalog(dir: File = File(folder.root, UserPresetStore.DIR_NAME)) = MergedPresetCatalog(
        builtin = PresetTestSupport.catalog(builtinDevice, builtinLocation),
        store = store(dir),
    )

    private fun input(name: String = "我的机型", value: String = "OnePlus") = UserPresetInput(
        name = name,
        kind = PresetKind.DEVICE,
        rows = listOf(UserFieldInput(make, value)),
    )

    @Test
    fun `内置在前、自建在后，四栏都能查到`() {
        val catalog = catalog()
        catalog.save(input("自建机型"))

        val ids = catalog.all().map { it.id }
        assertEquals(listOf("device.iphone-16-pro", "location.beijing", "user.preset"), ids)
        assertEquals("自建机型", catalog.byId("user.preset")?.name)
    }

    @Test
    fun `id 撞了就补序号，不会覆盖内置预设`() {
        val catalog = catalog()

        val first = catalog.save(input()).getOrNull()!!
        val second = catalog.save(input()).getOrNull()!!

        assertEquals("user.preset", first.id)
        assertEquals("user.preset-2", second.id)
        assertEquals("内置那份还在", "iPhone 16 Pro", catalog.byId("device.iphone-16-pro")?.name)
        assertEquals(2, catalog.all().count { it.origin == com.pict.metatool.domain.preset.PresetOrigin.USER })
    }

    @Test
    fun `存完立刻查得到（缓存有刷新）`() {
        val catalog = catalog()
        assertEquals(2, catalog.all().size)

        val saved = catalog.save(input("马上要用", "vivo")).getOrNull()!!

        assertEquals("马上要用", catalog.byId(saved.id)?.name)
        assertEquals(3, catalog.all().size)
    }

    @Test
    fun `只删得掉自己加的那份，内置的不给动`() {
        val catalog = catalog()
        val saved = catalog.save(input()).getOrNull()!!

        assertFalse("内置预设不能删", catalog.delete("device.iphone-16-pro"))
        assertTrue(catalog.isUserPreset(saved.id))
        assertFalse(catalog.isUserPreset("device.iphone-16-pro"))

        assertTrue(catalog.delete(saved.id))
        assertNull(catalog.byId(saved.id))
        assertEquals(2, catalog.all().size)
    }

    @Test
    fun `内容不合规的直接拒绝，不落盘也不进列表`() {
        val catalog = catalog()

        val failed = catalog.save(input(name = ""))

        assertFalse(failed.isSuccess)
        assertEquals(2, catalog.all().size)
        assertFalse(File(File(folder.root, UserPresetStore.DIR_NAME), "user-preset.json").exists())
    }

    @Test
    fun `用户目录里的坏文件只报问题，自建预设仍然能用`() {
        val dir = File(folder.root, UserPresetStore.DIR_NAME)
        store(dir).save(
            Preset(
                id = "user.ok",
                kind = PresetKind.TIME,
                name = "好的那份",
                fields = mapOf(make to FieldRule.Fixed(PresetValue.Text("x"))),
                origin = com.pict.metatool.domain.preset.PresetOrigin.USER,
            ),
        ).getOrNull()!!
        File(dir, "user-broken.json").writeText("{ 坏掉的自建预设 ")

        val catalog = catalog(dir)

        assertEquals(3, catalog.all().size)
        assertEquals(1, catalog.userIssues.size)
        assertTrue(catalog.userIssues.single().message.isNotBlank())
    }
}
