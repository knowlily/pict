# AGENTS.md · pict 协作约定

> 给在本仓库工作的 AI（下次会话的自己、Codex、其他 agent）看的**规则手册**。
> 不是变更日志 —— 历史看 `git log`，任务状态看 `README.md`，任务拆解看 `docs/07`。

## 项目一句话

Android 端**离线批量图片元数据编辑 + 格式转换**工具，包名 `com.pict.metatool`。
需求/架构/字段全集在 `docs/`（索引见 `README.md`）；任务与验收标准在 `docs/07`、`docs/08`。

## 环境（本机已核实，别改）

| 项 | 值 |
| --- | --- |
| 项目根 | `D:\githubs\pict`（**不放 C 盘**，C 盘常年紧张） |
| `JAVA_HOME` | `C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot` |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `D:/Android/Sdk` |
| `GRADLE_USER_HOME` | `D:/gradle-home` —— **必须设**，中文用户名会让测试 worker 找不到主类 |
| 日志 / 临时产物 | `D:/tmp/pict-logs/`、`D:/tmp/`（零 C 盘写入） |

构建命令模板：

```bash
cd /d/githubs/pict && export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"; \
  export ANDROID_HOME="D:/Android/Sdk"; export ANDROID_SDK_ROOT="D:/Android/Sdk"; \
  export GRADLE_USER_HOME="D:/gradle-home"; export PATH="$JAVA_HOME/bin:$PATH"; \
  ./gradlew :app:testDebugUnitTest > /d/tmp/pict-logs/<名>.log 2>&1; echo "EXIT=$?"
```

## 红线

1. **不做 `MANAGE_EXTERNAL_STORAGE`**；文件访问一律 SAF / MediaStore。
2. **默认不覆盖原文件**；覆盖必须二次确认且可备份。
3. **不申请 `INTERNET` 权限**（V1）—— CI 断言。
4. **退出码不可信**：Gradle 测试失败有时仍 exit 0。**必须**解析
   `app/build/test-results/testDebugUnitTest/*.xml` 的 `failures` / `errors` 才算实证，
   别拿 `EXIT=0` 当绿。
5. **临时诊断测试用完即删**，不随提交入库。
6. KDoc / 块注释里**不得出现字面 `/*`**（会提前闭合注释）。

## 踩坑清单（实测过，别再踩）

### Commons Imaging 1.0.0-alpha6

- 常量挂在 `AbstractFieldType`，不是 `FieldType`。
- 没有 `getOrCreateInteroperabilityDirectory()` → 用 `outputDirectory.addInteroperabilityDirectory()`。
- `RationalNumber` 构造收 `Int` 不收 `Long`。
- **`TiffImageMetadata(TiffContents)` 这个构造是残缺的**：既不填 `items`（于是 `getOutputSet()` /
  `getDirectories()` 恒返空集），也不填 `allFields`（`getAllFields()` 恒空）。真数据只在
  `contents.directories` 里 —— 读字段走 `contents.directories.flatMap { it.directoryEntries }`，
  重建输出集同理，字节序取 `contents.header.byteOrder`（否则会莫名变成大端 MM）。
- **TIFF 写路径不要手动重建输出集**：`Imaging.getMetadata` 返回的 `outputSet` 里偏移类字段
  （strip offsets 等）已是库算好的形态，手动重建会把偏移当普通字段照搬旧值，写出后 strip
  定位丢失。只有 JPEG 路径（自扫 APP1 段）才需要 `outputSetOf`。
- `ExifRewriter` 对空输出集抛 `No directories.` → 原图无 EXIF 且目标无新增时直接搬字节短路。

### ExifInterface

- 对 PNG / WebP / HEIF 读尺寸返回字符串 `"0"`，必须丢弃，不能当有效值。

### 测试环境

- JVM 单测只有 JUnit + turbine，**没有 Robolectric / MockK**；需要 Android 框架的落盘验证走
  instrumented（MuMu `V2366GA - 15`，`127.0.0.1:7555`，adb 在 `/d/platform-tools/adb`）。
- Commons Imaging 是纯 JVM 库，写路径单测内端到端即可（含像素逐字节比对），**不加 instrumented 用例**。
- **JVM 单测里没有 `java.awt`**（`Imaging.getBufferedImage` 编不过）：TIFF 的像素校验走原始 strip
  字节（`TiffTagConstants.TIFF_TAG_STRIP_OFFSETS` + `STRIP_BYTE_COUNTS` 切一段），JPEG 走 SOS 段起逐字节。
- **金标准**（exiftool 比对）跑法：`tools/verify-with-exiftool.sh`；exiftool 找 `PICT_EXIFTOOL`，
  本机便携版在 `D:/tools/exiftool/exiftool.exe`（13.59，自带 perl，无需另装）。需有 exiftool 才有意义，
  缺失时脚本默认失败、`--allow-missing` 才降级为跳过。
- **exiftool 输出别用 `-T`**：`-T`（表格）只打印值、不带标签名，解析不出来；统一用长格式
  `-a -G1 -s -n`（`[组] 标签 : 值`，`-n` 出裸数值），group 名是 family-1（`IFD0` / `ExifIFD` / `GPS` / `IFD1` / `System` / `Composite`）。
- 金标准已钉住两个写通道缺口：**R-16**（JPEG 无损重写丢 IFD1 缩略图）、**R-17**（GPS IFD 重建丢 `GPSVersionID`）。
  它们在 `ExiftoolGoldStandardTest.defaultGaps()` 里逐条声明，修好会立刻失败提醒删条目 —— 别把断言改松。

## 工作流

- 每个任务完成后**单次提交**，中文 commit message，主题形如 `feat(<层>): <做了什么> (T<id>)`。
- 提交前相关单测须绿（XML 实证），工作区不得残留诊断文件。
- 任务完成 → 更新 `README.md` 的「当前阶段」与「下一步」；**本文件只在出现新规则或新坑时改**。
