package org.fossic.ssloccn.tablegen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * string-table.json 写出器。
 *
 * <p>输出格式（运行期由宿主模块用游戏自带 org.json 解析）：
 * <pre>{@code
 * {
 *   "gameVersion": "0.98a-RC8",
 *   "termFormat": "v2",
 *   "generatedFrom": "Starsector-Localization-CN@<commit>",
 *   "stats": { "terms": N, "classes": M, ... },
 *   "classes": { "<named 内部名>": { "<original>": "<translation>" } }
 * }
 * }</pre>
 *
 * <p>写出保持键序（构建端已排序），保证产物字节确定、词条变更可走 git diff 评审。
 */
public final class TableWriter {

    /** 表格式版本，运行期 {@code StringTableImpl} 校验。 */
    public static final String TERM_FORMAT = "v2";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * 写出 string-table.json。
     *
     * @param out           输出路径（父目录自动创建）
     * @param result        组装结果
     * @param gameVersion   目标游戏版本（如 0.98a-RC8）
     * @param generatedFrom 词条来源描述（仓库@commit）
     */
    public void write(Path out, TableBuilder.BuildResult result, String gameVersion, String generatedFrom)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("gameVersion", gameVersion);
        root.addProperty("termFormat", TERM_FORMAT);
        root.addProperty("generatedFrom", generatedFrom);

        TableBuilder.TableStats stats = result.stats();
        JsonObject statsJson = new JsonObject();
        statsJson.addProperty("terms", stats.terms());
        statsJson.addProperty("classes", stats.classes());
        statsJson.addProperty("skippedUntranslated", stats.skippedUntranslated());
        statsJson.addProperty("skippedStage", stats.skippedStage());
        statsJson.addProperty("deduped", stats.deduped());
        root.add("stats", statsJson);

        JsonObject classesJson = new JsonObject();
        for (Map.Entry<String, Map<String, String>> classEntry : result.classes().entrySet()) {
            JsonObject classTable = new JsonObject();
            for (Map.Entry<String, String> termEntry : classEntry.getValue().entrySet()) {
                classTable.addProperty(termEntry.getKey(), termEntry.getValue());
            }
            classesJson.add(classEntry.getKey(), classTable);
        }
        root.add("classes", classesJson);

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }
}
