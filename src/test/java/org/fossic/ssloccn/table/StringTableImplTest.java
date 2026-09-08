package org.fossic.ssloccn.table;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** StringTableImpl 真实解析验证：查表、统计、格式损坏报错。 */
class StringTableImplTest {

    private static StringTable load(String json) throws Exception {
        return StringTableImpl.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 正常表解析可查表与统计() throws Exception {
        StringTable table = load("""
                {"gameVersion":"0.98a-RC8","termFormat":"v2","generatedFrom":"test@abc",
                 "stats":{"terms":2,"classes":1},
                 "classes":{"a/B":{"Hello":"你好","World":"世界"},"c/D":{"x":"y"}}}
                """);

        assertEquals(Map.of("Hello", "你好", "World", "世界"), table.forClass("a/B"));
        assertEquals(Map.of("x", "y"), table.forClass("c/D"));
        assertNull(table.forClass("a/Unknown"));
        assertEquals(2, table.classCount());
        assertEquals(3, table.termCount());
        assertEquals("0.98a-RC8", table.gameVersion());
    }

    @Test
    void 缺gameVersion直接报错() {
        assertThrows(IllegalStateException.class,
                () -> load("{\"termFormat\":\"v2\",\"classes\":{}}"));
    }

    @Test
    void termFormat版本不符直接报错() {
        assertThrows(IllegalStateException.class,
                () -> load("{\"gameVersion\":\"0.98a-RC8\",\"termFormat\":\"v1\",\"classes\":{}}"));
    }

    @Test
    void 缺classes段直接报错() {
        assertThrows(IllegalStateException.class,
                () -> load("{\"gameVersion\":\"0.98a-RC8\",\"termFormat\":\"v2\"}"));
    }

    @Test
    void 译文不是字符串直接报错() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> load("{\"gameVersion\":\"0.98a-RC8\",\"termFormat\":\"v2\","
                        + "\"classes\":{\"a/B\":{\"Hello\":123}}}"));
        assertTrue(e.getMessage().contains("a/B"));
    }

    @Test
    void 非法JSON直接报错() {
        assertThrows(IllegalStateException.class, () -> load("not json"));
    }
}
