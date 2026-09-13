# Pict · 图片元数据工坊

面向 Android 平台的图片元数据批量编辑与格式转换工具。可查看照片中携带的各类元数据、按需修改，
或将整批照片统一刷写为同一套档案。全部处理均在本机完成，应用不申请网络权限。

| 项 | 值 |
| --- | --- |
| 应用 ID | `com.pict.metatool` |
| 当前版本 | 0.1.0（开发中） |
| 系统要求 | Android 8.0（API 26）及以上 |
| 技术栈 | Kotlin · Jetpack Compose（Material 3）· Navigation Compose · Hilt · Coroutines/Flow · WorkManager |
| 当前进度 | P0–P3 基础能力与批量链路（T5.x）已落地 |

## 功能

| 能力 | 说明 |
| --- | --- |
| 导入 | 支持单张、多张、整目录导入与系统相册选择器；目录授权可持久保留。 |
| 查看 | 展示 EXIF、GPS、XMP、IPTC、ICC、文件属性与缩略图，按分组折叠。 |
| 编辑 | 支持单字段编辑、批量写入同一值、时间批量偏移，以及 GPS 坐标编辑与随机抖动。 |
| 预设 | 内置 43 套档案（设备 26 / 地域 10 / 时间 6 / 混合 1），按类别分栏并支持跨栏多选；自建预设可就地新建、编辑与删除。 |
| 随机填充 | 按权重从预设池取值，支持字段锁定与随机种子复现；确认前先生成预览。 |
| 清除 | 可清除全部元数据，或按分组清除（GPS、设备信息、时间、MakerNote、缩略图）。 |
| 转换 | 支持 JPEG、PNG、WebP（有损与无损）、HEIF、BMP，可选质量、尺寸缩放与元数据保留策略。 |
| 批量处理 | 前台任务队列（通知栏可取消），并发 1–8，失败自动重试 2 次（间隔 500 ms / 2 s），显示进度与剩余时间预估，结果报告可导出。 |
| 任务记录 | 分为「进行中」与「历史记录」两组；历史任务可重跑（复制为新任务，不覆盖原有记录）或撤销（依据备份回滚，写入文件前二次确认）。 |
| 设置 | 深浅色与动态取色、页面底色、图库列数、导出文件名后缀、套用预设时的覆盖策略、默认随机种子、导出后校验、底栏样式与入口。 |
| 安全策略 | 默认另存为副本而不覆盖原文件；写入后校验；可选备份；提供变更预览（dry-run）。 |

## 设计与安全约束

1. 不使用 `MANAGE_EXTERNAL_STORAGE` 权限（受 Google Play 政策限制），文件访问统一经 SAF 与 MediaStore。
2. 默认不覆盖原文件：改动一律写入副本，覆盖需二次确认并可保留备份。
3. 不申请 `INTERNET` 权限：离线运行既是功能定位，也是隐私承诺。

## 构建与运行

环境要求：

- JDK 17
- Android SDK：`platforms;android-36`、`build-tools;36.0.0`
- Gradle 8.14.3（由仓库内 wrapper 提供，无需另行安装）
- `GRADLE_USER_HOME` 须指向纯 ASCII 路径。用户名含中文时若未设置该项，单元测试 worker 将无法定位主类。

```bash
export GRADLE_USER_HOME=/path/to/ascii/gradle-home
./gradlew :app:assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # 单元测试（纯 JVM，约 1 分钟）
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 可选：安装至设备
```

## 项目结构

```
pict/
├─ app/                        # 单模块，package-by-feature
│  └─ src/
│     ├─ main/java/com/pict/metatool/
│     │  ├─ app/               # Application、MainActivity、导航
│     │  ├─ core/              # 通用工具
│     │  ├─ data/              # source、metadata、codec、preset、repo
│     │  ├─ domain/            # model、plan、preset、job、format、naming、settings
│     │  └─ ui/                # library、detail、edit、preset、batch、job、jobs、report、settings、theme
│     ├─ test/                 # 单元测试（86 个测试类）
│     └─ androidTest/          # 仪器测试
├─ docs/                       # 设计与规范文档（见下表）
├─ presets/                    # 43 个内置预设 JSON，构建时写入 assets
├─ samples/                    # 测试样本图
└─ tools/                      # exiftool 比对与样本生成脚本
```

## 测试与验证

| 手段 | 执行方式 | 说明 |
| --- | --- | --- |
| 单元测试 | `./gradlew test` | 86 个测试类、930 条用例，纯 JVM 运行（无 Robolectric）。导航与回栈等逻辑均抽为可单测的状态契约。 |
| 金标准比对 | `tools/verify-with-exiftool.sh` | 将编辑计划折叠后经真实写入通道落盘，再由 exiftool 独立读回，对目标字段、其余字段、像素与缩略图四项取证。需要 exiftool 13.x；未安装时会明确说明并跳过（CI 可加 `--allow-missing`）。产物位于 `app/build/goldstandard/out/`。 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [00-项目总览](docs/00-项目总览.md) | 项目定位与模块划分 |
| [01-需求规格说明](docs/01-需求规格说明.md) | 功能与非功能需求（FR / NFR） |
| [02-技术架构设计](docs/02-技术架构设计.md) | 分层、数据流与关键技术选型 |
| [03-元数据模型与预设规范](docs/03-元数据模型与预设规范.md) | 元数据字段模型与预设规范 |
| [04-元数据引擎设计](docs/04-元数据引擎设计.md) | 读取、解析与写入通道 |
| [05-批量处理与转换设计](docs/05-批量处理与转换设计.md) | 任务队列、并发与格式转换 |
| [06-UI-UX设计说明](docs/06-UI-UX设计说明.md) | 页面结构与交互说明 |
| [07-实施计划与任务拆解](docs/07-实施计划与任务拆解.md) | 里程碑与任务表 |
| [08-测试与验收计划](docs/08-测试与验收计划.md) | 测试策略与验收清单 |
| [09-风险清单与决策记录](docs/09-风险清单与决策记录.md) | 风险项与架构决策记录 |
| [10-构建发布与合规](docs/10-构建发布与合规.md) | 工具链、签名、发布与合规 |
| [11-开发进展日志](docs/11-开发进展日志.md) | 逐轮改动与验证记录 |
| [AGENTS.md](AGENTS.md) | 协作约定 |

## 许可

[Apache-2.0](LICENSE) © 2026 knowlily
