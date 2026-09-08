package org.fossic.ssloccn.tablegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tiny v2 映射表（仅取类映射）：Windows 混淆类名 → named 类名。
 *
 * <p>动机：词条类路径是 Windows 混淆名，而运行时 transformer 看到的是 NanoForge
 * remap 之后的 named 字节码，表必须以 named 名为键。本类解析 SourceSector 发布的
 * 全量 tiny 表（命名空间行 {@code tiny 2 0 obf intermediary named}），只消费
 * {@code c} 行（类映射），字段/方法行跳过。
 *
 * <p>表不存在或格式损坏视为构建错误（fail loud）；单个类查不到映射不视为错误——
 * 未混淆类（如 {@code com/fs/starfarer/StarfarerLauncher}）本就不在表中，由
 * {@link TableBuilder} 保持原名，最终由 audit 对照 named jar 验证存在性。
 */
public final class TinyMappings {

    private final Map<String, String> obfToNamed;
    private final Map<String, String> namedToObf;
    private final int unmappedCount;

    private TinyMappings(Map<String, String> obfToNamed, int unmappedCount) {
        this.obfToNamed = obfToNamed;
        Map<String, String> reverse = new HashMap<>();
        for (Map.Entry<String, String> entry : obfToNamed.entrySet()) {
            reverse.put(entry.getValue(), entry.getKey());
        }
        this.namedToObf = reverse;
        this.unmappedCount = unmappedCount;
    }

    /**
     * 解析 tiny v2 映射文件。
     *
     * @param file tiny 文件路径
     * @throws IOException              读取失败
     * @throws IllegalArgumentException 文件不存在或头部格式不符（缺 obf/named 命名空间）
     */
    public static TinyMappings parse(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("tiny 映射文件不存在：" + file.toAbsolutePath());
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("tiny 映射文件为空：" + file.toAbsolutePath());
        }
        String[] header = lines.get(0).split("\t");
        if (header.length < 3 || !"tiny".equals(header[0]) || !"2".equals(header[1])) {
            throw new IllegalArgumentException("不是 tiny v2 格式（头部不符）：" + lines.get(0));
        }
        int obfColumn = -1;
        int namedColumn = -1;
        // header 列为 tiny/major/minor + 命名空间列表；c 行去掉前 2 列（c + 无 minor），
        // 故命名空间 header 下标 i 对应 c 行下标 i-2
        for (int i = 3; i < header.length; i++) {
            if ("obf".equals(header[i])) {
                obfColumn = i - 2;
            } else if ("named".equals(header[i])) {
                namedColumn = i - 2;
            }
        }
        if (obfColumn < 0 || namedColumn < 0) {
            throw new IllegalArgumentException("tiny 表缺 obf/named 命名空间列：" + lines.get(0));
        }

        Map<String, String> map = new HashMap<>();
        int unmappedCount = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("c\t")) {
                continue;
            }
            String[] columns = line.split("\t");
            if (columns.length <= obfColumn) {
                throw new IllegalArgumentException(
                        "tiny 表第 " + (i + 1) + " 行列数不足：" + line);
            }
            if (columns.length <= namedColumn) {
                // 该类在 named 命名空间无映射（映射工具未命名）：运行期 remap 对其保持
                // 混淆名恒等，这里等同「不在表中」处理（保持原名），并单独计数观测
                unmappedCount++;
                continue;
            }
            map.put(columns[obfColumn], columns[namedColumn]);
        }
        return new TinyMappings(map, unmappedCount);
    }

    /**
     * 查询混淆类名的 named 名。
     *
     * @param obfInternalName 混淆类内部名（{@code /} 分隔）
     * @return named 内部名；不在表中（未混淆类或 named 侧无映射，运行期均保持原名）返回 null
     */
    public String toNamed(String obfInternalName) {
        return obfToNamed.get(obfInternalName);
    }

    /** 映射表中的类数量（不含 named 侧无映射的）。 */
    public int size() {
        return obfToNamed.size();
    }

    /** 存在映射行但 named 列为空的类数量（运行期保持混淆名）。 */
    public int unmappedCount() {
        return unmappedCount;
    }

    /**
     * 反查 named 类名的混淆名（审计用：表键 → 纯净 jar 中的类名）。
     *
     * @param namedInternalName named 类内部名
     * @return 混淆内部名；不在表中（未混淆类，原名即真名）返回 null
     */
    public String toObf(String namedInternalName) {
        return namedToObf.get(namedInternalName);
    }
}
