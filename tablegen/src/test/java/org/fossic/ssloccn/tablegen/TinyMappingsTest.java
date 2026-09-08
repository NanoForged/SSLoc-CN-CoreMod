package org.fossic.ssloccn.tablegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** TinyMappings 真实解析验证：obf→named 类映射、未收录类返回 null、坏格式报错。 */
class TinyMappingsTest {

    @TempDir
    Path tempDir;

    private Path writeTiny(String content) throws Exception {
        Path file = tempDir.resolve("test.tiny");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void 解析类映射并忽略字段方法行() throws Exception {
        Path file = writeTiny(
                "tiny\t2\t0\tobf\tintermediary\tnamed\n"
                        + "c\tom/fs/graphics/A/D\tom/fs/graphics/A/C_x\tcom/fs/graphics/font/BitmapFontManager\n"
                        + "\tm\tÒ00000\tÒ00000\tgetFont\t(Ljava/lang/String;)Lcom/fs/graphics/A/F;\n"
                        + "\tf\tObject\tObject\tlineTokens\t[Ljava/lang/String;\n"
                        + "c\tom/fs/starfarer/O000\tom/fs/starfarer/C_x\tcom/fs/starfarer/Game\n");

        TinyMappings mappings = TinyMappings.parse(file);

        assertEquals(2, mappings.size());
        assertEquals("com/fs/graphics/font/BitmapFontManager", mappings.toNamed("om/fs/graphics/A/D"));
        assertEquals("com/fs/starfarer/Game", mappings.toNamed("om/fs/starfarer/O000"));
        assertNull(mappings.toNamed("com/fs/starfarer/StarfarerLauncher"));
    }

    @Test
    void 文件不存在直接报错() {
        assertThrows(IllegalArgumentException.class,
                () -> TinyMappings.parse(tempDir.resolve("missing.tiny")));
    }

    @Test
    void 头部格式不符直接报错() throws Exception {
        Path file = writeTiny("tiny\t1\t0\tsource\ttarget\nc\ta\tb\n");
        assertThrows(IllegalArgumentException.class, () -> TinyMappings.parse(file));
    }

    @Test
    void 缺named命名空间列直接报错() throws Exception {
        Path file = writeTiny("tiny\t2\t0\tobf\tintermediary\nc\ta\tb\n");
        assertThrows(IllegalArgumentException.class, () -> TinyMappings.parse(file));
    }
}
