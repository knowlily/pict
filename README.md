# Pict · 图片元数据工坊

Android 端**批量图片元数据编辑 + 格式转换**工具。全程本地处理，不申请网络权限。

> 仓库路径：`D:\githubs\pict`
> 文档版本：v1.0（2026-09-09）
> 当前阶段：**P2 收口 + P3 预设/导出 + 设置页 + 任务页骨架** — P0 骨架、P1 读取链路、P2 编辑引擎（T2.1–T2.13：操作折叠、写通道、时间偏移、GPS 编辑、清空分组、单文件编辑 UI、备份回滚、导出副本）与 P3 的 20 个内置预设、随机填充均已提交；设置页 6 项真正接线（深/浅色主题、图库列数、导出后缀、套用预设覆盖、默认种子、导出后校验），底栏样式（悬浮＝液态玻璃胶囊，内容从底下穿过／贴底）与底栏入口可自由选择（图片／任务可关，设置固定保留）；任务页「进行中／历史记录」是写明将来能力的空态骨架。单测 **555/555 通过**（47 个 XML），金标准 **17/17 通过**（`tools/verify-with-exiftool.sh`，111 条复核断言）；R-19 已收口（读侧按**字节层 IFD 目录**判定，不再凭空补标签，写侧也不再把它们落盘）
>
> 协作约定与踩坑清单见 [AGENTS.md](AGENTS.md)。

---

## 一句话定位

在手机上对**单张或成批**图片进行 EXIF / XMP / IPTC 元数据的查看、编辑、随机填充、清除，并在 JPEG / PNG / WebP / HEIF / BMP 之间完成格式转换。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 导入 | 单张、多张、整目录（SAF 持久授权）、系统相册选择器 |
| 查看 | EXIF / GPS / XMP / IPTC / ICC / 文件属性 / 缩略图，按分组折叠展示 |
| 编辑 | 逐字段编辑、批量同值写入、时间批量偏移、GPS 坐标编辑与随机抖动 |
| 导出 | 把改动写成**副本**（SAF 选目标，建议名自动加 `-edited`），源文件全程只读；不能原地写的格式（HEIF）也能导出 |
| 预设 | 20 个内置设备档案（手机/微单/运动相机/无人机）+ 地域、时间、商品档案，支持自定义预设与 JSON 导入导出 |
| 随机填充 | 从预设池按权重随机取值，支持字段级锁定、随机种子可复现 |
| 清除 | 全部元数据 / 按分组清除（GPS、设备信息、时间、MakerNote、缩略图） |
| 转换 | JPEG / PNG / WebP（有损/无损）/ HEIF / BMP，质量、尺寸缩放、元数据保留策略 |
| 批处理 | 任务队列、并发控制、进度、暂停/取消、失败重试、结果报告导出 |
| 任务页 | 「进行中」与「历史记录」两组（线框 docs/06 §3.8）：现在是写明将来能力的空态骨架，真实队列与记录随批处理（P5 / T5.x）接入 |
| 设置 | 深色/浅色/跟随系统（系统栏图标与窗口底色一并跟随）、图库列数、导出文件名后缀、套用预设是否覆盖、随机填充默认种子、导出后是否校验、底栏样式（悬浮＝液态玻璃胶囊，内容从玻璃底下穿过／贴底）与底栏入口选择（图片／任务可关，设置固定保留）——每一项都落到实际行为，不做摆设开关 |
| 安全 | 默认另存不覆盖、写后校验、可选备份、变更预览（dry-run） |

## 技术栈（拟定）

Kotlin · Jetpack Compose Material 3 · Navigation Compose · Hilt · Coroutines/Flow · WorkManager · Room（自定义预设）
元数据：AndroidX ExifInterface 1.4.x · metadata-extractor · Adobe XMPCore · Apache Commons Imaging
编解码：ImageDecoder / Bitmap.compress / AndroidX HeifWriter / 自研 BMP 编码器

详见 [docs/02-技术架构设计.md](docs/02-技术架构设计.md)。

## 文档索引

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [docs/00-项目总览与范围.md](docs/00-项目总览与范围.md) | 背景、目标用户、范围边界、约束、成功标准、待确认决策 | ✅ |
| [docs/01-需求规格说明书.md](docs/01-需求规格说明书.md) | 用户故事、FR/NFR 编号需求与验收标准、状态机、错误码 | ✅ |
| [docs/02-技术架构设计.md](docs/02-技术架构设计.md) | 分层架构、包结构、依赖、数据模型、线程与内存模型 | ✅ |
| [docs/03-元数据模型与预设规范.md](docs/03-元数据模型与预设规范.md) | TagKey 模型、字段白名单、预设 JSON Schema、随机算法 | ✅ |
| [docs/04-格式转换矩阵与实现路径.md](docs/04-格式转换矩阵与实现路径.md) | 各格式读写能力矩阵、编解码路径、降级策略 | ✅ |
| [docs/05-存储权限与文件访问方案.md](docs/05-存储权限与文件访问方案.md) | SAF / MediaStore / scoped storage / 写回策略 | ✅ |
| [docs/06-UI-UX设计说明.md](docs/06-UI-UX设计说明.md) | 页面清单、线框、交互状态、文案与 i18n | ✅ |
| [docs/07-实施计划与任务拆解.md](docs/07-实施计划与任务拆解.md) | P0–P6 阶段、70+ 个可执行任务（文件路径/验收/命令） | ✅ |
| [docs/08-测试与验收计划.md](docs/08-测试与验收计划.md) | 单测/金标准/仪器测试、设备矩阵、手工验收清单 | ✅ |
| [docs/09-风险清单与决策记录.md](docs/09-风险清单与决策记录.md) | 风险登记册 + ADR 架构决策记录 | ✅ |
| [docs/10-构建发布与合规.md](docs/10-构建发布与合规.md) | 工具链、签名、CI、Play 合规、开源许可 | ✅ |
| [docs/附录A-元数据字段全集.md](docs/附录A-元数据字段全集.md) | EXIF/GPS/XMP/IPTC 字段表与可编辑性标注 | ✅ |
| [presets/](presets/) | 20 个内置预设 JSON（12 设备 / 4 地域 / 3 时间 / 1 商品档案）+ Schema | ✅ |

## 快速开始（开发环境）

当前机器状态（已核实）：

| 项 | 状态 |
| --- | --- |
| JDK 17 (Temurin) | ✅ 已安装 |
| adb / platform-tools 37.0.1 | ✅ `D:\Android\Sdk\platform-tools\adb.exe` |
| Android SDK | ✅ `D:\Android\Sdk`（platforms;android-36、build-tools;36.0.0） |
| Android Studio | ❌ 未安装（命令行 + Gradle 构建；本机有 IntelliJ IDEA 2025.3.3 可选） |
| Gradle | ✅ wrapper 8.14.3（发行包 `D:\Android\gradle-8.14.3`，已预置进 wrapper 缓存） |
| `GRADLE_USER_HOME` | ⚠️ 必须设为 `D:\gradle-home`（中文用户名会导致测试 worker 找不到主类，见 [docs/10 §1.1](docs/10-构建发布与合规.md)） |
| D 盘可用空间 | 76 GB（SDK 约需 6–10 GB） |

安装与首次构建步骤见 [docs/10-构建发布与合规.md](docs/10-构建发布与合规.md) 第 2 节。

## 目录结构（规划）

```
pict/
├─ app/                        # 单模块，package-by-feature
│  └─ src/
│     ├─ main/java/com/pict/metatool/
│     │  ├─ app/               # Application / MainActivity / navigation
│     │  ├─ data/              # source, metadata, codec, preset, repo
│     │  ├─ domain/            # model, usecase, plan
│     │  ├─ ui/                # library, detail, preset, convert, batch, settings, theme
│     │  └─ di/
│     ├─ test/                 # 单元测试
│     └─ androidTest/          # 仪器测试
├─ docs/                       # 本文档集
├─ presets/                    # 预设 JSON（构建时打包进 assets）
├─ samples/                    # 测试样本图（小体积、授权明确）
└─ tools/                      # 校验脚本（exiftool 比对、对齐检查）
```

## 关键约束（先记住这三条）

1. **不做 MANAGE_EXTERNAL_STORAGE**（Google Play 政策限制），文件访问一律走 SAF / MediaStore。
2. **默认不覆盖原文件**，输出到用户指定目录；覆盖必须二次确认且可备份。
3. **不申请 `INTERNET` 权限**（V1），"离线"是核心卖点也是隐私承诺。

## 下一步

1. 编辑链路的真机验收已在 MuMu（Android 15）跑通一轮：导入 → 详情 →「编辑元数据」→ 改一项 → 应用 →
   原文件落地，缩略图逐字节不变（过程与证据见 [docs/08](docs/08-测试与验收计划.md) §10）。
2. 已知风险（[docs/09](docs/09-风险清单与决策记录.md)）：R-16（缩略图）已修并加了常驻断言；R-17（`GPSVersionID`）
   是上游 commons-imaging 读不到 tag 0，只在 TIFF 通道触发，V1 记作已知限制；R-18（金标准跑生产写通道）已收口，
   两条样本上的写通道偏差逐条钉在 `prodDeviations`／`prodGaps` 里，行为一变就红；R-19 已收口（2026-09-11）：判据从 `getAttributeRange` 的负偏移换成字节层 IFD 目录
   （`IfdTagIndex` + 机器导出的 `ExifTagNumbers`），读侧不再给源文件没有的字段补默认值，写侧再把库补出来的
   属性清掉后才落盘；证人是 `ExifReadHonestyTest`／`ExifWriteHonestyTest`，尚未覆盖 HEIF（只读容器、无可信样本）；
   R-20（深浅色与系统栏图标不匹配）已修；R-21 是「底栏入口可关掉」带来的导航死路，已按「至少留一项 + 必需项不在设置页列出 +
   关掉当前页自动换页」处理；R-22 是液态玻璃的背板模糊需要 `RenderEffect`（API 31+），更低版本降级为
   纯半透明。
3. 每个任务完成后提交一次 git；协作约定与踩坑见 [AGENTS.md](AGENTS.md)。

## 金标准怎么跑

```bash
tools/verify-with-exiftool.sh          # 需要 exiftool（13.x）；找不到会明说并跳过
tools/verify-with-exiftool.sh --allow-missing   # CI 上没装 exiftool 时：跳过但不报错
```

脚本干两件事：跑 `ExiftoolGoldStandardTest`（把编辑计划折叠 → 真写通道落盘 → **由 exiftool 独立读回**，
对目标字段、其余字段、像素、缩略图四项取证），再用 exiftool 把每个用例的断言逐条复核一遍。
产物留在 `app/build/goldstandard/out/`：改后的图、`*.exiftool.json` 转储、`*.changed.txt` 改动清单、`*.gaps.txt` 缺口。
