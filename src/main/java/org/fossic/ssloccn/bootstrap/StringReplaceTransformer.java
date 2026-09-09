package org.fossic.ssloccn.bootstrap;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.log4j.Logger;
import org.fossic.ssloccn.table.StringTable;
import org.fossic.ssloccn.weave.LdcStringRewriter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 字符串替换变换器：coremod 的 ASM 分发 transformer（唯一）。
 * <p>
 * 经 {@code coremod.toml} 的 {@code [asm] transformers} 声明，由 LaunchWrapper 以无参构造
 * 实例化并注册进 LaunchClassLoader transformer 链（排在 NanoForge 的 patch / obf→named remap
 * 之后、Mixin 之前）。游戏类加载到这里时已是 named 字节码，故只做纯 named 类名查表。
 * <p>
 * 翻译表为静态：{@code SSLocCorePlugin.onLoad} 在装配阶段写入（transformer 实例化
 * 早于 onLoad，但 {@link #transform} 只在更晚的游戏类加载时执行，懒读静态表是安全时序，
 * 与 SSOptimizer HybridWeaverTransformer 同款已验证模式）。类名统一 JVM 内部格式
 * （{@code /} 分隔）。
 * <p>
 * <b>自身类加载环路防护（实机实证，2026-09-09）</b>：transform() 首次引用
 * {@code LdcStringRewriter} 会经 LaunchClassLoader 加载它，穿过 transformer 链时 Mixin
 * select/prepare 阶段反读游戏类字节形成跨类名重入，再次引用尚在加载途中的
 * {@code LdcStringRewriter} → ClassCircularityError（IN_FLIGHT 按游戏类名防护挡不住这种
 * 重入）。两道防线：
 * <ol>
 *   <li>{@code SSLocCorePlugin.onLoad} 调用 {@code LdcStringRewriter.warmup()} 在装配期
 *       （非 transformer 链上下文）完成全部协作者类加载——transform() 执行时不再触发
 *       任何 org.fossic.ssloccn 类的新加载；</li>
 *   <li>{@code coremod.toml} 的 {@code [asm] transformerExclusions} 排除
 *       {@code org.fossic.ssloccn} 包——自身类加载不再进入 transformer 链。</li>
 * </ol>
 * 维护约束：表未安装（onLoad 前）的早退路径不得引用 {@code LdcStringRewriter}
 * （onLoad 前已有对其他 coremod 元类的 transform 调用，靠 table==null 早退透传）。
 */
public final class StringReplaceTransformer implements IClassTransformer {
    private static final Logger LOGGER = Logger.getLogger(StringReplaceTransformer.class);

    /** 翻译表，onLoad 写入、transform 懒读。 */
    private static volatile StringTable table;

    /** table 未安装告警只发一次（装配失败会在 onLoad 抛错终止，此分支仅为防御）。 */
    private static final AtomicBoolean NO_TABLE_WARNED = new AtomicBoolean();

    /**
     * 正在处理中的类名（按线程）。防护场景：改写逻辑涉及的辅助类在执行期被懒加载，
     * 其加载会穿过 Mixin 子系统反读同一个游戏类的字节，形成重入并以
     * ClassCircularityError 收场（SSOptimizer 运行时已验证的模式）。重入时透传原字节：
     * Mixin 侧只是读取分析，用未处理字节无害；真正的定义期变换由外层调用完成。
     */
    private static final ThreadLocal<Set<String>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

    /**
     * LaunchWrapper 无参构造实例化入口。表读写全部走静态方法，实例本身不持有状态。
     */
    public StringReplaceTransformer() {
    }

    /**
     * 安装翻译表（由 {@code SSLocCorePlugin.onLoad} 调用，装配期单线程写入）。
     *
     * @param stringTable 解析完毕的翻译表
     */
    public static void installTable(StringTable stringTable) {
        table = stringTable;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>RFB 契约警告</b>：RFB 的 {@code runTransformers} 无条件采纳返回值
     * （{@code basicClass = newKlass}），返回 {@code null} 会把类字节丢弃——与原版
     * LaunchWrapper「null = 无变更」的契约不同。故未命中/未替换/异常时都必须返回原字节。
     */
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        String className = transformedName != null ? transformedName : name;
        if (className == null) {
            return basicClass;
        }
        String key = className.replace('.', '/');

        Set<String> inFlight = IN_FLIGHT.get();
        if (!inFlight.add(key)) {
            // 同类重入（见 IN_FLIGHT 注释）：透传未处理字节
            return basicClass;
        }
        try {
            StringTable currentTable = table;
            if (currentTable == null) {
                if (NO_TABLE_WARNED.compareAndSet(false, true)) {
                    LOGGER.warn("[SSLocCN] 翻译表未安装（onLoad 尚未执行），类 " + key + " 透传");
                }
                return basicClass;
            }
            Map<String, String> classTable = currentTable.forClass(key);
            if (classTable == null || classTable.isEmpty()) {
                return basicClass;
            }

            LdcStringRewriter.RewriteResult result = LdcStringRewriter.rewrite(basicClass, classTable);
            // 版本漂移探针：类在表中但有词条未命中任何 ldc/indy —— 游戏更新导致字符串
            // 漂移时此处给出精确清单（构建期 audit 是第一道防线，此处是运行期兜底观测）
            Set<String> unmatched = new TreeSet<>(classTable.keySet());
            unmatched.removeAll(result.matchedOriginals());
            if (!unmatched.isEmpty()) {
                LOGGER.warn("[SSLocCN] 类 " + key + " 有 " + unmatched.size()
                        + " 条词条未命中任何字符串常量（游戏版本可能已漂移）：" + unmatched);
            }
            if (!result.changed()) {
                return basicClass;
            }
            LOGGER.debug("[SSLocCN] 已替换字符串常量：" + key + "（" + result.matchedOriginals().size() + " 条）");
            return result.bytes();
        } catch (Throwable t) {
            LOGGER.error("[SSLocCN] 字符串替换失败：" + key, t);
            return basicClass;
        } finally {
            inFlight.remove(key);
        }
    }

    /** 测试专用：卸载翻译表，恢复 onLoad 前状态。 */
    static void resetForTest() {
        table = null;
        NO_TABLE_WARNED.set(false);
    }
}
