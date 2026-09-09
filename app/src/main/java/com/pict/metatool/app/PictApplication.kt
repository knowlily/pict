package com.pict.metatool.app

import android.app.Application

/**
 * 应用入口。
 *
 * 职责（P0 仅占位）：
 * - 后续在此初始化 WorkManager 配置、预设仓库、全局异常兜底（docs/07 T0.5、T0.10）。
 * - 不在此处做任何网络相关初始化（ADR-09：V1 不申请 INTERNET 权限）。
 */
class PictApplication : Application()
