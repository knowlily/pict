# Pict · 图片元数据工坊

Android 端**批量图片元数据编辑 + 格式转换**工具。全程本地处理，不申请网络权限。

> 仓库路径：`D:\githubs\pict`
> 文档版本：v1.0（2026-09-09）
> 当前阶段：**P2 元数据编辑引擎（进行中）** — P0 骨架、P1 读取链路已落地；P2 已提交 T2.1–T2.8（操作折叠、写通道、时间偏移、GPS 编辑）。单测 **300/300 通过**
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
| 预设 | 内置设备档案（相机/机型）+ 自定义预设，可导入导出 JSON |
| 随机填充 | 从预设池按权重随机取值，支持字段级锁定、随机种子可复现 |
| 清除 | 全部元数据 / 按分组清除（GPS、设备信息、时间、MakerNote、缩略图） |
| 转换 | JPEG / PNG / WebP（有损/无损）/ HEIF / BMP，质量、尺寸缩放、元数据保留策略 |
| 批处理 | 任务队列、并发控制、进度、暂停/取消、失败重试、结果报告导出 |
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
| [presets/](presets/) | 预设元数据 JSON 样例（设备/地域/时间） | ✅ |

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

1. P2 剩余：T2.9 清空分组（`domain/plan/ClearGroups.kt`）、T2.11 备份与回滚（`data/source/BackupManager.kt`）。
2. P2 用户可见链路：单文件编辑 UI（T2.10，字段弹层 / 未保存提示 / diff 预览）。
3. P2 收口：单元测试 + exiftool 金标准脚本（T2.12），跑通后按 [docs/07](docs/07-实施计划与任务拆解.md) 的 Phase 2 退出标准验收。
4. 每个任务完成后提交一次 git；协作约定与踩坑见 [AGENTS.md](AGENTS.md)。
