package org.fossic.ssloccn.tablegen;

/**
 * 单条 ParaTranz 词条的构建期中间表示。
 *
 * <p>动机：词条 JSON 是平台交换格式（含截断 hash 的 key、面向人工核对的 context），
 * 构建期需要的是结构化字段。本 record 是「词条 JSON → string-table.json」流水线的
 * 第一道工序产物，类路径等权威信息已从 context 解析完毕。
 *
 * @param key             词条 key（仅用于诊断输出；含截断 hash，不作为定位依据）
 * @param original        原文字符串（class 常量池中 UTF8 常量的内容）
 * @param translation     译文字符串（可能为空串，表示未翻译）
 * @param stage           ParaTranz 词条状态：0 未译 / 1 已译 / 2 疑问 / 3 校对 / 5 审核 / 9 锁定 / -1 忽略
 * @param className       权威类内部名（`/` 分隔，无 `.class` 后缀，Windows 混淆名），
 *                        取自 context 的「类：」行
 * @param sourceFile      词条来源文件名（如 starfarer_obf.json），用于冲突定位
 * @param constantIndex   context 的「常量号」（原 jar 常量池索引，仅供排序与人工核对）
 * @param occurrenceIndex context 的「同值序号」；同类同原文多常量时区分出现次序，
 *                        唯一出现时为 null
 */
public record Term(
        String key,
        String original,
        String translation,
        int stage,
        String className,
        String sourceFile,
        int constantIndex,
        Integer occurrenceIndex) {
}
