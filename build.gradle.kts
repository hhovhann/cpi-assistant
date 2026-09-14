plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hhovhann"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // --- Track A: LangChain4j (core only, all GA) ---------------------------
    // Deliberately NOT langchain4j-spring-boot-starter: those starters are
    // built against Spring Boot 3.5 and blow up on Boot 4 with
    // NoClassDefFoundError: .../web/client/RestClientAutoConfiguration.
    // Core artifacts have no Spring dependency, so we wire the beans ourselves
    // in LangChain4jConfig — which is also the point of the exercise.
    implementation(platform("dev.langchain4j:langchain4j-bom:1.20.0"))
    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-open-ai")
    // Pulled in transitively at runtime anyway, but declared so LangChain4jConfig
    // can compile against JdkHttpClientBuilder to force HTTP/1.1 — see the
    // comment on the HttpClientBuilder bean.
    implementation("dev.langchain4j:langchain4j-http-client-jdk")

    // --- Track B: Spring AI (native Boot 4) ---------------------------------
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
