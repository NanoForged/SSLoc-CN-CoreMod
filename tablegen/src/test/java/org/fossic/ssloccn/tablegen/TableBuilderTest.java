package org.fossic.ssloccn.tablegen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TableBuilder 规则验证：stage 过滤、空译文跳过、类名转换、同值冲突消解、占位符观测。 */
class TableBuilderTest {

    /** 构造一条词条（测试夹具：只填本测试关心的字段）。 */
    private static Term term(String className, String original, String translation,
                             int stage, int constantIndex, Integer occurrenceIndex) {
        return new Term("key-" + constantIndex + "-" + (occurrenceIndex == null ? "u" : occurrenceIndex),
                original, translation, stage, className, "test.json", constantIndex, occurrenceIndex);
    }

    private static TinyMappings emptyMappings() throws Exception {
        return TinyMappings.parse(writeTiny("tiny\t2\t0\tobf\tintermediary\tnamed\n"));
    }

    private static java.nio.file.Path writeTiny(String content) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("test", ".tiny");
        java.nio.file.Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        return file;
    }

    private static TinyMappings mappingsWith(String obf, String named) throws Exception {
        return TinyMappings.parse(writeTiny(
                "tiny\t2\t0\tobf\tintermediary\tnamed\nc\t" + obf + "\tmid\t" + named + "\n"));
    }

    @Test
    void stage过滤与空译文跳过() throws Exception {
        List<Term> terms = List.of(
                term("a/A", "s1", "译1", 1, 1, null),
                term("a/A", "s3", "译3", 3, 2, null),
                term("a/A", "s5", "译5", 5, 3, null),
                term("a/A", "s9", "译9", 9, 4, null),
                term("a/A", "s0", "译0", 0, 5, null),
                term("a/A", "s2", "译2", 2, 6, null),
                term("a/A", "sx", "译x", -1, 7, null),
                term("a/A", "empty", "", 1, 8, null),
                term("a/A", "same", "same", 1, 9, null));

        TableBuilder.BuildResult result = new TableBuilder(emptyMappings()).build(terms);

        assertEquals(Map.of("s1", "译1", "s3", "译3", "s5", "译5", "s9", "译9"),
                result.classes().get("a/A"));
        assertEquals(4, result.stats().terms());
        assertEquals(1, result.stats().classes());
        assertEquals(3, result.stats().skippedStage());
        assertEquals(2, result.stats().skippedUntranslated());
    }

    @Test
    void 混淆类名转换为named未混淆类保持原名() throws Exception {
        TinyMappings mappings = mappingsWith("om/fs/X", "com/fs/named/X");
        List<Term> terms = List.of(
                term("om/fs/X", "a", "甲", 1, 1, null),
                term("com/fs/starfarer/StarfarerLauncher", "b", "乙", 1, 2, null));

        TableBuilder.BuildResult result = new TableBuilder(mappings).build(terms);

        assertTrue(result.classes().containsKey("com/fs/named/X"));
        assertTrue(result.classes().containsKey("com/fs/starfarer/StarfarerLauncher"));
        assertFalse(result.classes().containsKey("om/fs/X"));
    }

    @Test
    void 同值同译文去重() throws Exception {
        List<Term> terms = List.of(
                term("a/A", "x", "译", 1, 10, 0),
                term("a/A", "x", "译", 1, 20, 1),
                term("a/A", "x", "译", 1, 30, 2));

        TableBuilder.BuildResult result = new TableBuilder(emptyMappings()).build(terms);

        assertEquals(Map.of("x", "译"), result.classes().get("a/A"));
        assertEquals(1, result.stats().terms());
        assertEquals(2, result.stats().deduped());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void 同值冲突取同值序号0并记录明细() throws Exception {
        List<Term> terms = List.of(
                term("a/A", ".", "}。", 1, 250, 0),
                term("a/A", ".", "。", 1, 430, 2),
                term("a/A", ".", "。", 5, 428, 1));

        TableBuilder.BuildResult result = new TableBuilder(emptyMappings()).build(terms);

        assertEquals(Map.of(".", "}。"), result.classes().get("a/A"));
        assertEquals(1, result.conflicts().size());
        TableBuilder.ConflictRecord conflict = result.conflicts().get(0);
        assertEquals("a/A", conflict.className());
        assertEquals(".", conflict.original());
        assertEquals("}。", conflict.winner().translation());
        assertEquals(2, conflict.dropped().size());
    }

    @Test
    void 占位符数量不一致记入警告不阻断() throws Exception {
        List<Term> terms = List.of(
                term("a/A", "Sell %s for %d credits", "出售 %s", 1, 1, null),
                term("a/A", "100%% off %s", "全免 %s", 1, 2, null));

        TableBuilder.BuildResult result = new TableBuilder(emptyMappings()).build(terms);

        assertEquals(2, result.stats().terms());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("Sell %s for %d credits"));
    }

    @Test
    void 输出按键排序保证确定性() throws Exception {
        List<Term> terms = List.of(
                term("b/B", "z", "1", 1, 1, null),
                term("a/A", "y", "2", 1, 2, null),
                term("b/B", "a", "3", 1, 3, null));

        TableBuilder.BuildResult result = new TableBuilder(emptyMappings()).build(terms);

        assertEquals(List.of("a/A", "b/B"), List.copyOf(result.classes().keySet()));
        assertEquals(List.of("a", "z"), List.copyOf(result.classes().get("b/B").keySet()));
    }
}
