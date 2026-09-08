#!/usr/bin/env bash
# 从本地化仓库同步词条快照到 terms/：
#   - 拷贝 para_tranz/output/{starfarer_obf.json, starfarer.api.json}
#   - 重写 terms/SOURCE.md（来源仓库 commit / 游戏版本 / 同步日期）
#   - 更新 gradle.properties 的 ssloc.termsCommit（generateStringTable 写入 string-table.json 的 generatedFrom）
#
# 用法：tools/sync_terms.sh [本地化仓库路径]
# 默认路径：../Starsector-Localization-CN（相对本仓库根）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_REPO="${1:-$ROOT/../Starsector-Localization-CN}"
OUTPUT_DIR="$SRC_REPO/para_tranz/output"

for f in starfarer_obf.json starfarer.api.json; do
    if [[ ! -f "$OUTPUT_DIR/$f" ]]; then
        echo "词条文件不存在：$OUTPUT_DIR/$f" >&2
        exit 1
    fi
done

COMMIT="$(git -C "$SRC_REPO" rev-parse HEAD)"
SYNC_DATE="$(date +%F)"
GAME_VERSION="0.98a-RC8"

mkdir -p "$ROOT/terms"
cp "$OUTPUT_DIR/starfarer_obf.json" "$OUTPUT_DIR/starfarer.api.json" "$ROOT/terms/"

cat > "$ROOT/terms/SOURCE.md" <<EOF
# 词条快照来源

- 来源仓库：Starsector-Localization-CN（para_tranz/output/）
- 来源 commit：\`$COMMIT\`
- 目标游戏版本：$GAME_VERSION
- 同步日期：$SYNC_DATE
- 同步方式：\`tools/sync_terms.sh\`（重新同步后直接提交 terms/ 与 gradle.properties 的变更）
EOF

# 更新 gradle.properties 中的来源 commit
sed -i "s/^ssloc.termsCommit=.*/ssloc.termsCommit=$COMMIT/" "$ROOT/gradle.properties"

echo "已同步词条快照 @$COMMIT"
wc -c "$ROOT/terms/starfarer_obf.json" "$ROOT/terms/starfarer.api.json"
