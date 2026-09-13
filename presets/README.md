# 预设目录

存放 Pict 的**元数据预设**文件（JSON）。格式定义见 [`schema/preset-v1.schema.json`](schema/preset-v1.schema.json)，语义说明见 [`docs/03-元数据模型与预设规范.md`](../docs/03-元数据模型与预设规范.md)。

## 文件命名

```
<kind>-<slug>.json          内置预设
user-<自定义 id>.json        用户导出预设（不提交到仓库）
```

## 内置预设一览

共 **43 个**（`docs/03-元数据模型与预设规范.md` 第 7 节的清单，T3.2 已全部落地）。字段数是该预设声明的 TagKey 条数，
不是单次抽样会写入的条数——`pool` 类字段每次只取池中一个值。

| id | 名称 | 字段 | 文件 |
| --- | --- | --- | --- |
| `device.iphone-17-pro` | iPhone 17 Pro | 21 | `device-iphone-17-pro.json` |
| `device.iphone-17` | iPhone 17 | 21 | `device-iphone-17.json` |
| `device.iphone-16-pro` | iPhone 16 Pro | 20 | `device-iphone-16-pro.json` |
| `device.iphone-16` | iPhone 16 | 21 | `device-iphone-16.json` |
| `device.iphone-15-pro` | iPhone 15 Pro | 21 | `device-iphone-15-pro.json` |
| `device.iphone-15` | iPhone 15 | 21 | `device-iphone-15.json` |
| `device.iphone-14-pro` | iPhone 14 Pro | 23 | `device-iphone-14-pro.json` |
| `device.iphone-14` | iPhone 14 | 23 | `device-iphone-14.json` |
| `device.iphone-13` | iPhone 13 | 23 | `device-iphone-13.json` |
| `device.iphone-12` | iPhone 12 | 23 | `device-iphone-12.json` |
| `device.pixel-9-pro` | Google Pixel 9 Pro | 17 | `device-pixel-9-pro.json` |
| `device.pixel-8-pro` | Pixel 8 Pro | 17 | `device-pixel-8-pro.json` |
| `device.galaxy-s24-ultra` | Galaxy S24 Ultra | 16 | `device-galaxy-s24-ultra.json` |
| `device.galaxy-s23-ultra` | Galaxy S23 系列 | 17 | `device-galaxy-s23-ultra.json` |
| `device.huawei-mate-60` | Mate 60 Pro | 16 | `device-huawei-mate-60.json` |
| `device.xiaomi-14` | Xiaomi 14 | 16 | `device-xiaomi-14.json` |
| `device.sony-a7iv` | Sony A7 IV | 25 | `device-sony-a7iv.json` |
| `device.sony-a7-iii` | Sony A7 III | 25 | `device-sony-a7-iii.json` |
| `device.canon-r6m2` | Canon EOS R6 Mark II | 24 | `device-canon-r6m2.json` |
| `device.canon-eos-r8` | Canon EOS R8 | 25 | `device-canon-eos-r8.json` |
| `device.canon-eos-5d-mark-iv` | Canon EOS 5D Mark IV | 25 | `device-canon-eos-5d-mark-iv.json` |
| `device.nikon-z6ii` | Nikon Z6 II | 22 | `device-nikon-z6ii.json` |
| `device.nikon-z50` | Nikon Z 50 | 24 | `device-nikon-z50.json` |
| `device.fujifilm-x-t5` | Fujifilm X-T5 | 22 | `device-fujifilm-x-t5.json` |
| `device.gopro-hero12` | GoPro HERO12 Black | 16 | `device-gopro-hero12.json` |
| `device.dji-mini4pro` | DJI Mini 4 Pro | 24 | `device-dji-mini4pro.json` |
| `location.shanghai` | 上海 · 人民广场周边 5 km | 14 | `location-shanghai.json` |
| `location.beijing` | 北京 · 国贸周边 5 km | 14 | `location-beijing.json` |
| `location.shenzhen` | 深圳 · 福田周边 5 km | 14 | `location-shenzhen.json` |
| `location.guangzhou` | 广州 · 天河周边 6 km | 14 | `location-guangzhou.json` |
| `location.hangzhou` | 杭州 · 西湖周边 4 km | 14 | `location-hangzhou.json` |
| `location.chengdu` | 成都 · 天府广场周边 6 km | 14 | `location-chengdu.json` |
| `location.xian` | 西安 · 钟楼周边 5 km | 14 | `location-xian.json` |
| `location.chongqing` | 重庆 · 解放碑周边 5 km | 14 | `location-chongqing.json` |
| `location.nanjing` | 南京 · 新街口周边 5 km | 14 | `location-nanjing.json` |
| `location.tokyo` | 东京 · 涩谷周边 3 km | 14 | `location-tokyo.json` |
| `time.2023` | 2023 全年 | 8 | `time-2023.json` |
| `time.2024` | 2024 全年 | 8 | `time-2024.json` |
| `time.2025` | 2025 全年 | 8 | `time-2025.json` |
| `time.2026-h1` | 2026 上半年 | 8 | `time-2026-h1.json` |
| `time.2026-h2` | 2026 下半年 | 8 | `time-2026-h2.json` |
| `time.night-2026` | 2026 夜间 | 8 | `time-night-2026.json` |
| `mixed.ecommerce` | 电商商品拍摄档案 | 19 | `mixed-ecommerce.json` |

覆盖度约定：手机厂商的 EXIF 机型串按**系统实际写入的串**给（三星是区域型号 `SM-S928B`、华为是 `ALN-AL00`、
小米是 `23127PN0CC`、尼康是 `NIKON Z 6_2`），不写营销名——脱敏场景要的是「像真机拍的」，不是「像宣传页」。
`description` 里逐条写明每个值的出处；查不到确切串的（如三星的 `LensModel`）宁缺勿造，在 `description` 说明原因。

## 用户自建预设

应用里「预设 → 自己加一个」写出来的文件落在 **应用私有目录**（`files/presets/user-*.json`），
不进仓库、不覆盖内置的那 43 个：

- 内置目录（`assets/presets/`）与用户目录在启动时**合并成一份**给界面用，**内置优先**：同 id 时以内置为准；列表里自建排在各栏最前、内置在后。
- 用户在界面上只需填「名称 + 归到哪一栏 + 字段值」，`id` 由名称推（`我的机型` → `user.preset`；撞了就补序号）；
  值文本按 `单值` / `逗号分隔多值` / `31.23~31.25` 区间 / `2026-01-01~2026-12-31` 时间区间自动转成对应 `mode`，
  表达不了的规则原样保留、不在编辑器里抹平。
- 一个文件坏了只影响它自己：读取时逐条记 `PresetIssue`（带文件名），其余预设照常可用。

## 校验

```bash
# 语法校验
python -m json.tool presets/device-iphone-16-pro.json > /dev/null

# 结构校验（需要 jsonschema）
python - <<'PY'
import json, glob, jsonschema
schema = json.load(open('presets/schema/preset-v1.schema.json', encoding='utf-8'))
for f in sorted(glob.glob('presets/*.json')):
    jsonschema.validate(json.load(open(f, encoding='utf-8')), schema)
    print('OK', f)
PY
```

## 约定

1. **id 全局唯一**，格式 `^[a-z0-9][a-z0-9._-]{2,63}$`；内置预设固定前缀 `device.` / `location.` / `time.` / `mixed.`。
2. 字段名必须是 `EXIF:` / `GPS:` / `XMP:` / `IPTC:` 开头的 TagKey，见 [附录 A](../docs/附录A-元数据字段全集.md)。
3. `weight` 缺省为 1；权重为 0 或负数视为非法。
4. `hourWeights` 必须恰好 24 个非负数。
5. 不写入序列号类字段（`BodySerialNumber`、`LensSerialNumber`、`ImageUniqueID`、`XMP:aux:SerialNumber`），除非用户显式开启。
6. 预设值必须来自公开可查的真实机型/镜头参数，不得编造；查不到确切串的字段**宁缺勿造**，
   （如三星的 `LensModel`、各厂的 `Software` 版本串）在 `description` 里说明为什么没写。
7. 相对时间（"近 30 天"）目前不支持，需用固定区间；V1.5 计划增加 `"mode": "datetimeRelative"`（`{"daysBack": 30}`）。
8. 用户自建预设不进本目录，落在应用私有目录 `files/presets/user-*.json`（见上「用户自建预设」）。
