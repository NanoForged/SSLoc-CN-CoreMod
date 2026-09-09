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
    void 字段ConstantValue字符串命中时被替换() {
        byte[] original = TestClasses.buildClass("a/FieldConst",
                new Object[]{"Hello"}, new Object[0],
                new String[]{"Base value for colony size", "保留字段"});

        LdcStringRewriter.RewriteResult result = LdcStringRewriter.rewrite(original,
                Map.of("Base value for colony size", "殖民地规模基准值"));

        assertTrue(result.changed());
        assertEquals(Set.of("Base value for colony size"), result.matchedOriginals());

        List<String> strings = TestClasses.collectStringConstants(result.bytes());
        assertTrue(strings.contains("殖民地规模基准值"));
        assertTrue(strings.contains("保留字段"));
        assertTrue(strings.contains("Hello"));
        assertFalse(strings.contains("Base value for colony size"));
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
    void warmup后改写路径真实可用() {
        // warmup 以自身类字节跑完整 ASM 管线（不命中任何替换），
        // 验证装配期预热可执行且预热后 rewrite 路径真实可用；
        // 「onLoad 后 transform 不再触发新类加载」的并发重入机制无法单测，
        // 防护点与实机根因见 LdcStringRewriter.warmup 与 StringReplaceTransformer 的 javadoc
        LdcStringRewriter.warmup();

        byte[] original = TestClasses.buildClass("a/Warm",
                new Object[]{"Hello"}, new Object[0]);
        LdcStringRewriter.RewriteResult result = LdcStringRewriter.rewrite(original,
                Map.of("Hello", "你好"));

        assertTrue(result.changed());
        assertTrue(TestClasses.collectStringConstants(result.bytes()).contains("你好"));
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
