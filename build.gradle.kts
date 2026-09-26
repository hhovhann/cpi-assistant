plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hhovhann"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

// Spring Boot 4.1.1 manages Lombok 1.18.46, which fails on Java 27 at
// compile time (ClassNotFoundException: com.sun.tools.javac.tree.EndPosTable —
// the JDK removed an internal class Lombok hooks into). 1.18.48 supports 27.
// Drop this override once Spring Boot's managed version catches up.
extra["lombok.version"] = "1.18.48"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // --- LangChain4j (core only, all GA) ------------------------------------
    // Deliberately NOT langchain4j-spring-boot-starter: those starters are
    // built against Spring Boot 3.5 and blow up on Boot 4 with
    // NoClassDefFoundError: .../web/client/RestClientAutoConfiguration.
    // Core artifacts have no Spring dependency, so we wire the beans ourselves
    // in LangChain4jConfig — which is also the point of the exercise.
    implementation(platform("dev.langchain4j:langchain4j-bom:1.20.0"))
    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-open-ai")
    // Claude, when cpi.chat.provider=anthropic. Embeddings stay on LM Studio:
    // Anthropic has no embedding endpoint.
    implementation("dev.langchain4j:langchain4j-anthropic")
    // Pulled in transitively at runtime anyway, but declared so LangChain4jConfig
    // can compile against JdkHttpClientBuilder to force HTTP/1.1 — see the
    // comment on the HttpClientBuilder bean.
    implementation("dev.langchain4j:langchain4j-http-client-jdk")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("eval")
    }
}

// Evaluations need LM Studio running, so they stay out of `test`.
//   ./gradlew eval
//   ./gradlew eval -Dcpi.chat.provider=anthropic   (answers from Claude — paid)
tasks.register<Test>("eval") {
    description = "Runs the evaluations tagged 'eval' against the live models."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("eval")
    }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
    // Gradle runs tests in a separate JVM; pass the provider through to it.
    System.getProperty("cpi.chat.provider")?.let { systemProperty("cpi.chat.provider", it) }
}
