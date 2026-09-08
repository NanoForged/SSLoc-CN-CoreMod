plugins {
    java
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
    mavenCentral()
}

dependencies {
    // 表生成期 JSON（写出需保持插入序，Gson JsonObject 满足）；运行期解析在宿主模块用游戏自带 org.json
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    // 审计测试现场织入含已知字符串常量的类
    testImplementation("org.ow2.asm:asm:9.8")
}

tasks.test {
    useJUnitPlatform()
}
