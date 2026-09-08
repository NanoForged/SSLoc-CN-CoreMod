package org.fossic.ssloccn.tablegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** TableGenCli 端到端验证：真实词条文件 + tiny 映射 → 生成表 → 对照真实 jar 审计。 */
class TableGenCliTest {

    @TempDir
    Path tempDir;

    private Path writeTermsFile() throws Exception {
        JsonObject term = new JsonObject();
        term.addProperty("key", "starfarer_obf.jar:om/fs/X.class#\"Hello\"");
        term.addProperty("original", "Hello");
        term.addProperty("translation", "你好");
        term.addProperty("stage", 1);
        term.addProperty("context",
                "版本：0.98-RC8 词条格式：v2\n文件：starfarer_obf.jar\n类：om/fs/X.class\n"
                        + "常量号：0010\n原始数据：\"Hello\"\n译文数据：\"你好\"");
        JsonArray array = new JsonArray();
        array.add(term);
        Path file = tempDir.resolve("starfarer_obf.json");
        Files.writeString(file, array.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private Path writeTinyFile() throws Exception {
        Path file = tempDir.resolve("windows-full.tiny");
        Files.writeString(file,
                "tiny\t2\t0\tobf\tintermediary\tnamed\nc\tom/fs/X\tmid/X\tcom/fs/named/X\n",
                StandardCharsets.UTF_8);
        return file;
    }

    private Path writeGameJar(String jarName, String className, byte[] classBytes) throws Exception {
        Path jarPath = tempDir.resolve(jarName);
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(className + ".class"));
            jar.write(classBytes);
            jar.closeEntry();
        }
        return jarPath;
    }

    @Test
    void 生成并审计全流程() throws Exception {
        Path terms = writeTermsFile();
        Path tiny = writeTinyFile();
        Path out = tempDir.resolve("string-table.json");

        int generateCode = TableGenCli.run(new String[]{
                "generate",
                "--terms=" + terms,
                "--mapping=" + tiny,
                "--out=" + out,
                "--gameVersion=0.98a-RC8",
                "--generatedFrom=TestRepo@abc123"});
        assertEquals(0, generateCode);

        JsonObject table = JsonParser.parseString(
                Files.readString(out, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("0.98a-RC8", table.get("gameVersion").getAsString());
        assertEquals("v2", table.get("termFormat").getAsString());
        assertEquals("TestRepo@abc123", table.get("generatedFrom").getAsString());
        assertEquals(1, table.getAsJsonObject("stats").get("terms").getAsInt());
        assertEquals("你好", table.getAsJsonObject("classes")
                .getAsJsonObject("com/fs/named/X").get("Hello").getAsString());

        Path obfJar = writeGameJar("starfarer_obf.jar", "om/fs/X",
                TestClasses.buildClass("om/fs/X", new Object[]{"Hello"}, new Object[0]));
        Path namedJar = writeGameJar("named.jar", "com/fs/named/X",
                TestClasses.buildClass("com/fs/named/X", new Object[]{"Hello"}, new Object[0]));
        Path reportFile = tempDir.resolve("audit-report.txt");
        int auditCode = TableGenCli.run(new String[]{
                "audit", "--table=" + out, "--mapping=" + tiny,
                "--jars=" + namedJar, "--obfJars=" + obfJar, "--report=" + reportFile});
        assertEquals(0, auditCode);
    }

    @Test
    void 审计未命中返回1且报告含明细() throws Exception {
        Path terms = writeTermsFile();
        Path tiny = writeTinyFile();
        Path out = tempDir.resolve("string-table.json");
        TableGenCli.run(new String[]{"generate", "--terms=" + terms, "--mapping=" + tiny,
                "--out=" + out, "--gameVersion=0.98a-RC8", "--generatedFrom=TestRepo@abc123"});

        // 纯净 jar 中字符串内容已漂移（Changed ≠ Hello）
        Path obfJar = writeGameJar("starfarer_obf.jar", "om/fs/X",
                TestClasses.buildClass("om/fs/X", new Object[]{"Changed"}, new Object[0]));
        Path reportFile = tempDir.resolve("audit-report.txt");
        int auditCode = TableGenCli.run(new String[]{
                "audit", "--table=" + out, "--mapping=" + tiny,
                "--jars=" + obfJar, "--obfJars=" + obfJar, "--report=" + reportFile});

        assertEquals(1, auditCode);
        String report = Files.readString(reportFile, StandardCharsets.UTF_8);
        assert(report.contains("Hello") && report.contains("常量池中无此字符串"));
    }
}
