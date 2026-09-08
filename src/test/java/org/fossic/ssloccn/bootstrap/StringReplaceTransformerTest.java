package org.fossic.ssloccn.bootstrap;

import org.fossic.ssloccn.TestClasses;
import org.fossic.ssloccn.table.StringTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** StringReplaceTransformer 契约验证：命中改写、未命中透传、null 契约、异常透传、重入透传。 */
class StringReplaceTransformerTest {

    @AfterEach
    void tearDown() {
        StringReplaceTransformer.resetForTest();
    }

    /** 测试用 Map 支撑表。 */
    private static StringTable mapTable(Map<String, Map<String, String>> data) {
        return new StringTable() {
            @Override
            public Map<String, String> forClass(String internalName) {
                return data.get(internalName);
            }

            @Override
            public int classCount() {
                return data.size();
            }

            @Override
            public int termCount() {
                return data.values().stream().mapToInt(Map::size).sum();
            }

            @Override
            public String gameVersion() {
                return "0.98a-RC8";
            }
        };
    }

    @Test
    void 命中类改写字符串常量() {
        StringReplaceTransformer.installTable(mapTable(Map.of("a/Target", Map.of("Hello", "你好"))));
        byte[] original = TestClasses.buildClass("a/Target", new Object[]{"Hello"}, new Object[0]);

        byte[] result = new StringReplaceTransformer().transform("a.Target", "a.Target", original);

        List<String> strings = TestClasses.collectStringConstants(result);
        assertEquals(List.of("你好"), strings);
    }

    @Test
    void 未命中类透传原数组() {
        StringReplaceTransformer.installTable(mapTable(Map.of("a/Target", Map.of("Hello", "你好"))));
        byte[] original = TestClasses.buildClass("a/Other", new Object[]{"Hello"}, new Object[0]);

        byte[] result = new StringReplaceTransformer().transform("a.Other", "a.Other", original);

        assertSame(original, result);
    }

    @Test
    void basicClass为null时返回null() {
        StringReplaceTransformer.installTable(mapTable(Map.of("a/Target", Map.of("Hello", "你好"))));
        assertNull(new StringReplaceTransformer().transform("a.Target", "a.Target", null));
    }

    @Test
    void 处理器异常时透传原数组() {
        StringReplaceTransformer.installTable(mapTable(Map.of("a/Bad", Map.of("Hello", "你好"))));
        byte[] garbage = {0x01, 0x02, 0x03};

        byte[] result = new StringReplaceTransformer().transform("a.Bad", "a.Bad", garbage);

        assertSame(garbage, result);
    }

    @Test
    void 同类重入时内层透传外层正常改写() {
        byte[] original = TestClasses.buildClass("a/Reentry", new Object[]{"Hello"}, new Object[0]);
        StringReplaceTransformer transformer = new StringReplaceTransformer();
        AtomicReference<byte[]> innerResult = new AtomicReference<>();

        StringTable reentrantTable = new StringTable() {
            @Override
            public Map<String, String> forClass(String internalName) {
                // 外层 transform 查表时模拟重入：同线程同类再次进入 transform
                innerResult.set(transformer.transform("a.Reentry", "a.Reentry", original));
                return Map.of("Hello", "你好");
            }

            @Override
            public int classCount() {
                return 1;
            }

            @Override
            public int termCount() {
                return 1;
            }

            @Override
            public String gameVersion() {
                return "0.98a-RC8";
            }
        };
        StringReplaceTransformer.installTable(reentrantTable);

        byte[] result = transformer.transform("a.Reentry", "a.Reentry", original);

        // 内层重入被 IN_FLIGHT 防护拦截，透传原数组
        assertSame(original, innerResult.get());
        // 外层正常完成改写
        assertEquals(List.of("你好"), TestClasses.collectStringConstants(result));
    }

    @Test
    void 表未安装时透传() {
        byte[] original = TestClasses.buildClass("a/Any", new Object[]{"Hello"}, new Object[0]);

        byte[] result = new StringReplaceTransformer().transform("a.Any", "a.Any", original);

        assertSame(original, result);
    }
}
