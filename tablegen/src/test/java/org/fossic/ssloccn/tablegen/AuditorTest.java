package org.fossic.ssloccn.tablegen;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auditor 真实审计验证：字符串真值对照纯净 jar，named jar 做类存在性漂移观测。 */
class AuditorTest {

    @TempDir
    Path tempDir;

    private Path writeJar(String name, String className, byte[] classBytes) throws Exception {
        Path jarPath = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out)) {
            if (className != null) {
                jar.putNextEntry(new JarEntry(className + ".class"));
                jar.write(classBytes);
                jar.closeEntry();
            }
        }
        return jarPath;
    }

    private Path writeTable(JsonObject classes) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("gameVersion", "0.98a-RC8");
        root.add("classes", classes);
        Path file = tempDir.resolve("string-table.json");
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private TinyMappings writeMappings(String... obfNamedPairs) throws Exception {
        StringBuilder sb = new StringBuilder("tiny\t2\t0\tobf\tnamed\n");
        for (int i = 0; i < obfNamedPairs.length; i += 2) {
            sb.append("c\t").append(obfNamedPairs[i]).append('\t').append(obfNamedPairs[i + 1]).append('\n');
        }
        Path file = tempDir.resolve("test.tiny");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return TinyMappings.parse(file);
    }

    @Test
    void 常量池扫描覆盖ldc与indy参数并跳过long双槽() {
        byte[] classBytes = TestClasses.buildClass("a/T",
                new Object[]{"ldc文本", 42, 1234567890123456789L, 3.14d,
                        org.objectweb.asm.Type.getType("Ljava/lang/Object;"), "尾串"},
                new Object[]{"indy配方", 7});

        Set<String> strings = Auditor.scanConstantPool(classBytes).stringReferenced();

        assertTrue(strings.contains("ldc文本"));
        assertTrue(strings.contains("尾串"));
        assertTrue(strings.contains("indy配方"));
        assertFalse(strings.contains("42"));
        // long/double 双槽跳过未读飞：集合大小恰好为 3 个字符串
        assertEquals(3, strings.size());
    }

    @Test
    void 常量池扫描识别extraRef交集() {
        byte[] classBytes = TestClasses.buildClassWithExtraRef("a/Extra", "d");

        Auditor.ConstantPoolStrings pool = Auditor.scanConstantPool(classBytes);

        assertTrue(pool.stringReferenced().contains("d"));
        assertEquals(Set.of("d"), pool.extraReferenced());
    }

    @Test
    void 原文同时被符号引用时审计失败并打印明细() throws Exception {
        // ldc "d" + invokestatic a/Extra.d()V：UTF8 "d" 同时被 String 与 NameAndType 引用
        Path obfJar = writeJar("obf-extra.jar", "a/Extra",
                TestClasses.buildClassWithExtraRef("a/Extra", "d"));

        JsonObject classTable = new JsonObject();
        classTable.addProperty("d", "译d");
        JsonObject classes = new JsonObject();
        classes.add("a/Extra", classTable);

        Auditor.AuditReport report = new Auditor().audit(
                writeTable(classes), writeMappings(), List.of(), List.of(obfJar));

        assertFalse(report.success());
        assertEquals(0, report.hitTerms());
        assertEquals(1, report.misses().size());
        assertTrue(report.misses().get(0).reason().contains("Class/NameAndType"));
        assertTrue(report.render().contains("拒收"));
    }

    @Test
    void 全部命中时审计通过() throws Exception {
        Path obfJar = writeJar("obf-hit.jar", "a/Hit",
                TestClasses.buildClass("a/Hit", new Object[]{"Hello", "World"}, new Object[]{"配方"}));
        Path namedJar = writeJar("named-hit.jar", "a/Hit",
                TestClasses.buildClass("a/Hit", new Object[]{"Hello"}, new Object[0]));

        JsonObject classTable = new JsonObject();
        classTable.addProperty("Hello", "你好");
        classTable.addProperty("World", "世界");
        classTable.addProperty("配方", "配方译");
        JsonObject classes = new JsonObject();
        classes.add("a/Hit", classTable);

        Auditor.AuditReport report = new Auditor().audit(
                writeTable(classes), writeMappings(), List.of(namedJar), List.of(obfJar));

        assertTrue(report.success());
        assertEquals(3, report.totalTerms());
        assertEquals(3, report.hitTerms());
    }

    @Test
    void 字符串缺失与类缺失逐条列入报告() throws Exception {
        Path obfJar = writeJar("obf-partial.jar", "a/Partial",
                TestClasses.buildClass("a/Partial", new Object[]{"Hello"}, new Object[0]));

        JsonObject partial = new JsonObject();
        partial.addProperty("Hello", "你好");
        partial.addProperty("Missing", "缺失");
        JsonObject ghost = new JsonObject();
        ghost.addProperty("Anything", "任意");
        JsonObject classes = new JsonObject();
        classes.add("a/Partial", partial);
        classes.add("a/Ghost", ghost);

        Auditor.AuditReport report = new Auditor().audit(
                writeTable(classes), writeMappings(), List.of(), List.of(obfJar));

        assertFalse(report.success());
        assertEquals(3, report.totalTerms());
        assertEquals(1, report.hitTerms());
        assertEquals(2, report.misses().size());
        assertEquals(Set.of("a/Ghost"), report.missingClasses());
        String rendered = report.render();
        assertTrue(rendered.contains("Missing"));
        assertTrue(rendered.contains("a/Ghost"));
        assertTrue(rendered.contains("常量池中无此字符串"));
        assertTrue(rendered.contains("类不存在于原始混淆 jar"));
    }

    @Test
    void 已映射类按named反查obf取字符串named缺失仅观测不判负() throws Exception {
        // 映射 om/fs/X → com/fs/named/X；named jar 缺失该类（漂移），纯净 jar 有
        Path namedJar = writeJar("named-empty.jar", null, new byte[0]);
        Path obfJar = writeJar("obf-x.jar", "om/fs/X",
                TestClasses.buildClass("om/fs/X", new Object[]{"Hello"}, new Object[0]));

        JsonObject classTable = new JsonObject();
        classTable.addProperty("Hello", "你好");
        JsonObject classes = new JsonObject();
        classes.add("com/fs/named/X", classTable);

        Auditor.AuditReport report = new Auditor().audit(
                writeTable(classes), writeMappings("om/fs/X", "com/fs/named/X"),
                List.of(namedJar), List.of(obfJar));

        assertTrue(report.success());
        assertEquals(1, report.hitTerms());
        assertEquals(Set.of("com/fs/named/X"), report.namedMissingClasses());
        assertTrue(report.render().contains("漂移观测"));
    }

    @Test
    void 已映射类在纯净jar中按混淆名取不到时判负() throws Exception {
        Path obfJar = writeJar("obf-empty.jar", null, new byte[0]);

        JsonObject classTable = new JsonObject();
        classTable.addProperty("Hello", "你好");
        JsonObject classes = new JsonObject();
        classes.add("com/fs/named/X", classTable);

        Auditor.AuditReport report = new Auditor().audit(
                writeTable(classes), writeMappings("om/fs/X", "com/fs/named/X"),
                List.of(), List.of(obfJar));

        assertFalse(report.success());
        assertEquals(Set.of("com/fs/named/X"), report.missingClasses());
    }
}
