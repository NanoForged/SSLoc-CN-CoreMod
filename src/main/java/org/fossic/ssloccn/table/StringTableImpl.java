package org.fossic.ssloccn.table;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * {@link StringTable} 的 org.json 实现：加载并持有 string-table.json。
 *
 * <p>org.json 由游戏 classpath 自带（compileOnly，不打进 coremod jar）。
 * 表格式损坏（缺 gameVersion/termFormat/classes、termFormat 版本不符、译文非字符串）
 * 一律抛 {@link IllegalStateException}——汉化表损坏不应静默进入英文游戏。
 */
public final class StringTableImpl implements StringTable {

    /** 支持的表格式版本（与 tablegen 的 TableWriter.TERM_FORMAT 对应）。 */
    public static final String SUPPORTED_TERM_FORMAT = "v2";

    private final Map<String, Map<String, String>> table;
    private final String gameVersion;
    private final int termCount;

    private StringTableImpl(Map<String, Map<String, String>> table, String gameVersion, int termCount) {
        this.table = table;
        this.gameVersion = gameVersion;
        this.termCount = termCount;
    }

    /**
     * 从输入流加载 string-table.json。
     *
     * @param in 表文件输入流（由调用方关闭）
     * @throws IOException              读取失败
     * @throws IllegalStateException    表格式损坏或版本不符
     */
    @SuppressWarnings("unchecked") // 游戏自带的老版 org.json 中 keys() 返回原生 Iterator
    public static StringTable load(InputStream in) throws IOException {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        JSONObject root;
        try {
            root = new JSONObject(content);
        } catch (JSONException e) {
            throw new IllegalStateException("string-table.json 不是合法 JSON", e);
        }

        String gameVersion = root.optString("gameVersion", "");
        if (gameVersion.isEmpty()) {
            throw new IllegalStateException("string-table.json 缺 gameVersion 字段");
        }
        String termFormat = root.optString("termFormat", "");
        if (!SUPPORTED_TERM_FORMAT.equals(termFormat)) {
            throw new IllegalStateException(
                    "string-table.json 格式版本不符：期望 " + SUPPORTED_TERM_FORMAT + "，实际 " + termFormat);
        }
        JSONObject classes = root.optJSONObject("classes");
        if (classes == null) {
            throw new IllegalStateException("string-table.json 缺 classes 段");
        }

        Map<String, Map<String, String>> table = new HashMap<>();
        int termCount = 0;
        for (Iterator<String> classNames = classes.keys(); classNames.hasNext(); ) {
            String className = classNames.next();
            JSONObject classTable;
            try {
                classTable = classes.getJSONObject(className);
            } catch (JSONException e) {
                throw new IllegalStateException("类 " + className + " 的词条段不是 JSON 对象", e);
            }
            Map<String, String> classMap = new HashMap<>();
            for (Iterator<String> originals = classTable.keys(); originals.hasNext(); ) {
                String original = originals.next();
                Object translation;
                try {
                    translation = classTable.get(original);
                } catch (JSONException e) {
                    throw new IllegalStateException(
                            "类 " + className + " 中原文 \"" + original + "\" 读取译文失败", e);
                }
                if (!(translation instanceof String text)) {
                    throw new IllegalStateException(
                            "类 " + className + " 中原文 \"" + original + "\" 的译文不是字符串");
                }
                classMap.put(original, text);
            }
            termCount += classMap.size();
            table.put(className, Map.copyOf(classMap));
        }
        return new StringTableImpl(Map.copyOf(table), gameVersion, termCount);
    }

    @Override
    public Map<String, String> forClass(String internalName) {
        return table.get(internalName);
    }

    @Override
    public int classCount() {
        return table.size();
    }

    @Override
    public int termCount() {
        return termCount;
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }
}
