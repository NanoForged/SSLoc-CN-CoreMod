package org.fossic.ssloccn.tablegen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * string-table.json 的构建期审计器：以「运行期真实视图」为准逐条断言可命中。
 *
 * <p>动机：这是游戏版本漂移的核心防线——游戏更新后重跑 tablegen + audit，
 * 未命中清单直接指出哪些词条失效（类被删/改名、字符串内容变化），
 * 而不是等玩家进游戏才发现某界面回到英文。
 *
 * <p>审计口径（为什么是两个 jar 来源）：
 * <ul>
 *   <li>字符串内容：对照<strong>纯净 Windows 混淆 jar</strong>（经映射 named→obf 反查
 *       取类）。运行期加载的是玩家游戏目录的纯净 jar，remap 只改类/成员名与
 *       "整串恰为类名"的字符串常量（表键已在构建期同步改写），其余字符串原样，
 *       故纯净 jar 的常量池就是运行期真值。named jar 不能用于字符串校验——
 *       其 starfarer_obf 由汉化版 jar 构建（SourceSector game-jars README 明示），
 *       常量池已是中文。</li>
 *   <li>类存在性：named jar 侧做漂移观测（named 类应存在；缺失记入
 *       {@link AuditReport#namedMissingClasses()} 但不判失败——named jar 仅是
 *       开发期编译目标，运行期类名由"纯净 jar + 映射"推导）。</li>
 *   <li>判定口径与 jar_loader 提取口径一致：原文必须出现在该类「被 CONSTANT_String
 *       引用的 UTF8 常量」集合中（ldc 字面量与 indy bootstrap 字符串参数都是
 *       CONSTANT_String，一次扫描全覆盖；注解字符串本就不翻译）。</li>
 * </ul>
 * 命中率必须 100%，任何未命中逐条列入报告并使审计失败。
 */
public final class Auditor {

    /**
     * 执行审计。
     *
     * @param tableFile string-table.json
     * @param mappings  tiny 映射（named→obf 反查用）
     * @param namedJars named 游戏 jar（类存在性漂移观测）
     * @param obfJars   纯净 Windows 混淆 jar（字符串内容真值来源）
     * @return 审计报告（{@link AuditReport#success()} 为 false 即存在未命中）
     * @throws IOException 读取失败
     */
    public AuditReport audit(Path tableFile, TinyMappings mappings,
                             List<Path> namedJars, List<Path> obfJars) throws IOException {
        JsonObject table = readTable(tableFile);
        Map<String, ClassLocation> namedIndex = indexClasses(namedJars);
        Map<String, ClassLocation> obfIndex = indexClasses(obfJars);

        int totalTerms = 0;
        int hitTerms = 0;
        List<Miss> misses = new ArrayList<>();
        Set<String> missingClasses = new TreeSet<>();
        Set<String> namedMissingClasses = new TreeSet<>();

        JsonObject classes = table.getAsJsonObject("classes");
        for (Map.Entry<String, JsonElement> classEntry : classes.entrySet()) {
            String className = classEntry.getKey();
            List<String> originals = new ArrayList<>();
            for (Map.Entry<String, JsonElement> termEntry : classEntry.getValue().getAsJsonObject().entrySet()) {
                originals.add(termEntry.getKey());
            }
            totalTerms += originals.size();

            String obfName = mappings.toObf(className);
            if (obfName == null) {
                // 映射中无记录：未混淆类（api 类等）或未命名类，运行期保持原名
                obfName = className;
            } else if (!namedIndex.containsKey(className)) {
                namedMissingClasses.add(className);
            }

            ClassLocation location = obfIndex.get(obfName);
            if (location == null) {
                missingClasses.add(className);
                for (String original : originals) {
                    misses.add(new Miss(className, original, "类不存在于原始混淆 jar"));
                }
                continue;
            }

            Set<String> constantStrings = constantStrings(location.readBytes());
            for (String original : originals) {
                if (constantStrings.contains(original)) {
                    hitTerms++;
                } else {
                    misses.add(new Miss(className, original, "常量池中无此字符串"));
                }
            }
        }
        return new AuditReport(totalTerms, hitTerms, List.copyOf(misses),
                missingClasses, namedMissingClasses);
    }

    /**
     * 提取类字节码中全部被 CONSTANT_String 引用的字符串值。
     *
     * <p>手工解析常量池而非走 ASM 指令遍历：CONSTANT_String 一项即同时覆盖 ldc 字面量
     * 与 invokedynamic bootstrap 字符串参数，且池级口径与 jar_loader 的提取口径一致
     * （ASM {@code ClassReader.readConst} 遇 Fieldref/NameAndType 等引用型常量会抛
     * IllegalArgumentException，无法直接全池遍历）。
     *
     * <p>UTF8 解码说明：class 文件使用 MUTF-8，与标准 UTF-8 仅在 NUL 与非 BMP 字符
     * （增补平面）上编码不同；词条原文已验证不含这两类字符（构建期全量检查），
     * 故直接按 UTF-8 解码是精确的。
     */
    static Set<String> constantStrings(byte[] bytes) {
        if (bytes.length < 10 || readU2(bytes, 0) != 0xCAFE) {
            throw new IllegalArgumentException("不是有效的 class 文件（magic 不符）");
        }
        int constantCount = readU2(bytes, 8);
        String[] utf8ByIndex = new String[constantCount];
        List<Integer> stringUtf8Indexes = new ArrayList<>();

        int offset = 10;
        for (int i = 1; i < constantCount; i++) {
            int tag = bytes[offset] & 0xFF;
            switch (tag) {
                case 1 -> { // Utf8
                    int length = readU2(bytes, offset + 1);
                    utf8ByIndex[i] = new String(bytes, offset + 3, length, StandardCharsets.UTF_8);
                    offset += 3 + length;
                }
                case 7, 16, 19, 20 -> offset += 3; // Class / MethodType / Module / Package
                case 8 -> { // String
                    stringUtf8Indexes.add(readU2(bytes, offset + 1));
                    offset += 3;
                }
                case 3, 4 -> offset += 5; // Integer / Float
                case 5, 6 -> { // Long / Double（占两槽）
                    offset += 9;
                    i++;
                }
                case 9, 10, 11, 12, 18 -> offset += 5; // 引用型 / NameAndType / InvokeDynamic
                case 15 -> offset += 4; // MethodHandle
                case 17 -> offset += 5; // Dynamic
                default -> throw new IllegalArgumentException("未知常量池 tag " + tag + "（常量号 " + i + "）");
            }
        }

        Set<String> strings = new HashSet<>();
        for (int utf8Index : stringUtf8Indexes) {
            String value = utf8ByIndex[utf8Index];
            if (value == null) {
                throw new IllegalArgumentException("CONSTANT_String 引用了不存在的 UTF8 常量：" + utf8Index);
            }
            strings.add(value);
        }
        return strings;
    }

    private static int readU2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static JsonObject readTable(Path tableFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(tableFile, StandardCharsets.UTF_8)) {
            JsonObject table = JsonParser.parseReader(reader).getAsJsonObject();
            if (!table.has("classes") || !table.get("classes").isJsonObject()) {
                throw new IllegalArgumentException("string-table.json 缺 classes 段：" + tableFile);
            }
            return table;
        }
    }

    /** 建立类内部名 → jar 条目 的索引；同名类后者覆盖（同一 jar 集合内不会出现重名类）。 */
    private static Map<String, ClassLocation> indexClasses(List<Path> jarFiles) throws IOException {
        Map<String, ClassLocation> index = new HashMap<>();
        for (Path jarFile : jarFiles) {
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class")) {
                        index.put(name.substring(0, name.length() - ".class".length()),
                                new ClassLocation(jarFile, name));
                    }
                }
            }
        }
        return index;
    }

    /** 类条目定位：jar 路径 + 条目名，读取时按需开 jar。 */
    private record ClassLocation(Path jarFile, String entryName) {
        byte[] readBytes() throws IOException {
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                JarEntry entry = jar.getJarEntry(entryName);
                return jar.getInputStream(entry).readAllBytes();
            }
        }
    }

    /**
     * 一条未命中记录。
     *
     * @param className 表中的类名（named 内部名）
     * @param original  未命中原文
     * @param reason    原因
     */
    public record Miss(String className, String original, String reason) {
    }

    /**
     * 审计报告。
     *
     * @param totalTerms          表中词条总数
     * @param hitTerms            命中数
     * @param misses              未命中明细（失败判定的唯一依据）
     * @param missingClasses      原始混淆 jar 中不存在的类
     * @param namedMissingClasses named jar 中不存在的已映射类（named jar 构建漂移观测，不判失败）
     */
    public record AuditReport(
            int totalTerms, int hitTerms, List<Miss> misses,
            Set<String> missingClasses, Set<String> namedMissingClasses) {

        /** 100% 命中（无任何未命中）才算通过。 */
        public boolean success() {
            return misses.isEmpty();
        }

        /** 渲染为文本报告（写入 reports 目录并打印）。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("string-table 审计报告\n");
            sb.append("词条总数：").append(totalTerms)
                    .append("，命中：").append(hitTerms)
                    .append("，未命中：").append(misses.size()).append('\n');
            if (!namedMissingClasses.isEmpty()) {
                sb.append("named jar 缺失的已映射类（漂移观测，不判失败，").append(namedMissingClasses.size())
                        .append(" 个）：\n");
                for (String className : namedMissingClasses) {
                    sb.append("  ").append(className).append('\n');
                }
            }
            if (!missingClasses.isEmpty()) {
                sb.append("缺失类（").append(missingClasses.size()).append("）：\n");
                for (String className : missingClasses) {
                    sb.append("  ").append(className).append('\n');
                }
            }
            if (!misses.isEmpty()) {
                sb.append("未命中明细：\n");
                for (Miss miss : misses) {
                    sb.append("  [").append(miss.reason()).append("] ")
                            .append(miss.className()).append(" : \"")
                            .append(miss.original()).append("\"\n");
                }
            }
            return sb.toString();
        }
    }
}
