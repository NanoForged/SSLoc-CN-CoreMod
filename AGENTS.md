# SSLoc-CN-CoreMod 项目规范

Starsector 简体中文汉化 CoreMod（P1 阶段：jar 硬编码字符串运行时替换）。
运行前提：游戏已安装 NanoForge（coremod 加载器）。与 `Starsector-Localization-CN`
的 data 覆盖包互补：覆盖包处理 data/ 资源文本，本 coremod 处理 jar 内硬编码字符串。

## 模块职责

- 根模块（`src/main/java/org/fossic/ssloccn/`）：运行期代码，打进 `build/libs/SSLocCN.jar`。
  - `bootstrap/SSLocCorePlugin`：coremod 入口（`coremod.toml` 的 pluginClass），onLoad
    读取 jar 内 `/ssloc/string-table.json` 并安装进 transformer 静态注册表；表缺失/损坏
    直接抛异常终止启动（无静默兜底）。
  - `bootstrap/StringReplaceTransformer`：唯一 ASM transformer，经 `coremod.toml` 的
    `[asm] transformers` 声明（NanoForge 以无参构造实例化并注册进 LaunchClassLoader 链，
    位于 patch/obf→named remap 之后、Mixin 之前，看到的是 named 字节码）。
  - `table/StringTable` + `table/StringTableImpl`：查表接口与 org.json 实现
    （org.json 用游戏 classpath 自带的老版本，只有 `keys()` 迭代器，没有 `keySet()`）。
  - `weave/LdcStringRewriter`：ASM ClassVisitor 改写 ldc / invokedynamic bootstrap
    参数 / 字段 ConstantValue 三种形态中的字符串常量。
- `tablegen/`：构建期工具模块（不进产物）。词条 JSON + tiny 映射 → string-table.json，
  以及构建期审计（Auditor）。CLI 入口 `TableGenCli`（generate / audit 子命令）。
- `terms/`：词条快照（starfarer_obf.json / starfarer.api.json），来自
  `Starsector-Localization-CN` 仓的 `para_tranz/output/`，来源信息见 `terms/SOURCE.md`。

## 构建命令

- `./gradlew test`：全部单测（根模块 + tablegen，共 39 个）。
- `./gradlew build`：含 `auditStringTable`（挂在 check）与 `overlayZip`（挂在 assemble）。
  产物：`build/libs/SSLocCN.jar`（含 coremod.toml + string-table.json）、
  `build/distributions/SSLocCN-overlay-*.zip`（`mods/coremods/SSLocCN.jar` 结构）。
- 部署到游戏：`-Pstarsector.gameDir=<游戏目录>` 或环境变量 `SSLOC_GAME_DIR`。

## 词条同步流程

1. `tools/sync_terms.sh [本地化仓库路径]`（默认 `../Starsector-Localization-CN`）：
   拷贝词条、重写 `terms/SOURCE.md`、更新 `gradle.properties` 的 `ssloc.termsCommit`。
2. 提交 `terms/` 与 `gradle.properties` 的变更。
3. `./gradlew build` 重新生成并审计 string-table.json。

## 关键事实与口径（改动前必读）

1. **tiny 映射唯一事实源**：`../Paragon/build/mappings/mappings-named.tiny`
   （header `tiny 2 0 obf named`，2932 个类），与 named 游戏 jar 及 NanoForge 运行期
   remap 同源。用 `-Pssloc.mappingFile=` 覆盖。SSOptimizer 的旧 tiny 命名与 named jar
   不符，勿回退。
2. **审计是双层口径**（`auditStringTable`，挂在 check 上，100% 命中才通过）：
   - 类存在性：对照 named jar（`starsector.named:*`，SourceSector named 仓）——
     仅漂移观测，不做字符串真值断言；
   - 字符串真值：对照**纯净混淆 jar**（默认 `../Starsector-Localization-CN/game data/`，
     用 `-Pssloc.obfJarsDir=` 覆盖），经 named→obf 反查。
   - 原因：SourceSector 的 `game-jars/windows/starfarer_obf.jar` 本身是汉化版 jar
     （其 README 明示），named 仓的常量池已是中文，不能做原文真值来源。
   - 当前实测：10797/10797 词条 100% 命中（另有 11 条 extra_ref 危险词条经
     `terms/exclusions.txt` 显式排除，见下条）。
3. **常量池去重冲突**：named jar 常量池经 ASM 重建去重，同一原文在一处出现、
   词条里同值有多条译文时只能取同值序号 0 的译文（当前 11 组），构建期 WARN 并记入
   BuildResult.conflicts，不判失败。
4. **extra_ref 安全防护（与旧管线 jar_loader 对齐）**：词条原文对应的 UTF8 若同时被
   非 String 常量（Class / NameAndType）引用，翻译会连坐改写反射/符号引用路径
   （Class.forName、同名方法调用）。`auditStringTable` 对这类词条 fail-loud；
   已确认的 11 条危险词条收录在 `terms/exclusions.txt`（手工维护，生成期剔除并打印
   排除数），审计再命中清单外条目时把审计明细追加进该文件。**禁止**为此恢复
   "整串类名改 named" 的键改写（旧 remapStringConstant 已删除：named 名必不在
   obf 池，audit 口径矛盾，且 NanoForge remap 只改 ldc 不改 indy，口径不完整）。
5. **RFB 契约**：RFB 的 runTransformers 无条件采纳 transformer 返回值
   （与原版 LaunchWrapper「null=无变更」不同），transformer 任何路径都不得返回 null
   （入参 basicClass==null 除外）。
6. **日志双 API**：运行期日志用 org.apache.log4j（1.2 API，运行时由 NanoForge 部署的
   log4j-over-slf4j 提供）；`CoreModContext.logger()` 是 log4j2 Logger。两者都 compileOnly。
7. **重入防护**：transformer 的 IN_FLIGHT ThreadLocal 防护加在查表之前
   （Mixin 子系统会反读正在处理的类形成重入，SSOptimizer 已实证）。
8. 打包时 jar 排除 `module-info.class`（RFB 按命名模块加载会导致包不可见）。
