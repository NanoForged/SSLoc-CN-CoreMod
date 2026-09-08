package org.fossic.ssloccn.tablegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tablegen 命令行入口（Gradle generateStringTable / auditStringTable 任务的执行体）。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code generate --terms=a.json,b.json --mapping=x.tiny --out=string-table.json
 *       --gameVersion=0.98a-RC8 --generatedFrom=<仓库@commit>}</li>
 *   <li>{@code audit --table=string-table.json --mapping=x.tiny --jars=n1.jar,n2.jar
 *       --obfJars=o1.jar,o2.jar --report=report.txt}
 *       （jars=named jar 做类存在性观测，obfJars=纯净混淆 jar 做字符串真值校验）</li>
 * </ul>
 *
 * <p>退出码：0 成功；参数/格式错误抛异常（非 0）；audit 存在未命中返回 1。
 * 统计、警告、冲突消解、审计未命中全部打印到 stdout（Gradle 构建日志可见）。
 */
public final class TableGenCli {

    private TableGenCli() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Exception e) {
            System.err.println("tablegen 失败：" + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
            return;
        }
        System.exit(exitCode);
    }

    /** 可测试的执行入口：返回退出码而不 System.exit。 */
    static int run(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("缺子命令：generate | audit");
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        return switch (command) {
            case "generate" -> generate(options);
            case "audit" -> audit(options);
            default -> throw new IllegalArgumentException("未知子命令：" + command);
        };
    }

    private static int generate(Map<String, String> options) throws IOException {
        List<Path> termsFiles = splitPaths(require(options, "terms"));
        Path mappingFile = Path.of(require(options, "mapping"));
        Path out = Path.of(require(options, "out"));
        String gameVersion = require(options, "gameVersion");
        String generatedFrom = require(options, "generatedFrom");

        TermsReader reader = new TermsReader();
        List<Term> terms = new ArrayList<>();
        for (Path termsFile : termsFiles) {
            List<Term> part = reader.read(termsFile);
            System.out.println("已读取 " + termsFile.getFileName() + "：" + part.size() + " 条");
            terms.addAll(part);
        }

        TinyMappings mappings = TinyMappings.parse(mappingFile);
        System.out.println("tiny 映射：" + mappings.size() + " 个类（" + mappingFile.getFileName() + "）");

        TableBuilder.BuildResult result = new TableBuilder(mappings).build(terms);
        for (String warning : result.warnings()) {
            System.out.println("[WARN] " + warning);
        }
        for (TableBuilder.ConflictRecord conflict : result.conflicts()) {
            StringBuilder sb = new StringBuilder();
            sb.append("[WARN] 同值冲突消解 ").append(conflict.className())
                    .append(" \"").append(conflict.original()).append("\" → 采用「")
                    .append(conflict.winner().translation()).append("」（同值序号 ")
                    .append(conflict.winner().occurrenceIndex()).append("），丢弃：");
            for (Term dropped : conflict.dropped()) {
                sb.append(" [同值序号 ").append(dropped.occurrenceIndex())
                        .append(" →「").append(dropped.translation()).append("」")
                        .append(dropped.sourceFile()).append(']');
            }
            System.out.println(sb);
        }

        new TableWriter().write(out, result, gameVersion, generatedFrom);

        TableBuilder.TableStats stats = result.stats();
        System.out.println("string-table 生成完成：" + out.toAbsolutePath());
        System.out.println("  词条 " + stats.terms() + " 条 / 类 " + stats.classes() + " 个"
                + "，跳过未译 " + stats.skippedUntranslated()
                + "，跳过 stage " + stats.skippedStage()
                + "，同值去重 " + stats.deduped()
                + "，类名表键改写 " + stats.remappedKeys()
                + "，占位符警告 " + result.warnings().size()
                + "，同值冲突 " + result.conflicts().size());
        return 0;
    }

    private static int audit(Map<String, String> options) throws IOException {
        Path tableFile = Path.of(require(options, "table"));
        TinyMappings mappings = TinyMappings.parse(Path.of(require(options, "mapping")));
        List<Path> jars = splitPaths(require(options, "jars"));
        List<Path> obfJars = splitPaths(require(options, "obfJars"));
        Path reportFile = Path.of(require(options, "report"));

        Auditor.AuditReport report = new Auditor().audit(tableFile, mappings, jars, obfJars);
        String rendered = report.render();
        System.out.print(rendered);
        Files.createDirectories(reportFile.toAbsolutePath().getParent());
        Files.writeString(reportFile, rendered, java.nio.charset.StandardCharsets.UTF_8);

        if (!report.success()) {
            System.out.println("审计未通过：存在未命中词条（详见 " + reportFile.toAbsolutePath() + "）");
            return 1;
        }
        System.out.println("审计通过：" + report.hitTerms() + "/" + report.totalTerms() + " 命中（100%）");
        return 0;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("参数必须是 --key=value 形式：" + arg);
            }
            int eq = arg.indexOf('=');
            options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return options;
    }

    private static List<Path> splitPaths(String joined) {
        List<Path> paths = new ArrayList<>();
        for (String part : joined.split(",")) {
            if (!part.isBlank()) {
                paths.add(Path.of(part));
            }
        }
        return paths;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺参数 --" + key);
        }
        return value;
    }
}
