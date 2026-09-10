#!/usr/bin/env bash
# exiftool 金标准复核（T2.12）。
#
# 用法：tools/verify-with-exiftool.sh [--allow-missing]
#
# 退出标准（docs/00 §1、docs/07 T2.12）说的是「改完元数据，用 exiftool 读回来对得上」。
# 这件事分两层做，互相独立：
#
#   1. ExiftoolGoldStandardTest —— 走真实写通道（commons-imaging 无损重写）改图，
#      再调 exiftool 读回，断言目标字段的值、断言其余字段一个没动、断言像素不变；
#   2. 本脚本 —— 同一个 exiftool、另一条通路：把上一步留下的 checks.tsv 逐条
#      再对一遍，并把改动清单 / JSON 转储的路径打出来给人看。
#
# 第 1 层是权威判据（测试 XML 里的 failures/errors），第 2 层是防「测试自己糊弄自己」的复核：
# 两层的读数不一致，说明有问题。
#
# 环境：JAVA_HOME / ANDROID_HOME 没设时按下面的默认值补；exiftool 找 PICT_EXIFTOOL，
# 否则按候选路径找，再否则找 PATH。找不到 exiftool 时脚本默认**失败**（金标准没跑成
# 不该算通过），加 --allow-missing 才会退化成跳过。

set -uo pipefail

ALLOW_MISSING=0
for arg in "$@"; do
    case "$arg" in
        --allow-missing) ALLOW_MISSING=1 ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "未知参数：$arg" >&2; exit 2 ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

: "${JAVA_HOME:=C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot}"
: "${ANDROID_HOME:=D:/Android/Sdk}"
: "${ANDROID_SDK_ROOT:=$ANDROID_HOME}"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT
export PATH="$JAVA_HOME/bin:$PATH"

XML="app/build/test-results/testDebugUnitTest/TEST-com.pict.metatool.goldstandard.ExiftoolGoldStandardTest.xml"
OUT_DIR="app/build/goldstandard/out"

# ---- exiftool ----------------------------------------------------------------

find_exiftool() {
    if [ -n "${PICT_EXIFTOOL:-}" ] && [ -x "${PICT_EXIFTOOL}" ]; then
        echo "$PICT_EXIFTOOL"; return 0
    fi
    for candidate in \
        "D:/tools/exiftool/exiftool.exe" \
        "D:/tools/exiftool/exiftool-13.59_64/exiftool.exe" \
        "/usr/bin/exiftool" \
        "/usr/local/bin/exiftool"
    do
        [ -x "$candidate" ] && { echo "$candidate"; return 0; }
    done
    command -v exiftool 2>/dev/null && return 0
    return 1
}

EXIFTOOL="$(find_exiftool || true)"
if [ -z "$EXIFTOOL" ]; then
    cat >&2 <<'EOF'
找不到 exiftool，金标准没法跑。
  · 设 PICT_EXIFTOOL 指到可执行文件（例：export PICT_EXIFTOOL="D:/tools/exiftool/exiftool.exe"）
  · 或者把 exiftool 装进 PATH（Windows 便携版解压即用；exiftool 需要 perl，
    Windows 便携包 "exiftool-XX.XX_64.zip" 里自带，不必另装）
  · 只想跑单元测试：./gradlew :app:testDebugUnitTest
  · 确实要先跳过这份金标准：加上 --allow-missing
EOF
    [ "$ALLOW_MISSING" = "1" ] && exit 0
    exit 3
fi
export PICT_EXIFTOOL="$EXIFTOOL"
echo "exiftool: $EXIFTOOL ($("$EXIFTOOL" -ver))"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo

# ---- 第 1 层：跑金标准测试 ----------------------------------------------------

echo "== 跑 ExiftoolGoldStandardTest =="
./gradlew :app:testDebugUnitTest --tests '*ExiftoolGoldStandardTest*'
GRADLE_EXIT=$?

if [ ! -f "$XML" ]; then
    echo >&2
    echo "没有测试报告 $XML —— 说明测试根本没跑到（日志往上翻）" >&2
    exit 1
fi

read_attr() {
    grep -o "$1=\"[0-9]*\"" "$XML" | head -1 | grep -o '[0-9]*'
}

TESTS="$(read_attr tests)"
FAILURES="$(read_attr failures)"
ERRORS="$(read_attr errors)"
SKIPPED="$(read_attr skipped)"
: "${TESTS:=0}"; : "${FAILURES:=0}"; : "${ERRORS:=0}"; : "${SKIPPED:=0}"

echo
echo "测试报告：$XML"
echo "  tests=$TESTS failures=$FAILURES errors=$ERRORS skipped=$SKIPPED"

if [ "$SKIPPED" != "0" ] && [ "$TESTS" = "$SKIPPED" ]; then
    echo >&2
    echo "全部用例被跳过（大概率是没认出 exiftool）——不算通过。" >&2
    [ "$ALLOW_MISSING" = "1" ] || exit 1
fi

# ---- 第 2 层：脚本自己拿 exiftool 再对一遍 ------------------------------------

echo
echo "== 复核：用 exiftool 逐条对 checks.tsv =="
CHECKED=0
BAD=0
for file in "$OUT_DIR"/*.jpg "$OUT_DIR"/*.tif; do
    [ -e "$file" ] || continue
    id="$(basename "$file")"; id="${id%.*}"
    checks="$OUT_DIR/$id.checks.tsv"
    [ -f "$checks" ] || continue

    result="$("$EXIFTOOL" -a -G1 -s -n "$file" | awk -v checks="$checks" '
        function tol(a, b) { d = a - b; return (d < 0 ? -d : d) <= 0.000001 }
        BEGIN {
            FS = "\t"
            while ((getline line < checks) > 0) {
                if (line ~ /^#/ || line == "") continue
                n = split(line, p, "\t")
                if (n < 2) continue
                m++; order[m] = p[2]; kind[p[2]] = p[1]; want[p[2]] = p[3]
            }
            close(checks)
        }
        {
            sub(/\r$/, "")
            if (match($0, /^\[([^]]+)\][ \t]+([^ \t]+)[ \t]*:[ \t]?(.*)$/, a))
                actual[a[1] ":" a[2]] = a[3]
        }
        END {
            bad = 0; gn = 0
            for (i = 1; i <= m; i++) {
                k = order[i]; ok = 0
                if (kind[k] == "value")
                    ok = (k in actual) && (want[k] ~ /^-?[0-9.]+$/ && actual[k] ~ /^-?[0-9.]+$/ \
                        ? tol(actual[k] + 0, want[k] + 0) : actual[k] == want[k])
                else if (kind[k] == "gone")
                    ok = !(k in actual)
                else if (kind[k] == "intact")
                    ok = (k in actual) && actual[k] == want[k]
                else if (kind[k] == "gap") {
                    # 缺口：测试已把它钉成「确实丢了」，这里只做独立确认，不计失败
                    if (k in actual) gn++
                    continue
                }
                if (!ok) { bad++; fdet[++fn] = "    x " kind[k] " " k " 期望[" want[k] "] 实际[" (k in actual ? actual[k] : "无") "]" }
            }
            printf "%d %d\n", m, (bad > 0 ? 1 : 0)
            for (i = 1; i <= fn; i++) print fdet[i]
            if (gn > 0) print "    · 本用例缺 " gn " 项（已在测试里钉住，不计失败）"
        }
    ')"

    summary="$(printf '%s\n' "$result" | head -1)"
    total="${summary%% *}"
    rest="${summary#* }"
    bad="${rest%% *}"
    details="$(printf '%s\n' "$result" | tail -n +2)"
    CHECKED=$((CHECKED + total))
    if [ "$bad" = "0" ]; then
        echo "  ok  $id（$total 条）"
        printf '%s\n' "$details" | grep '^    · ' | sed 's/^/    /' || true
    else
        echo "  FAIL $id"
        printf '%s\n' "$details"
        BAD=$((BAD + 1))
    fi
done

echo
echo "复核合计：$CHECKED 条断言，$BAD 个用例不一致"

# ---- 产物位置 ----------------------------------------------------------------

echo
echo "== 产物 =="
echo "  改后的图 / exiftool JSON 转储：$OUT_DIR/"
if [ -d "$OUT_DIR" ]; then
    ls -1 "$OUT_DIR" | sed 's/^/    /'
    echo
    echo "  改动清单（[意图] = 计划内的改动，[白名单] = 派生量/结构偏移/[缺口] 见下，[其它] = 计划外，必须为空）："
    for changed in "$OUT_DIR"/*.changed.txt; do
        [ -e "$changed" ] || continue
        echo "    $(basename "$changed")"
        grep '^\[其它\]' "$changed" | sed 's/^/      /'
    done
    # 缺口：金标准跑出来、已经钉在测试里的「写通道留不住的字段」
    gaps_found=0
    for gaps in "$OUT_DIR"/*.gaps.txt; do
        [ -e "$gaps" ] || continue
        if [ "$gaps_found" = "0" ]; then
            echo
            echo "  已知缺口（写通道目前留不住的字段，测试里已逐个钉住；修好后测试会失败提醒你删条目）："
            gaps_found=1
        fi
        echo "    $(basename "$gaps")"
        grep -v '^#' "$gaps" | sed 's/^/      /'
    done
fi

# ---- 判定 -------------------------------------------------------------------

echo
if [ "$FAILURES" != "0" ] || [ "$ERRORS" != "0" ] || [ "$BAD" != "0" ]; then
    echo "结论：不通过（测试 failures=$FAILURES errors=$ERRORS；脚本复核不一致用例=$BAD）" >&2
    exit 1
fi
if [ "$GRADLE_EXIT" != "0" ]; then
    echo "结论：不通过（Gradle 退出码 $GRADLE_EXIT，但报告里没读到失败，往下查日志）" >&2
    exit 1
fi
echo "结论：通过（$TESTS 个用例、$CHECKED 条复核断言，全部一致）"
