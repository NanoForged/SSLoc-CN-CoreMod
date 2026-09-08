# SSLocCN（Starsector 简体中文汉化 CoreMod）

Starsector 0.98a-RC8 的简体中文汉化 coremod，处理 **jar 内硬编码字符串** 的运行时替换（P1 阶段）。

与 [Starsector-Localization-CN](../Starsector-Localization-CN) 的 data 覆盖包组合使用：

- data 覆盖包：翻译 `data/` 目录下的 csv/json/规则文本等资源；
- 本 coremod：翻译编译进 `starfarer_obf.jar` / `starfarer.api.jar` 字节码常量池里的硬编码字符串
  （对话框、UI 提示等覆盖包够不到的文本）。

## 安装前提

游戏已安装 **NanoForge**（coremod 加载器，把 coremod 放进 `mods/coremods/`）。

## 构建

```bash
./gradlew build
```

依赖的同级检出（默认相对路径，可用 gradle 属性覆盖）：

- `../Paragon`（tiny 映射 `build/mappings/mappings-named.tiny`，`-Pssloc.mappingFile=` 覆盖）
- `../Starsector-Localization-CN`（审计用纯净混淆 jar `game data/`，`-Pssloc.obfJarsDir=` 覆盖）
- SourceSector named 仓（由 SDG 插件解析，`-Psourcesector.namedRepo=` 覆盖）

构建产物：

- `build/libs/SSLocCN.jar` —— coremod jar（含 `coremod.toml` 与 `ssloc/string-table.json`）；
- `build/distributions/SSLocCN-overlay-<版本>.zip` —— 发布包，解压到游戏目录后得到
  `mods/coremods/SSLocCN.jar`。

`build` 会连带执行 `auditStringTable`：对照真实游戏字节码逐条断言全部词条
均可被替换，不到 100% 即构建失败，防止游戏更新后词条静默漂移。

## 词条更新

```bash
tools/sync_terms.sh   # 从 ../Starsector-Localization-CN 拉取最新词条快照
./gradlew build       # 重新生成并审计 string-table.json
```

然后提交 `terms/` 与 `gradle.properties` 的变更。

## 工作原理（简述）

1. 构建期：`tablegen` 模块把词条快照（键为 Windows 混淆类名）经 tiny 映射转成 named 类名，
   生成 `string-table.json` 打进 coremod jar；
2. 运行期：NanoForge 按 `coremod.toml` 注册 `StringReplaceTransformer` 进 LaunchClassLoader
   transformer 链（在游戏字节码 remap 为 named 之后执行），对每个待加载类按类名查表，
   用 ASM 改写 ldc / invokedynamic 中的字符串常量。

更详细的模块划分与口径约定见 [AGENTS.md](AGENTS.md)。
