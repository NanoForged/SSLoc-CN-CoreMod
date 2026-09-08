package org.fossic.ssloccn.tablegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** TermsReader 真实解析验证：正常字段、同值序号可选项、格式损坏报错。 */
class TermsReaderTest {

    @TempDir
    Path tempDir;

    private Path writeTerms(JsonArray array) throws Exception {
        Path file = tempDir.resolve("terms.json");
        Files.writeString(file, array.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static JsonObject term(String key, String original, String translation, int stage, String context) {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", key);
        obj.addProperty("original", original);
        obj.addProperty("translation", translation);
        obj.addProperty("stage", stage);
        obj.addProperty("context", context);
        return obj;
    }

    private static String context(String classPath, String constant, String occurrence) {
        StringBuilder sb = new StringBuilder();
        sb.append("版本：0.98-RC8 词条格式：v2\n");
        sb.append("文件：starfarer_obf.jar\n");
        sb.append("类：").append(classPath).append('\n');
        sb.append("常量号：").append(constant).append('\n');
        if (occurrence != null) {
            sb.append("同值序号：").append(occurrence).append('\n');
        }
        sb.append("原始数据：\"x\"\n译文数据：\"y\"");
        return sb.toString();
    }

    @Test
    void 正常词条解析出全部结构化字段() throws Exception {
        JsonArray array = new JsonArray();
        array.add(term("k1", "Hello", "你好", 1,
                context("om/fs/starfarer/A.class", "0258", null)));
        array.add(term("k2", ".", "。", 5,
                context("om/fs/starfarer/B$C.class", "0430", "2")));

        List<Term> terms = new TermsReader().read(writeTerms(array));

        assertEquals(2, terms.size());
        Term first = terms.get(0);
        assertEquals("k1", first.key());
        assertEquals("Hello", first.original());
        assertEquals("你好", first.translation());
        assertEquals(1, first.stage());
        assertEquals("om/fs/starfarer/A", first.className());
        assertEquals("terms.json", first.sourceFile());
        assertEquals(258, first.constantIndex());
        assertNull(first.occurrenceIndex());

        Term second = terms.get(1);
        assertEquals("om/fs/starfarer/B$C", second.className());
        assertEquals(430, second.constantIndex());
        assertEquals(2, second.occurrenceIndex());
    }

    @Test
    void 缺类行直接报错() {
        JsonArray array = new JsonArray();
        array.add(term("k1", "Hello", "你好", 1, "文件：starfarer_obf.jar\n常量号：0001\n"));
        assertThrows(IllegalArgumentException.class,
                () -> new TermsReader().read(writeTerms(array)));
    }

    @Test
    void 类行不是class后缀直接报错() {
        JsonArray array = new JsonArray();
        array.add(term("k1", "Hello", "你好", 1,
                context("om/fs/starfarer/A.java", "0001", null)));
        assertThrows(IllegalArgumentException.class,
                () -> new TermsReader().read(writeTerms(array)));
    }

    @Test
    void 缺translation字段直接报错() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "k1");
        obj.addProperty("original", "Hello");
        obj.addProperty("stage", 1);
        obj.addProperty("context", context("om/fs/starfarer/A.class", "0001", null));
        JsonArray array = new JsonArray();
        array.add(obj);
        assertThrows(IllegalArgumentException.class,
                () -> new TermsReader().read(writeTerms(array)));
    }

    @Test
    void 顶层不是数组直接报错() throws Exception {
        Path file = tempDir.resolve("bad.json");
        Files.writeString(file, "{\"key\":\"x\"}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new TermsReader().read(file));
    }
}
