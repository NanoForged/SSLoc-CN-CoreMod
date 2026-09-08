package org.fossic.ssloccn.tablegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


/**
 * 词条 → string-table 的组装器：过滤、类名 obf→named 转换、同值冲突消解、占位符观测。
 *
 * <p>规则（与汉化仓 jar_loader 的写回语义对齐）：
 * <ol>
 *   <li>过滤：stage ∈ {1,3,5,9} 且 translation 非空且 ≠ original；其余计入统计。</li>
 *   <li>类名转换：查 tiny 映射；未混淆类（不在表中）保持原名，最终由 audit 验证存在性。</li>
 *   <li>同值冲突：同类同原文多条译文——预处理 jar 为区分同一字符串的多次出现会复制常量
 *       （同值序号），而 named jar 常量池经 ASM 重建已去重，同值只剩一个常量，运行期
 *       ldc 替换无法按出现次序区分。取同值序号 0（即未预处理 jar 中真实存在的那个常量）
 *       的译文，其余丢弃并记录到 {@link BuildResult#conflicts()}（构建日志 WARN）。</li>
 *   <li>占位符：translation 的 printf 占位符（%s/%d 等）数量与 original 不一致 →
 *       记入 {@link BuildResult#warnings()}（WARN 观测，不阻断）。</li>
 * </ol>
 */
public final class TableBuilder {

    /** 收录的词条 stage 集合：1 已译 / 3 校对 / 5 审核 / 9 锁定。 */
    private static final int[] ACCEPTED_STAGES = {1, 3, 5, 9};

    private final TinyMappings mappings;

    public TableBuilder(TinyMappings mappings) {
        this.mappings = mappings;
    }

    /**
     * 组装词表。
     *
     * @param terms 全部词条（可来自多个词条文件）
     * @return 组装结果（类名与类内条目均按键排序，输出确定）
     */
    public BuildResult build(List<Term> terms) {
        int skippedStage = 0;
        int skippedUntranslated = 0;
        int deduped = 0;
        List<String> warnings = new ArrayList<>();
        List<ConflictRecord> conflicts = new ArrayList<>();

        // namedClass → original → 候选词条（保留全部出现以供冲突消解）
        Map<String, Map<String, List<Term>>> grouped = new TreeMap<>();
        for (Term term : terms) {
            if (!isAcceptedStage(term.stage())) {
                skippedStage++;
                continue;
            }
            if (term.translation().isEmpty() || term.translation().equals(term.original())) {
                skippedUntranslated++;
                continue;
            }
            String namedClass = toNamedClass(term.className());
            grouped.computeIfAbsent(namedClass, k -> new TreeMap<>())
                    .computeIfAbsent(term.original(), k -> new ArrayList<>())
                    .add(term);
        }

        Map<String, Map<String, String>> classes = new TreeMap<>();
        int termCount = 0;
        for (Map.Entry<String, Map<String, List<Term>>> classEntry : grouped.entrySet()) {
            Map<String, String> classTable = new TreeMap<>();
            for (Map.Entry<String, List<Term>> termEntry : classEntry.getValue().entrySet()) {
                List<Term> candidates = termEntry.getValue();
                Term winner = candidates.get(0);
                if (candidates.size() > 1) {
                    long distinct = candidates.stream().map(Term::translation).distinct().count();
                    if (distinct == 1) {
                        deduped += candidates.size() - 1;
                    } else {
                        winner = pickOccurrenceZero(candidates);
                        List<Term> dropped = new ArrayList<>();
                        for (Term candidate : candidates) {
                            if (candidate != winner) {
                                dropped.add(candidate);
                            }
                        }
                        conflicts.add(new ConflictRecord(
                                classEntry.getKey(), termEntry.getKey(), winner, List.copyOf(dropped)));
                        deduped += candidates.size() - 1;
                    }
                }
                checkPlaceholders(winner, classEntry.getKey(), warnings);
                classTable.put(termEntry.getKey(), winner.translation());
                termCount++;
            }
            classes.put(classEntry.getKey(), classTable);
        }

        return new BuildResult(
                classes,
                new TableStats(termCount, classes.size(), skippedUntranslated, skippedStage, deduped),
                List.copyOf(warnings),
                List.copyOf(conflicts));
    }

    /**
     * 冲突消解：取同值序号 0 的词条（对应未预处理 jar 中真实存在的常量）；
     * 无同值序号时退而取常量号最小者（同值序号缺失说明导出端已视为唯一值，排序仍确定）。
     */
    private static Term pickOccurrenceZero(List<Term> candidates) {
        return candidates.stream()
                .min((a, b) -> {
                    int byOccurrence = Integer.compare(
                            a.occurrenceIndex() != null ? a.occurrenceIndex() : Integer.MAX_VALUE,
                            b.occurrenceIndex() != null ? b.occurrenceIndex() : Integer.MAX_VALUE);
                    return byOccurrence != 0
                            ? byOccurrence
                            : Integer.compare(a.constantIndex(), b.constantIndex());
                })
                .orElseThrow();
    }

    /** printf 占位符数量观测：不一致只 WARN（ParaTranz 侧已有格式规范约束）。 */
    private static void checkPlaceholders(Term term, String namedClass, List<String> warnings) {
        int originalCount = countFormatSpecifiers(term.original());
        int translationCount = countFormatSpecifiers(term.translation());
        if (originalCount != translationCount) {
            warnings.add(namedClass + "：占位符数量不一致（原文 " + originalCount
                    + " / 译文 " + translationCount + "）：\"" + term.original() + "\"");
        }
    }

    /** printf 占位符计数：去掉 %% 转义后数 % 个数（WARN 级观测，无需精确解析 flags）。 */
    private static int countFormatSpecifiers(String text) {
        String withoutEscapes = text.replace("%%", "");
        int count = 0;
        for (int i = 0; i < withoutEscapes.length(); i++) {
            if (withoutEscapes.charAt(i) == '%') {
                count++;
            }
        }
        return count;
    }

    private static boolean isAcceptedStage(int stage) {
        for (int accepted : ACCEPTED_STAGES) {
            if (stage == accepted) {
                return true;
            }
        }
        return false;
    }

    private String toNamedClass(String obfClassName) {
        String named = mappings.toNamed(obfClassName);
        return named != null ? named : obfClassName;
    }

    /**
     * 组装结果。
     *
     * @param classes   named 类内部名 →（原文 → 译文），双层均按键排序
     * @param stats     统计
     * @param warnings  占位符数量不一致等观测警告
     * @param conflicts 同值冲突消解记录（胜出/丢弃明细）
     */
    public record BuildResult(
            Map<String, Map<String, String>> classes,
            TableStats stats,
            List<String> warnings,
            List<ConflictRecord> conflicts) {
    }

    /**
     * 构建统计（写入 string-table.json 的 stats 段）。
     *
     * @param terms               最终收录词条数
     * @param classes             涉及类数
     * @param skippedUntranslated 因译文为空或等于原文被跳过的词条数
     * @param skippedStage        因 stage 不在收录集合被跳过的词条数
     * @param deduped             同值重复被去重的词条数（含冲突消解中丢弃的）
     */
    public record TableStats(
            int terms, int classes, int skippedUntranslated, int skippedStage, int deduped) {
    }

    /**
     * 一条同值冲突消解记录。
     *
     * @param className named 类内部名
     * @param original  冲突原文
     * @param winner    被收录的词条（同值序号 0）
     * @param dropped   被丢弃的词条
     */
    public record ConflictRecord(String className, String original, Term winner, List<Term> dropped) {
    }
}
