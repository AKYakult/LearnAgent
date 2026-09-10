plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "jin"
version = "0.0.1-SNAPSHOT"
description = "MyAgent"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // 读取 .env 环境变量支持
    implementation("io.github.cdimascio:dotenv-java:3.2.0")

    // LangChain4j BOM 统一物料清单与各子模块
    implementation(platform("dev.langchain4j:langchain4j-bom:1.20.0"))
    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-open-ai")
    implementation("dev.langchain4j:langchain4j-qdrant")
    implementation("dev.langchain4j:langchain4j-ollama")
    implementation("dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 消除 JDK 21 下 Mockito / ByteBuddy 动态加载 Java Agent 以及 CDS 共享警告
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
}
