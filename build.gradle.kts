plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.stocktracker"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    // --- Spring Boot 4.1 ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // NOTE: spring-boot-starter-aop does not exist for Boot 4.1.0 (confirmed 404 on Maven
    // Central — dropped upstream). Not needed here anyway: retry is implemented with
    // resilience4j-retry core (functional, no AOP proxying) rather than a Spring Framework 7
    // native @Retryable annotation whose exact package couldn't be verified. See AlpacaResilience.

    // --- DB ---
    // spring-boot-flyway carries FlywayAutoConfiguration itself — Boot 4.1 split it out of
    // spring-boot-autoconfigure into its own module (same split as spring-boot-health,
    // spring-boot-hibernate). Without it Flyway never runs: no error, no log line, Hibernate's
    // ddl-auto=validate just fails against an empty schema.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Resilience: CORE modules only, used functionally. See SETUP.md §7. ---
    // Retry uses the resilience4j core module (not resilience4j-spring-boot3) for the
    // same reason as rate-limiter/circuit-breaker below: no Spring Framework 7 API
    // surface to verify against, and no risk of the spring-boot4 module's moving target.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.3.0")

    // --- Cache & leader election ---
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.9.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.9.0")

    // --- Metrics ---
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed module artifacts with a "testcontainers-" prefix
    // (was org.testcontainers:postgresql / :junit-jupiter in 1.x). Confirmed against
    // the testcontainers-bom Boot 4.1.0 pins.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.wiremock:wiremock-standalone:3.13.0")
    testImplementation("net.jqwik:jqwik:1.9.2")   // property tests for the alert engine
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.test { useJUnitPlatform() }
