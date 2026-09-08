package org.fossic.ssloccn.tablegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ParaTranz 词条 JSON 读取器：把平台交换格式解析为 {@link Term} 列表。
 *
 * <p>动机：词条 JSON 的 key 含截断 hash、stage/译文状态需要结构化，且权威类路径藏在
 * context 文本行中（「类：om/fs/starfarer/xxx.class」）。本类是唯一解析入口，
 * 格式不符（缺字段、缺「类：」行、后缀不是 .class）一律抛错——词条快照损坏应在
 * 构建期暴露，不允许带病生成表。
 */
public final class TermsReader {

    /** context 中的类路径行：{@code 类：com/fs/starfarer/X.class}（全角冒号）。 */
    private static final Pattern CLASS_LINE = Pattern.compile("^类：(\\S+)$", Pattern.MULTILINE);

    /** context 中的常量号行：{@code 常量号：0258}。 */
    private static final Pattern CONSTANT_LINE = Pattern.compile("^常量号：(\\d+)$", Pattern.MULTILINE);

    /** context 中的同值序号行（可选，仅同类同原文多常量时存在）：{@code 同值序号：0}。 */
    private static final Pattern OCCURRENCE_LINE = Pattern.compile("^同值序号：(\\d+)$", Pattern.MULTILINE);

    /**
     * 读取一个词条 JSON 文件（顶层为数组，元素含 key/original/translation/stage/context）。
     *
     * @param file 词条 JSON 路径
     * @return 词条列表（未做任何过滤，原样解析）
     * @throws IOException              文件读取失败
     * @throws IllegalArgumentException JSON 结构或 context 格式不符
     */
    public List<Term> read(Path file) throws IOException {
        String sourceFile = file.getFileName().toString();
        JsonArray array;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                throw new IllegalArgumentException(sourceFile + "：词条 JSON 顶层必须是数组");
            }
            array = root.getAsJsonArray();
        }

        List<Term> terms = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            String key = requireString(obj, "key", sourceFile);
            String original = requireString(obj, "original", sourceFile);
            String translation = requireString(obj, "translation", sourceFile);
            if (!obj.has("stage") || !obj.get("stage").isJsonPrimitive()) {
                throw new IllegalArgumentException(sourceFile + "：词条缺 stage 字段，key=" + key);
            }
            int stage = obj.get("stage").getAsInt();
            String context = requireString(obj, "context", sourceFile);
            terms.add(new Term(
                    key,
                    original,
                    translation,
                    stage,
                    parseClassName(context, key, sourceFile),
                    sourceFile,
                    parseConstantIndex(context, key, sourceFile),
                    parseOccurrenceIndex(context)));
        }
        return terms;
    }

    /**
     * 从 context 解析权威类内部名（去 {@code .class} 后缀）。
     *
     * <p>旧版 para_tranz 同原文多常量合并格式的 context 会含多个「类：」块，
     * 本管线不支持合并格式（块与常量号/同值序号的对应关系无法确定），
     * 检测到多块直接抛错——格式漂移应在构建期暴露，不允许静默只取第一块。
     *
     * @param context    词条 context 全文
     * @param key        词条 key（仅用于报错信息）
     * @param sourceFile 来源文件名（仅用于报错信息）
     * @return 类内部名（{@code /} 分隔）
     */
    static String parseClassName(String context, String key, String sourceFile) {
        Matcher matcher = CLASS_LINE.matcher(context);
        if (!matcher.find()) {
            throw new IllegalArgumentException(sourceFile + "：词条 context 缺「类：」行，key=" + key);
        }
        String classPath = matcher.group(1);
        if (matcher.find()) {
            StringBuilder blocks = new StringBuilder(classPath);
            do {
                blocks.append("、").append(matcher.group(1));
            } while (matcher.find());
            throw new IllegalArgumentException(sourceFile
                    + "：词条 context 含多个「类：」块（同原文多常量合并格式，本管线不支持）："
                    + blocks + "，key=" + key);
        }
        if (!classPath.endsWith(".class")) {
            throw new IllegalArgumentException(
                    sourceFile + "：「类：」行不是 .class 路径：" + classPath + "，key=" + key);
        }
        return classPath.substring(0, classPath.length() - ".class".length());
    }

    /** 从 context 解析常量号（必填，缺失/非数字即格式损坏）。 */
    private static int parseConstantIndex(String context, String key, String sourceFile) {
        Matcher matcher = CONSTANT_LINE.matcher(context);
        if (!matcher.find()) {
            throw new IllegalArgumentException(sourceFile + "：词条 context 缺「常量号：」行，key=" + key);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** 从 context 解析同值序号（可选项，无则返回 null）。 */
    private static Integer parseOccurrenceIndex(String context) {
        Matcher matcher = OCCURRENCE_LINE.matcher(context);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String requireString(JsonObject obj, String field, String sourceFile) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException(sourceFile + "：词条缺 " + field + " 字段：" + obj);
        }
        return obj.get(field).getAsString();
    }
}
