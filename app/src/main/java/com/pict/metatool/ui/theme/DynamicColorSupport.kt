package com.pict.metatool.ui.theme

import android.os.Build

/**
 * 动态取色（Material You）在这台机器上能不能用（FR-38）。
 *
 * 系统从 Android 12（API 31）起才按壁纸给调色板：`dynamicLightColorScheme` 在更老的机器上
 * 拿不到东西，只会把品牌色换成一堆灰。设置页里那一行灰不灰、以及 [PictTheme] 的取色分支，
 * 都问这一个函数——同一个判断在两处各写一遍 `SDK_INT >= S`，迟早会写歪一边，
 * 到时候表现为「开关亮着但颜色没变」，最难查。
 *
 * [sdkInt] 之所以是个参数：这条规则能在 JVM 上单测（NFR-09），不必插一台旧手机去量。
 */
fun supportsDynamicColor(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S
