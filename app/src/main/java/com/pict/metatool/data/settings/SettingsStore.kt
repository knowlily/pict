package com.pict.metatool.data.settings

import com.pict.metatool.domain.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置的读写口（docs/01 FR-35）。
 *
 * 界面只认这个接口：拿 [settings] 当前值、用 [update] 提一个变换。
 * 这样设置页、编辑页、图库页读的是同一份值——不会出现「设置改了但编辑页还是老样子」。
 *
 * 只读当前值、不做「先读再写」的两段式：调用方给的是一整个变换函数，
 * 由存储自己在一把锁里完成，避免两处同时改互相覆盖。
 */
interface SettingsStore {

    /** 当前设置；每次变更都会发新值。 */
    val settings: StateFlow<AppSettings>

    /**
     * 按 [transform] 改设置，返回改完之后的值。
     *
     * 变换结果一律过 [AppSettings.normalized]：坏值不生效。
     */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings

    /** 恢复默认值（设置页的「恢复默认设置」）。 */
    fun reset(): AppSettings
}

/**
 * 内存实现：单测与 `@Preview` 用，也是落盘实现的地基。
 *
 * [persist] 在值**真的变了**之后才回调（值没变不写盘、也不多写一次 pref），
 * 由落盘实现接上。所有变更走同一把锁，界面线程与后台线程同时改也不会丢更新。
 */
open class InMemorySettingsStore(
    initial: AppSettings = AppSettings(),
    private val persist: (AppSettings) -> Unit = {},
) : SettingsStore {

    private val _settings = MutableStateFlow(initial.normalized())

    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    override fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value).normalized()
        if (next != _settings.value) {
            _settings.value = next
            persist(next)
        }
        return next
    }

    override fun reset(): AppSettings = update { AppSettings() }
}
