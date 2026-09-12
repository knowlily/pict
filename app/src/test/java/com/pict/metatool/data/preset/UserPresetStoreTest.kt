package com.pict.metatool.data.preset

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.preset.FieldRule
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自建预设的落盘（T3.x）：`files/presets/user-*.json`。
 *
 * 三条必须成立的规矩：
 * 1. **存得进去就读得出来**——写完立刻用解析器验一遍，验不过不许覆盖旧文件；
 * 2. **坏文件只丢自己**——一个坏了不能连累别的预设；
 * 3. **一个预设一个文件**——删一个不影响另一个，用户还能把文件直接拷进拷出。
 */
class UserPresetStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val make = TagKey.of("EXIF:Make")

    private fun store(dir: File = File(folder.root, UserPresetStore.DIR_NAME)) = UserPresetStore(dir)

    private fun preset(id: String, name: String = "我的机型", make0: String = "OnePlus") = Preset(
        id = id,
        kind = PresetKind.DEVICE,
        name = name,
        fields = mapOf(make to FieldRule.Fixed(PresetValue.Text(make0))),
        origin = PresetOrigin.USER,
    )

    @Test
    fun `存一份再读回来：文件名按约定，字段与来源都对`() {
        val store = store()

        val saved = store.save(preset("user.mine")).getOrNull()!!

        val file = store.fileOf("user.mine")
        assertTrue("文件该落在 ${file.absolutePath}", file.isFile)
        assertEquals("user-mine.json", file.name)
        assertTrue(store.directory.isDirectory)

        val loaded = store.load()
        assertTrue("不该有问题：${loaded.issues}", loaded.issues.isEmpty())
        assertEquals(1, loaded.presets.size)
        assertEquals(saved, loaded.presets.single())
        assertEquals(PresetOrigin.USER, loaded.presets.single().origin)
        assertEquals(PresetValue.Text("OnePlus"), loaded.presets.single().fields[make]?.let { (it as FieldRule.Fixed).value })
    }

    @Test
    fun `重名再存是覆盖同一个文件，不是多出一份`() {
        val store = store()
        store.save(preset("user.mine", name = "旧名字"))

        store.save(preset("user.mine", name = "新名字", make0 = "vivo"))

        val files = store.directory.listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(1, files.size)
        assertFalse("临时文件要收干净", store.directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        val loaded = store.load()
        assertEquals(1, loaded.presets.size)
        assertEquals("新名字", loaded.presets.single().name)
    }

    @Test
    fun `坏文件只进 issues，别的预设照常可用`() {
        val store = store()
        store.save(preset("user.good"))

        File(store.directory, "user-broken.json").writeText("{ 这不是 JSON")
        File(store.directory, "user-also.json").writeText("""{"schemaVersion":1,"id":"BAD ID","kind":"device"}""")
        // 不是 user- 开头的文件根本不该被看见（那是安装包那边的命名空间）
        File(store.directory, "device-iphone-16-pro.json").writeText("{ 坏 ")

        val loaded = store.load()

        assertEquals(listOf("user.good"), loaded.presets.map { it.id })
        assertTrue("两个坏文件都要报出来", loaded.issues.size >= 2)
        assertTrue(
            "每条提示都要指到哪个文件，且不能是空话",
            loaded.issues.all { it.message.isNotBlank() && it.path.startsWith("user-") },
        )
        assertTrue(
            "不是 user- 开头的文件根本不该被看见",
            loaded.issues.none { it.path.contains("device-iphone-16-pro") },
        )
    }

    @Test
    fun `删掉一个不影响另一个，删不存在的也算成功`() {
        val store = store()
        store.save(preset("user.a", name = "甲"))
        store.save(preset("user.b", name = "乙"))

        assertTrue(store.delete("user.a"))
        assertFalse(store.fileOf("user.a").exists())
        assertEquals(listOf("user.b"), store.load().presets.map { it.id })

        assertTrue("文件本来就不在，用户要的结果已经成立", store.delete("user.never"))
    }

    @Test
    fun `内容不合法就拒绝落盘，旧文件原样留着`() {
        val store = store()
        store.save(preset("user.keep", name = "原来的"))

        // id 不合 preset-v1 的命名规则：写出来再读回去会失败，这里必须什么都不写
        val broken = preset("User.Bad", name = "不合规的 id")
        val result = store.save(broken)

        assertFalse(result.isSuccess)
        assertEquals(PictError.FIELD_INVALID, result.failureOrNull()?.error)
        assertEquals("旧文件没被毁", "原来的", store.load().presets.single().name)
        assertFalse(store.fileOf("User.Bad").exists())
    }

    @Test
    fun `ids 只数得出目录里真实存在的预设`() {
        val store = store()
        store.save(preset("user.one"))
        File(store.directory, "user-broken.json").writeText("{ 坏 ")

        assertEquals(setOf("user.one"), store.ids())
    }
}
