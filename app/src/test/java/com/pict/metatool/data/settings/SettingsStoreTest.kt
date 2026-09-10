package com.pict.metatool.data.settings

import com.pict.metatool.domain.settings.AppSettings
import com.pict.metatool.domain.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置存储的读写语义（docs/06 §3.7）。
 *
 * 界面只跟 [SettingsStore] 打交道，落盘实现（SharedPreferences）只是壳；
 * 「改一项不动其余」「值没变不写盘」这两条在这里锁住。
 */
class SettingsStoreTest {

    @Test
    fun `初始值就是喂进去的那份`() {
        val store = InMemorySettingsStore(AppSettings(exportSuffix = "-副本"))
        assertEquals("-副本", store.settings.value.exportSuffix)
    }

    @Test
    fun `构造时喂进来的值也会被规范化`() {
        val store = InMemorySettingsStore(AppSettings(exportSuffix = " a/b ", gridColumns = 99))
        assertEquals("ab", store.settings.value.exportSuffix)
        assertEquals(4, store.settings.value.gridColumns)
    }

    @Test
    fun `只改想改的那一项，其余不动`() {
        val store = InMemorySettingsStore()
        store.update { it.copy(themeMode = ThemeMode.DARK) }
        assertEquals(ThemeMode.DARK, store.settings.value.themeMode)
        assertEquals("-edited", store.settings.value.exportSuffix)
        assertTrue(store.settings.value.verifyAfterExport)
    }

    @Test
    fun `update 返回改完之后的值`() {
        val store = InMemorySettingsStore()
        val updated = store.update { it.copy(verifyAfterExport = false) }
        assertFalse(updated.verifyAfterExport)
        assertEquals(updated, store.settings.value)
    }

    @Test
    fun `值没变化就不惊动落盘`() {
        val writes = mutableListOf<AppSettings>()
        val store = InMemorySettingsStore(persist = writes::add)
        store.update { it.copy(verifyAfterExport = it.verifyAfterExport) }
        assertEquals(emptyList<AppSettings>(), writes)
        store.update { it.copy(verifyAfterExport = false) }
        assertEquals(1, writes.size)
    }

    @Test
    fun `落盘拿到的是规范化之后的值`() {
        val writes = mutableListOf<AppSettings>()
        val store = InMemorySettingsStore(persist = writes::add)
        store.update { it.copy(exportSuffix = " 坏/名字 ", gridColumns = 1) }
        assertEquals("坏名字", writes.single().exportSuffix)
        assertEquals(2, writes.single().gridColumns)
    }

    @Test
    fun `恢复默认会写回一份默认值并通知落盘`() {
        val writes = mutableListOf<AppSettings>()
        val store = InMemorySettingsStore(AppSettings(themeMode = ThemeMode.DARK), persist = writes::add)
        assertEquals(AppSettings(), store.reset())
        assertEquals(AppSettings(), store.settings.value)
        assertEquals(AppSettings(), writes.single())
    }

    @Test
    fun `订阅者拿到的是最新值`() {
        val store = InMemorySettingsStore()
        store.update { it.copy(gridColumns = 4) }
        assertEquals(4, store.settings.value.gridColumns)
    }
}
