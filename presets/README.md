# 预设目录

存放 Pict 的**元数据预设**文件（JSON）。格式定义见 [`schema/preset-v1.schema.json`](schema/preset-v1.schema.json)，语义说明见 [`docs/03-元数据模型与预设规范.md`](../docs/03-元数据模型与预设规范.md)。

## 文件命名

```
<kind>-<slug>.json          内置预设
user-<自定义 id>.json        用户导出预设（不提交到仓库）
```

## 当前样例

| 文件 | kind | 说明 |
| --- | --- | --- |
| `device-iphone-16-pro.json` | device | iPhone 16 Pro（含 Pro Max、多镜头池） |
| `device-pixel-9-pro.json` | device | Google Pixel 9 Pro |
| `device-sony-a7iv.json` | device | Sony ILCE-7M4（含参数区间模式示例） |
| `location-shanghai.json` | location | 上海人民广场 5 km 圆内随机定位 |
| `time-2024-2026.json` | time | 2024–2026 时间随机（含小时权重） |
| `mixed-ecommerce.json` | mixed | 电商商品档案（机型池 + 时间 + 无 GPS） |

> `docs/03` 列出的 22 个内置预设中，其余 16 个（iPhone 13、Galaxy S24 Ultra、Mate 60 Pro、Xiaomi 14、Canon R6 II、Nikon Z6 II、Fujifilm X-T5、GoPro HERO12、DJI Mini 4 Pro、北京/深圳/东京定位、2025/2026H1 时间等）在 **T3.2** 补齐，格式与上表一致。

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
6. 预设值必须来自公开可查的真实机型/镜头参数，不得编造。
7. 相对时间（"近 30 天"）目前不支持，需用固定区间；V1.5 计划增加 `"mode": "datetimeRelative"`（`{"daysBack": 30}`）。
