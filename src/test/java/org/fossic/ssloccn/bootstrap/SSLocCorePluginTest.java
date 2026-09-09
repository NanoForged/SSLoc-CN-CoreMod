package org.fossic.ssloccn.bootstrap;

import io.github.nanoforged.api.CoreModContext;
import io.github.nanoforged.api.mapping.MappingResolver;
import io.github.nanoforged.core.meta.CoreModMeta;
import org.apache.logging.log4j.LogManager;
import org.fossic.ssloccn.TestClasses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SSLocCorePlugin 装配验证：真实加载 test classpath 上的 string-table.json fixture，
 * 安装进 transformer 后对探针类做端到端字符串替换。
 *
 * <p>onLoad 内含 LdcStringRewriter.warmup() 预热——本测试同时验证「onLoad 装表后
 * transform 全链路成功执行」（预热生效后改写路径可用）；跨类名重入环路本身无法
 * 在单测复现，机制与防护点见 StringReplaceTransformer 类 javadoc。
 */
class SSLocCorePluginTest {

    @AfterEach
    void tearDown() {
        StringReplaceTransformer.resetForTest();
    }

    /** 空 MappingResolver（本插件不使用映射查询）。 */
    private static MappingResolver emptyMappingResolver() {
        return new MappingResolver() {
            @Override
            public Optional<String> namedClassToObf(String namedInternalName) {
                return Optional.empty();
            }

            @Override
            public Optional<String> obfClassToNamed(String obfInternalName) {
                return Optional.empty();
            }

            @Override
            public Optional<String> namedFieldToObf(String owner, String name) {
                return Optional.empty();
            }

            @Override
            public Optional<String> obfFieldToNamed(String owner, String name) {
                return Optional.empty();
            }

            @Override
            public Optional<String> namedMethodToObf(String owner, String name, String descriptor) {
                return Optional.empty();
            }

            @Override
            public Optional<String> obfMethodToNamed(String owner, String name, String descriptor) {
                return Optional.empty();
            }
        };
    }

    private static CoreModContext testContext() {
        CoreModMeta meta = CoreModMeta.builder()
                .id("ssloccn")
                .name("SSLocCN")
                .version("1.0.0-test")
                .pluginClass("org.fossic.ssloccn.bootstrap.SSLocCorePlugin")
                .source("test")
                .build();
        return new CoreModContext(meta, Path.of("."), Path.of("."), Path.of("."), Path.of("."),
                LogManager.getLogger("ssloccn-test"), emptyMappingResolver());
    }

    @Test
    void onLoad加载表并装配transformer() {
        new SSLocCorePlugin().onLoad(testContext());

        // 装配后 transformer 对 fixture 表中的探针类做真实替换
        byte[] original = TestClasses.buildClass("a/PluginProbe",
                new Object[]{"ProbeText", "未收录"}, new Object[0]);
        byte[] result = new StringReplaceTransformer().transform("a.PluginProbe", "a.PluginProbe", original);

        assertEquals(List.of("探针译文", "未收录"), TestClasses.collectStringConstants(result));
    }
}
