import io.github.nanoforged.sdg.SdgExtension

plugins {
    java
    id("io.github.nanoforged.sectordevgradle.nanoforge") version "0.1.0-SNAPSHOT"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

repositories {
    // RFB / LaunchWrapper（IClassTransformer 编译期依赖）
    // （mavenLocal / mavenCentral / SourceSector named 仓由 SDG 插件统一注册）
    maven {
        url = uri("https://nexus.gtnewhorizons.com/repository/releases/")
    }
}

// ---- SDG：模组元数据唯一事实源（coremod.toml / mod_info.json 均由此派生） ----
starsector {
    modId.set("ssloccn")
    modName.set("SSLocCN")
    author.set("Hikari_Nova")
    description.set("Starsector 简体中文汉化 CoreMod：jar 硬编码字符串运行时替换（P1）")
    gameVersion.set("0.98a-RC8")
    // 游戏目录：-Pstarsector.gameDir 或 SSLOC_GAME_DIR；空白视为未设置（CI 不部署）
    gameDir.set(layout.dir(providers.gradleProperty("starsector.gameDir")
        .orElse(providers.environmentVariable("SSLOC_GAME_DIR"))
        .filter { it.isNotBlank() }
        .map { file(it) }))
    // SourceSector named 仓路径覆盖（默认取同级 SourceSector 检出）
    providers.gradleProperty("sourcesector.namedRepo").orNull?.let { sourceRepo.set(file(it)) }
}

nanoforge {
    coremod.set(true)
    pluginClass.set("org.fossic.ssloccn.bootstrap.SSLocCorePlugin")
    authors.set(listOf("Hikari_Nova"))
    asmTransformers.set(listOf("org.fossic.ssloccn.bootstrap.StringReplaceTransformer"))
    // 自身类加载不进入 transformer 链（防跨类名重入环路，见 StringReplaceTransformer javadoc）
    asmTransformerExclusions.set(listOf("org.fossic.ssloccn"))
}

dependencies {
    // ---- 运行时由 NanoForge / 游戏 classpath 提供，编译期对齐版本，不 shade ----
    compileOnly("io.github.nanoforged:NanoForge:0.1.0-SNAPSHOT") {
        isTransitive = false
    }
    // RFB 仅需要 LaunchWrapper 的 IClassTransformer，传递依赖与本项目无关
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") {
        isTransitive = false
    }
    compileOnly("org.ow2.asm:asm:9.8")
    // 源码使用 org.apache.log4j（log4j 1.2 API），运行时由 NanoForge 部署的 log4j-over-slf4j 提供
    compileOnly("org.slf4j:log4j-over-slf4j:2.0.17") {
        isTransitive = false
    }
    // INanoCorePlugin/CoreModContext 签名引用 log4j2 Logger（运行时由 NanoForge 提供）
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")
    // org.json（游戏 classpath 自带）经 SDG gameLibraries 挂入 compileOnly

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("io.github.nanoforged:NanoForge:0.1.0-SNAPSHOT") {
        isTransitive = false
    }
    testImplementation("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") {
        isTransitive = false
    }
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("log4j:log4j:1.2.17")
    testImplementation("org.apache.logging.log4j:log4j-api:2.25.2")
    // 游戏自带 org.json 构件（SourceSector 仓 starsector/game 组），测试期与运行期同源
    testImplementation("starsector.game:json:0.98a-RC8-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("SSLocCN")
    archiveVersion.set("")
    archiveClassifier.set("")
    // coremod jar 不携带 module-info（RFB 会按命名模块加载导致包不可见，SSOptimizer 已验证）
    exclude("module-info.class", "META-INF/versions/**")
}

// ---- string-table.json 生成与审计（tablegen CLI） ----

val sdgExt = extensions.getByType(SdgExtension::class.java)

/** tablegen CLI 运行 classpath（构建期工具，不进产物）。 */
val tablegenRuntime = configurations.create("tablegenRuntime") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isVisible = false
}

/** auditStringTable 专用的 named 游戏 jar 解析配置（对照真实字节码逐条断言）。 */
val auditGameJars = configurations.create("auditGameJars") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isVisible = false
    isTransitive = false
}

dependencies {
    tablegenRuntime(project(":tablegen"))
    auditGameJars("starsector.named:starfarer_obf:${sdgExt.gameVersion.get()}-SNAPSHOT")
    auditGameJars("starsector.named:starfarer.api:${sdgExt.gameVersion.get()}-SNAPSHOT")
}

val termsFiles = listOf("starfarer_obf.json", "starfarer.api.json")
    .map { layout.projectDirectory.file("terms/$it") }

/** 读取必填 gradle 属性，缺失时给出属性名与用途说明而非裸 NoSuchElementException。 */
fun requiredProperty(name: String, purpose: String): Provider<String> =
    providers.gradleProperty(name).map { it }.orElse(
        providers.provider {
            throw GradleException(
                "缺少必填 gradle 属性 -P$name（$purpose）。请在 gradle.properties 配置或命令行传入。")
        }
    )

val mappingFile = requiredProperty(
    "ssloc.mappingFile",
    "Windows 全量 tiny 映射路径（obf→named），与 named 游戏 jar 同源，通常指向 Paragon 仓的 build/mappings/mappings-named.tiny"
).map { rootProject.file(it) }
val termsCommit = requiredProperty(
    "ssloc.termsCommit",
    "terms/ 词条快照来源仓的 commit，由 tools/sync_terms.sh 写入"
)
val exclusionsFile = layout.projectDirectory.file("terms/exclusions.txt")
val stringTableFile = layout.buildDirectory.file("generated/resources/ssloc/string-table.json")

val generateStringTable = tasks.register<JavaExec>("generateStringTable") {
    group = "ssloc"
    description = "词条快照 + tiny 映射 → 运行期查表文件 string-table.json（named 类名键）"
    classpath = tablegenRuntime
    mainClass.set("org.fossic.ssloccn.tablegen.TableGenCli")
    inputs.files(termsFiles)
    inputs.files(mappingFile)
    inputs.file(exclusionsFile)
    inputs.property("gameVersion", sdgExt.gameVersion)
    inputs.property("termsCommit", termsCommit)
    outputs.file(stringTableFile)
    doFirst {
        args = listOf(
            "generate",
            "--terms=${termsFiles.joinToString(",") { it.asFile.absolutePath }}",
            "--mapping=${mappingFile.get().absolutePath}",
            "--out=${stringTableFile.get().asFile.absolutePath}",
            "--gameVersion=${sdgExt.gameVersion.get()}",
            "--generatedFrom=Starsector-Localization-CN@${termsCommit.get()}",
            "--exclude=${exclusionsFile.asFile.absolutePath}"
        )
    }
}

val auditReportFile = layout.buildDirectory.file("reports/ssloc/audit-report.txt")

/** 纯净 Windows 混淆 jar 目录（审计字符串真值来源）：默认取词条来源仓的 game data/。 */
val obfJarsDir = providers.gradleProperty("ssloc.obfJarsDir")
    .map { rootProject.file(it) }
    .orElse(rootProject.layout.projectDirectory.dir("../Starsector-Localization-CN/game data").asFile)
val obfJarFiles = obfJarsDir.map { dir ->
    listOf("starfarer_obf.jar", "starfarer.api.jar").map { dir.resolve(it) }
}

val auditStringTable = tasks.register<JavaExec>("auditStringTable") {
    group = "verification"
    description = "审计 string-table.json：类存在性对照 named jar，字符串真值对照纯净混淆 jar（100% 才通过）"
    classpath = tablegenRuntime
    mainClass.set("org.fossic.ssloccn.tablegen.TableGenCli")
    inputs.file(stringTableFile)
    inputs.files(mappingFile)
    inputs.files(auditGameJars)
    inputs.files(obfJarFiles)
    outputs.file(auditReportFile)
    dependsOn(generateStringTable)
    doFirst {
        args = listOf(
            "audit",
            "--table=${stringTableFile.get().asFile.absolutePath}",
            "--mapping=${mappingFile.get().absolutePath}",
            "--jars=${auditGameJars.files.joinToString(",") { it.absolutePath }}",
            "--obfJars=${obfJarFiles.get().joinToString(",") { it.absolutePath }}",
            "--report=${auditReportFile.get().asFile.absolutePath}"
        )
    }
}

tasks.named("check") {
    dependsOn(auditStringTable)
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}
tasks.processResources {
    dependsOn(generateStringTable)
}

// ---- NanoForge overlay 发布 zip：mods/coremods/SSLocCN.jar ----
val overlayZip = tasks.register<Zip>("overlayZip") {
    group = "ssloc"
    description = "打包 NanoForge overlay 发布 zip（mods/coremods/SSLocCN.jar 结构，安装前提：已装 NanoForge）"
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("mods/coremods")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("SSLocCN-overlay-${project.version}.zip")
    entryCompression = ZipEntryCompression.DEFLATED
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("assemble") {
    dependsOn(overlayZip)
}
