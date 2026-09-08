package org.fossic.ssloccn.weave;

import org.fossic.ssloccn.TestClasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LdcStringRewriter 真实字节码改写验证（ASM 织入 → 改写 → ASM 回读断言）。 */
class LdcStringRewriterTest {

    @Test
    void 命中ldc与indy字符串被替换其余常量原样() {
        byte[] original = TestClasses.buildClass("a/Target",
                new Object[]{"Hello", "World", "Untouched", 42, 1234567890123456789L, 3.14d,
                        Type.getType("Ljava/lang/Object;")},
                new Object[]{"recipe-部分", 7, "indy保留"});

        LdcStringRewriter.RewriteResult result = LdcStringRewriter.rewrite(original,
                Map.of("Hello", "你好", "World", "世界", "recipe-部分", "配方"));

        assertTrue(result.changed());
        assertEquals(Set.of("Hello", "World", "recipe-部分"), result.matchedOriginals());

        List<String> strings = TestClasses.collectStringConstants(result.bytes());
        assertTrue(strings.contains("你好"));
        assertTrue(strings.contains("世界"));
        assertTrue(strings.contains("配方"));
        assertTrue(strings.contains("Untouched"));
        assertTrue(strings.contains("indy保留"));
        assertFalse(strings.contains("Hello"));
        assertFalse(strings.contains("World"));
        assertFalse(strings.contains("recipe-部分"));
    }

    @Test
    void 未命中时透传原数组且changed为false() {
        byte[] original = TestClasses.buildClass("a/NoHit",
                new Object[]{"Hello"}, new Object[0]);

        LdcStringRewriter.RewriteResult result = LdcStringRewriter.rewrite(original,
                Map.of("Other", "其他"));

        assertFalse(result.changed());
        assertSame(original, result.bytes());
        assertTrue(result.matchedOriginals().isEmpty());
    }

    @Test
    void 空替换表直接透传() {
        byte[] original = TestClasses.buildClass("a/Empty",
                new Object[]{"Hello"}, new Object[0]);

        LdcStringRewriter.RewriteResult result = LdcStringRewriter.rewrite(original, Map.of());

        assertFalse(result.changed());
        assertSame(original, result.bytes());
    }
}
