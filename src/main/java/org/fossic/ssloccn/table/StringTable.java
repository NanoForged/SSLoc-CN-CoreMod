package org.fossic.ssloccn.table;

import java.util.Map;

/**
 * 运行期字符串翻译表：named 类 →（原文 → 译文）的只读查询契约。
 *
 * <p>动机：coremod 的 transformer 在游戏类加载期按 named 类名查表做 ldc/indy
 * 字符串替换。表数据在构建期由 tablegen 从 ParaTranz 词条快照 + tiny 映射生成
 * （string-table.json），运行期只做纯查表，不解析 ParaTranz 格式、不做映射。
 *
 * <p>实现由 {@code SSLocCorePlugin.onLoad} 装配进
 * {@code StringReplaceTransformer} 的静态注册表。
 */
public interface StringTable {

    /**
     * 查询指定类的翻译表。
     *
     * @param internalName named 类内部名（{@code /} 分隔）
     * @return 原文 → 译文 映射；该类无词条时返回 null（调用方透传原字节）
     */
    Map<String, String> forClass(String internalName);

    /** 表覆盖的类数量。 */
    int classCount();

    /** 表中词条总数。 */
    int termCount();

    /** 表面向的游戏版本（如 0.98a-RC8），来自构建期元数据。 */
    String gameVersion();
}
