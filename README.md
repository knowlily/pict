# Pict · 图片元数据工坊

Android 端**批量图片元数据编辑 + 格式转换**工具。全程本地处理，不申请网络权限。

> 仓库路径：`D:\githubs\pict`
> 文档版本：v1.0（2026-09-09）
> 当前阶段：**P2 收口 + P3 预设/导出 + 设置页 + 任务层（T5.1/T5.2）+ 批量编辑与预览页（T5.4/T5.5）+ 批量执行（T5.3）+ 进度页与报告（T5.6/T5.7）+ 图库目录持久化（FR-03）+ 任务历史与撤销（T5.8）+ 中断任务终态对账（FR-29）+ 预设分栏多选与用户自建预设（T5.11）** — P0 骨架、P1 读取链路、P2 编辑引擎（T2.1–T2.13：操作折叠、写通道、时间偏移、GPS 编辑、清空分组、单文件编辑 UI、备份回滚、导出副本）与 P3 的 36 个内置预设、随机填充均已提交；设置页 6 项真正接线（深/浅色主题、图库列数、导出后缀、套用预设覆盖、默认种子、导出后校验），底栏样式（悬浮＝液态玻璃胶囊，内容从底下穿过／贴底）与底栏入口可自由选择（图片／任务可关，设置固定保留）；批量功能已开刀，先落**任务层**：`Job`/`JobItem` 状态机（记账、进度与预估剩余、取消语义）、`JobRunner`（并发 1–8、FR-30 的 500 ms/2 s 重试、取消、`Flow<Job>` 快照）都在域层，真正读写由注入的 `JobItemWorker` 负责，因此并发上限/重试/取消在 JVM 上就能跑满；同轮接通计划路径的预设入口 `PresetResolver.applyPlan`（此前 `expand()` 只有测试在调，批量计划里的随机填充/预设会直接撞「尚未实现」）。任务页「进行中／历史记录」仍是空态骨架：进行中那一组的真实队列、历史列表与重跑还等 T5.8，这轮的进度页是从批量页执行完直接进去的。批量这条线这轮又往前推了两步：`BatchPlan`／`BatchDraft`（计划 + **按序号错开的随机种子**）与 `SafBatchSourceReader`（真实 SAF 读取，这条路径上没有任何写方法——预览不落盘是接口层面保证的）落在域层与数据层；界面侧 `BatchRoute`＋`BatchUiState`／`BatchViewModel`＋`BatchScreen` 已接起来：图库多选后进「批量编辑」，先选这一批要做什么（套预设／随机填充／清哪几组），再进预览页看逐项**字段级前后值**、按「有变化／无变化／不支持」过滤，「开始执行」仍只给文案，等 T5.3 的 WorkManager 接上队列。真机走查又拧了两处：可写性改成**授权位 + 格式支持**一起判（相册选择器给的地址常常只有读权限，只看格式会把「写不回去」误报成能写），进批量页前先用 `SafBatchTargets` 查一遍真名／MIME／大小／可写位（只查属性，不读内容也不落盘），占位壳不再把文件名显示成 `primary:Download/…`；图库多选里再点一项是继续勾选，不再跳详情页。编辑页的随机填充补齐了 docs/06 §3.3 的**两步**：先「生成预览」看清将写入哪些字段（复用草稿 diff 的行渲染，只算不写、与确认同一份纯计算，所以预览里看到的值就是确认后进草稿的值），翻看无误再「确认填入」；换预设／改勾选／换种子／关弹层都会让旧预览作废。真机上还发现弹层停在半屏展开时，那 20 行取值预设会把按钮顶出屏幕（按不到，看着就像「没反应」），于是弹层改成整屏展开、列表高度上限跟着屏幕走，按钮钉在列表下面。批量这条线这轮把**执行**接上了：`BatchJobSpec`（不变的作业定义：目标 + 计划 + 选项，落 `files/jobs/<jobId>.json`）与 `JobSnapshot`（执行态：计数、当前项、失败原话，落 `<jobId>.snapshot.json`）分开存；`JobQueue` 只把 `jobId` 交给 WorkManager（`enqueueUniqueWork` + `KEEP`），`JobWorker` 读回定义、跑域层 `JobRunner`、用 `setForeground` 挂通知（渠道 `pict.jobs`、进度节流 1 秒、带「取消」），「取消」经 `JobCancelReceiver` 交给 `JobCancellation`：正在写的那一项写完收手、没开工的记 `SKIPPED`。真机上 19 张（含 9 张 6000×4000）跑完的快照与预览逐项对齐（`字段变更 57 处` = 预览 57 处，成功 19、校验失败 0）。顺手把真机照出来的老账修了：写入器事前声明「格式存不下」的键（XMP 那类）不再算缺失，`ExifValueCodec.parseRational` 也认得 androidx 返回的十进制有理数（连分数还原最简分数，`0.00625 → 1/160`；不敢用精确小数，`1/75` 会变成 `10^11` 分母直接把值撑溢出）。这轮专门盯了「取消」这条链路：debug 包（release 合不进）里加了一个验收探针 `CancelProbeActivity`——`JobCancelReceiver` 是 `exported="false"`、模拟器又把对包名的广播投递拦掉，从 adb 那头根本没有一条路能走到 receiver，所以让探针在应用进程里重放「通知按钮那颗意图」，或者干脆拿最近一份定义重跑一遍再中途取消。探针一跑就现出真问题：`WorkManager` 取消 unique work 时会**直接把 worker 的协程取消**，worker 里那个 200 ms 轮询 `isStopped` 的看门狗抢不过它，域层那张 CANCELED 的终态根本发不出来，收尾只把过期的 RUNNING 落了盘——记录于是永远钉在「已处理 11/19」，未开工项也永远挂着。收尾改成 `JobSnapshot.endOf`：**被叫停且没到终态就按取消记账**（未开工项记 SKIPPED，与 `Job.cancel` 同一套口径），真机复核 `status=CANCELED finished=17/19 skipped=14 running=2`，取消后本应用的通知记录为 0（不再留一条写着「已处理 x/19」的通知）；取消语义本身继续由域层单测盖着。顺手修掉 `JobSpecStore.ids()` 会把 `<jobId>.snapshot.json` 剥成一个幽灵 id（`job-1.snapshot`）的老账——眼下只有测试在用，任务列表（T5.6）接上去就会凭空多出条目。单测 **712/712 通过**（62 个 XML），金标准 **17/17 通过**（`tools/verify-with-exiftool.sh`，111 条复核断言）；R-19 已收口（读侧按**字节层 IFD 目录**判定，不再凭空补标签，写侧也不再把它们落盘）这轮补的是**跑批之后**那一段：`JobProgressHub` 让 worker 每处理完一项就往进程里推一次广播，进度页于是有逐项状态、当前文件名和预估剩余可看，取消按钮与通知栏那颗按钮共用同一份意图（`JobQueue.cancelIntent()`）；跑完（被取消也算）在 `files/jobs/<jobId>.report.json` 落一份逐项明细——`JobReport` 在域层、零 Android 依赖，导出 CSV 开头写 BOM、字段内换行折成空格、全角逗号不加引号，图的就是「行数恒等于项数」这条验收口径，写完还读回比对（FR-33）；`JobSpecStore.ids()` 也一并排除 `.report.json`，免得再冒出幽灵条目。真机走查顺手照出一个单测照不出的 bug：报告页把「共 %1$d 项」当**标签**用，占位符原样漏在屏幕上，已改成纯标签、值仍由右列渲染。图库这边把 FR-03 的老账清了：授权拿过但树 URI 没落盘，重启回来永远 0 张——现在只记名字＋URI＋时间（最多 10 条，`\u001F` 一行一条，坏行只丢自己），授权有效性交给 `data/source` 查 `persistedUriPermissions`，域层继续零 Android 依赖；真机杀进程重启后点「最近目录」直接回到那 19 张，选择器不再弹。任务页也从占位变成真数据：进行中一组、历史一组，历史行给「查看报告／重跑／撤销」，撤销只有最近一次留了备份的那条能做——动手覆写之前先把每张现在的样子存一份到源目录下的 `Pict/backup/<时间戳>/`，备份失败就拒绝覆写（FR-34）；重跑不就地重跑，复制一份定义换个新 `jobId` 再排队，免得报告与快照互相撕账；被硬杀掉的任务（盘上最后一份快照还写着「进行中」，队列那头其实早没它了）在任务页打开时对一次账：队列说跑完了就记「已完成」、说被叫停了就记「已取消」、说没跑成或连记录都没有就记「已中断」，并补一句「还剩几项没结果」；真还在排队/跑着的一律不碰——那本来就是「进行中」，杀掉后重启还能接着跑。这轮把预设这块动了大手术。弹层从「按类别一节一节往下列」改成**四栏并列**（设备／位置／时间／混合，空栏也留着），**每栏至多选一个、跨栏可同时选**，同一栏再点一下取消；挑的过程一个字段都不动，点「套用到草稿」才按栏顺序（设备 → 位置 → 时间 → 混合）依次写入——覆盖开关关着时只补空缺，所以同一字段出现在两栏里是先选的赢，打开覆盖才是后选的赢。编辑页与批量页共用同一个 `PresetSelection` 和同一个弹层，免得「编辑页选了三栏、批量页只认最后一个」从缝里漏出来。每栏栏头挂一个「自己加一个」，用户自建预设就地新建 / 编辑 / 删除，落在私有目录 `files/presets/user-*.json`：与内置同一套格式、一文件一预设、文件名 `user-<id 去掉前缀>.json`；编辑器只收一行值文本，规则由文本推（`32, 50, 64` 是候选池，`31.23~31.25` 是区间，`2026-01-01~2026-12-31` 是时间区间），推不出来的规则（坐标/抖动/透传）保存时原样保留；落盘前先 `PresetWriter.write` → `PresetParser.parse` **读回自校验**，解析不过就拒绝落盘并报错，不出现「提示保存成功、文件其实读不回来」，目录不可写时直说「没有可写的预设目录」。内置与自建由 `MergedPresetCatalog` 合成**一份**目录给界面、两个 ViewModel 和 `JobWorker`（内置优先，同 id 以内置为准），坏文件只记自己那条、其余照常可用。预设库同时从 20 份扩到 **36 份**：新增 iPhone 15 Pro、Pixel 8 Pro、Galaxy S23 系列、Canon EOS R8、Canon EOS 5D Mark IV、Sony A7 III、Nikon Z 50 七个机型（相机那几份带 RF/EF/E 卡口镜头池与等效焦距），广州、杭州、成都、西安、重庆、南京六个地点，2023 全年、2026 下半年、2026 夜间三个时间档——值都按公开可查的机身/镜头参数写，查不到确切串的字段（三星的 LensModel、各厂的 Software 版本串）宁缺勿造并在说明里写明。批量链路里 `presetId` 顺势变成 `presetIds`（按栏顺序依次 ApplyPreset、RANDOM 每栏各管自己声明的字段、种子按序错开），老 JSON 仍读得回来、不升版本。这轮把设置页最后两处不顺眼的地方收了：**点哪一张卡里的项，弹出的编辑层就盖住那张卡**——需要编辑的三项（导出后缀／随机种子／恢复默认）从「屏幕正中的 `AlertDialog`」换成 `SettingsCardPopup`：取**卡片**的窗口矩形（`SettingsCardAnchor` + `boundsInWindow()` 取整），宽度取卡宽、最小高度取卡高，于是那张卡**原地展开**成编辑层（卡比内容高时内容在卡里居中）；真机上弹层窗口帧 `[28,259][1892,685]` 与输出那张卡 `x28–1891 / y259–514` 的左沿、上沿逐像素重合，高度 426 px 比卡高 256 px 还高——整张卡被盖住，一截卡边都不露（只盖一行时左右各留 28 px 卡面，看着像贴歪的小盒子；更早的对话框落在 `x480–1440`，横着偏了 452 px）。落点算法是纯函数 `anchoredPopupOffset`（越界时向内夹到装得下为止、还没量到卡时退回屏幕正中），边界由 `SettingsCardPopupPositionTest` 钉住。**动态取色**（FR-38 的 Material You）也接上了：`AppSettings.dynamicColor`（默认开，pref `dynamic_color`）→ `MainActivity` 的 `settings.dynamicColor && supportsDynamicColor()`，版本闸门只有一处 `supportsDynamicColor(sdkInt)`（`ui/theme/DynamicColorSupport.kt`），API < 31 时设置里那一行**置灰并写明原因**，而不是"开关亮着但颜色没变"；浅色主题下实测选中胶囊由品牌青绿 `(179,233,226)` 变成壁纸派生的浅蓝 `(209,223,242)`。**关掉动态取色之后底色可以自己挑**（FR-38 续）：设置页「界面」多一行色块（自动／云白／暖砂／薄荷／天青／藕荷／苔绿／淡粉），八块色只换 `background` 一个角色——卡片、强调色、状态色都不动，所以挑底色不会顺带把「导出失败」的红改掉；`Backdrop` 在 domain 层只收低饱和浅底，深色主题下按同一色相压到 L≈0.11（一份色号两种主题都能用，也免得浅底搬进深色里变成一块发光板），认不出来的色号在 `normalized()` 里回到「自动」。真机实测深色 + 薄荷 → 页面底色 `(20,36,27)`、浅色 + 薄荷 → `(235,246,240)`；动态取色开着时这一行置灰并写明「先关掉它才能自己挑」。单测 **849/849 通过**（81 个 XML），金标准 **17/17 通过**（111 条复核断言）。
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
| 预设 | 36 个内置档案（设备 19 / 地域 10 / 时间 6 / 混合 1）；**按类别分栏、跨栏同时选**；用户可就地新建/编辑/删除自建预设（`files/presets/user-*.json`，与内置同格式） |
| 随机填充 | 从预设池按权重随机取值，支持字段级锁定、随机种子可复现；填入草稿前先「生成预览」看清将写入的字段再确认 |
| 清除 | 全部元数据 / 按分组清除（GPS、设备信息、时间、MakerNote、缩略图） |
| 转换 | JPEG / PNG / WebP（有损/无损）/ HEIF / BMP，质量、尺寸缩放、元数据保留策略 |
| 批处理 | 任务队列、并发控制、进度、暂停/取消、失败重试、结果报告导出 |
| 任务页 | 「进行中」与「历史记录」两组（线框 docs/06 §3.8）：现在是写明将来能力的空态骨架，真实队列与记录随批处理（P5 / T5.x）接入 |
| 设置 | 深色/浅色/跟随系统（系统栏图标与窗口底色一并跟随）、动态取色（跟随壁纸的 Material You 配色，仅 Android 12 及以上；关掉即回品牌配色，关掉之后还能自选页面底色：自动／云白／暖砂／薄荷／天青／藕荷／苔绿／淡粉，只换页面底色一个角色）、图库列数、导出文件名后缀、套用预设是否覆盖、随机填充默认种子、导出后是否校验、底栏样式（悬浮＝液态玻璃胶囊，内容从玻璃底下穿过／贴底）、液态玻璃开关（关掉＝一层实心底栏，糊不动或嫌玻璃晃眼时的后路）与底栏入口选择（图片／任务可关，设置固定保留）——每一项都落到实际行为，不做摆设开关；点开要编辑的那几项（后缀／种子／恢复默认）弹出的编辑层**盖住那张设置卡**（原地展开：卡宽卡高都接上、内容在卡里居中，不是屏幕正中浮一个对话框） |
| 安全 | 默认另存不覆盖、写后校验、可选备份、变更预览（dry-run） |

## 技术栈（拟定）

Kotlin · Jetpack Compose Material 3 · Navigation Compose · Hilt · Coroutines/Flow · WorkManager · Room（自定义预设）
界面：Kyant0 Backdrop（`com.github.Kyant0:AndroidLiquidGlass`，底栏液态玻璃，Apache-2.0，走 JitPack）
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
| [presets/](presets/) | 36 个内置预设 JSON（19 设备 / 10 地域 / 6 时间 / 1 商品档案）+ Schema | ✅ |

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
   R-23 是动态取色（Material You）拿的是壁纸配色、且 API < 31 给不出调色板，按「默认开 +
   设置里一行开关 + 版本闸门一处收口」处理；R-24 是自选底色可能压掉对比度，按「只给低饱和浅底 +
   深色主题下按同一色相压暗 + 只换 `background` 一个角色」处理——底色再挑也不会把「导出失败」的红改掉。
3. 每个任务完成后提交一次 git；协作约定与踩坑见 [AGENTS.md](AGENTS.md)。

## 金标准怎么跑

```bash
tools/verify-with-exiftool.sh          # 需要 exiftool（13.x）；找不到会明说并跳过
tools/verify-with-exiftool.sh --allow-missing   # CI 上没装 exiftool 时：跳过但不报错
```

脚本干两件事：跑 `ExiftoolGoldStandardTest`（把编辑计划折叠 → 真写通道落盘 → **由 exiftool 独立读回**，
对目标字段、其余字段、像素、缩略图四项取证），再用 exiftool 把每个用例的断言逐条复核一遍。
产物留在 `app/build/goldstandard/out/`：改后的图、`*.exiftool.json` 转储、`*.changed.txt` 改动清单、`*.gaps.txt` 缺口。
