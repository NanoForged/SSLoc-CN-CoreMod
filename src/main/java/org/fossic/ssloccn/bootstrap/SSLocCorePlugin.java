package org.fossic.ssloccn.bootstrap;

import io.github.nanoforged.api.CoreModContext;
import io.github.nanoforged.api.INanoCorePlugin;
import org.fossic.ssloccn.table.StringTable;
import org.fossic.ssloccn.table.StringTableImpl;

import java.io.IOException;
import java.io.InputStream;

/**
 * SSLocCN coremod 入口插件（coremod.toml 的 pluginClass）。
 *
 * <p>职责：装配阶段读取 jar 内 {@value #TABLE_RESOURCE}（构建期由 tablegen 生成），
 * 解析为 {@link StringTable} 并安装进 {@link StringReplaceTransformer} 的静态注册表，
 * 然后输出版本与规模日志。
 *
 * <p>表缺失或格式损坏直接抛异常终止启动——汉化表损坏不应静默进入英文游戏
 * （无兜底规范）。本钩子不触碰任何游戏类（装配期游戏类尚不可安全引用）。
 */
public final class SSLocCorePlugin implements INanoCorePlugin {

    /** 翻译表在 coremod jar 内的资源路径。 */
    public static final String TABLE_RESOURCE = "/ssloc/string-table.json";

    @Override
    public void onLoad(CoreModContext context) {
        InputStream in = SSLocCorePlugin.class.getResourceAsStream(TABLE_RESOURCE);
        if (in == null) {
            throw new IllegalStateException(
                    "SSLocCN 汉化表缺失：" + TABLE_RESOURCE + "（coremod jar 构建产物损坏）");
        }
        StringTable stringTable;
        try (in) {
            stringTable = StringTableImpl.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("SSLocCN 汉化表读取失败：" + TABLE_RESOURCE, e);
        }
        // StringTableImpl.load 抛出的格式错误（IllegalStateException）原样上抛

        StringReplaceTransformer.installTable(stringTable);
        context.logger().info("[{}] v{} 已加载汉化表：游戏版本 {}，{} 个类 / {} 条词条",
                context.meta().id(), context.meta().version(),
                stringTable.gameVersion(), stringTable.classCount(), stringTable.termCount());
    }
}
