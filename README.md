# Pict · 图片元数据工坊

Android 端的**批量图片元数据编辑 + 格式转换**工具：看清一张照片里带了什么，改掉它，
或者把一整批照片刷成同一套档案。全程本地处理，**不申请网络权限**。

| 项 | 值 |
| --- | --- |
| 应用 ID | `com.pict.metatool` |
| 版本 | 0.1.0（开发中） |
| 系统要求 | Android 8.0（API 26）及以上 |
| 技术栈 | Kotlin · Jetpack Compose Material 3 · Navigation Compose · Hilt · Coroutines/Flow · WorkManager |
| 进度 | P0–P3 与批量链路（T5.x）已落地，逐轮记录见 [开发进展日志](docs/11-开发进展日志.md) |

## 能做什么

| 能力 | 说明 |
| --- | --- |
| 导入 | 单张、多张、整目录（SAF 持久授权）、系统相册选择器 |
| 查看 | EXIF / GPS / XMP / IPTC / ICC / 文件属性 / 缩略图，按分组折叠 |
| 编辑 | 逐字段编辑、批量同值写入、时间批量偏移、GPS 坐标编辑与随机抖动 |
| 预设 | 43 个内置档案（设备 26 / 地域 10 / 时间 6 / 混合 1），按类别分栏、跨栏同时选；自建预设就地新建 / 编辑 / 删除 |
| 随机填充 | 按权重从预设池取值，支持字段级锁定与随机种子复现；先「生成预览」看清将写入哪些字段再确认 |
| 清除 | 全部元数据，或按分组清除（GPS、设备信息、时间、MakerNote、缩略图） |
| 转换 | JPEG / PNG / WebP（有损·无损）/ HEIF / BMP，可选质量、尺寸缩放与元数据保留策略 |
| 批量 | 前台任务队列（通知里可取消）、并发 1–8、失败重试 2 次（500 ms / 2 s 退避）、进度与剩余时间预估、结果报告可导出 |
| 任务页 | 「进行中」与「历史记录」两组；历史每条可**重跑**（复制成新任务，不覆盖上一次的账）与**撤销**（按备份回滚，动文件前二次确认） |
| 设置 | 深浅色与动态取色、页面底色、图库列数、导出后缀、套用预设是否覆盖、默认随机种子、导出后校验、底栏样式与入口 |
| 安全 | 默认另存不覆盖、写后校验、可选备份、变更预览（dry-run） |

## 三条硬约束

1. **不做 `MANAGE_EXTERNAL_STORAGE`**（Google Play 政策限制），文件访问一律走 SAF / MediaStore。
2. **默认不覆盖原文件**：改动写成副本，覆盖必须二次确认且可备份。
3. **不申请 `INTERNET` 权限**：离线既是卖点，也是隐私承诺。

## 快速上手

环境要求：

- JDK 17
- Android SDK：`platforms;android-36`、`build-tools;36.0.0`
- Gradle 8.14.3（仓库自带 wrapper，无需另装）
- `GRADLE_USER_HOME`：**用户名含中文的机器必须指向纯 ASCII 路径**，否则单元测试 worker 找不到主类（详见 [docs/10 §1.1](docs/10-构建发布与合规.md)）

```bash
export GRADLE_USER_HOME=/path/to/ascii/gradle-home   # 中文用户名机器必设
./gradlew :app:assembleDebug                         # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew test                                       # 单元测试（纯 JVM）
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 可选：装到设备
```

完整工具链、签名与发布流程见 [docs/10-构建发布与合规.md](docs/10-构建发布与合规.md)。

## 项目结构

```
pict/
├─ app/                        # 单模块，package-by-feature
│  └─ src/
│     ├─ main/java/com/pict/metatool/
│     │  ├─ app/               # Application / MainActivity / navigation
│     │  ├─ core/              # 通用工具
│     │  ├─ data/              # source, metadata, codec, preset, repo
│     │  ├─ domain/            # model, plan, preset, job, format, naming, settings
│     │  └─ ui/                # library, detail, edit, preset, batch, job, jobs, report, settings, theme
│     ├─ test/                 # 单元测试
│     └─ androidTest/          # 仪器测试
├─ docs/                       # 设计文档（见下表）
├─ presets/                    # 43 个内置预设 JSON（构建时打进 assets）
├─ samples/                    # 测试样本图（小体积、授权明确）
└─ tools/                      # exiftool 比对、样本生成脚本
```

## 测试与验证

| 手段 | 怎么跑 | 说明 |
| --- | --- | --- |
| 单元测试 | `./gradlew test` | 86 个测试类 / 930 条用例，纯 JVM（无 Robolectric）。导航、回栈这类逻辑都抽成可单测的状态契约 |
| 金标准 | `tools/verify-with-exiftool.sh` | 把编辑计划折叠 → 真写通道落盘 → **由 exiftool 独立读回**，对目标字段、其余字段、像素、缩略图四项取证。需 exiftool 13.x，未装会明说并跳过（CI 加 `--allow-missing`）；产物在 `app/build/goldstandard/out/` |
| 真机走查 | 见 [docs/08](docs/08-测试与验收计划.md) | 设备矩阵与手工验收清单 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/00-项目总览与范围.md](docs/00-项目总览与范围.md) | 背景、目标用户、范围边界、约束、成功标准 |
| [docs/01-需求规格说明书.md](docs/01-需求规格说明书.md) | 用户故事、FR/NFR 编号需求与验收标准、状态机、错误码 |
| [docs/02-技术架构设计.md](docs/02-技术架构设计.md) | 分层架构、包结构、依赖、数据模型、线程与内存模型 |
| [docs/03-元数据模型与预设规范.md](docs/03-元数据模型与预设规范.md) | TagKey 模型、字段白名单、预设 JSON Schema、随机算法 |
| [docs/04-格式转换矩阵与实现路径.md](docs/04-格式转换矩阵与实现路径.md) | 各格式读写能力矩阵、编解码路径、降级策略 |
| [docs/05-存储权限与文件访问方案.md](docs/05-存储权限与文件访问方案.md) | SAF / MediaStore / scoped storage / 写回策略 |
| [docs/06-UI-UX设计说明.md](docs/06-UI-UX设计说明.md) | 页面清单、线框、交互状态、文案与 i18n |
| [docs/07-实施计划与任务拆解.md](docs/07-实施计划与任务拆解.md) | P0–P6 阶段、70+ 个可执行任务 |
| [docs/08-测试与验收计划.md](docs/08-测试与验收计划.md) | 单测 / 金标准 / 仪器测试、设备矩阵、手工验收清单 |
| [docs/09-风险清单与决策记录.md](docs/09-风险清单与决策记录.md) | 风险登记册 + ADR 架构决策记录 |
| [docs/10-构建发布与合规.md](docs/10-构建发布与合规.md) | 工具链、签名、CI、Play 合规、开源许可 |
| [docs/11-开发进展日志.md](docs/11-开发进展日志.md) | 逐轮改动、真机走查结论与踩坑 |
| [docs/附录A-元数据字段全集.md](docs/附录A-元数据字段全集.md) | EXIF / GPS / XMP / IPTC 字段表与可编辑性标注 |
| [presets/](presets/) | 43 个内置预设 JSON（26 设备 / 10 地域 / 6 时间 / 1 商品档案）+ Schema |

## 开发进展与协作

- 每轮的改动、真机走查结论与踩坑：[docs/11-开发进展日志.md](docs/11-开发进展日志.md)
- 已知风险与架构决策：[docs/09-风险清单与决策记录.md](docs/09-风险清单与决策记录.md)
- 协作约定与本地环境注意：[AGENTS.md](AGENTS.md)

## 许可

[Apache-2.0](LICENSE) © 2026 knowlily
