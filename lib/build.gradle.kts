plugins {
    `java-library`
    java
    jacoco
}

jacoco {
    toolVersion = "0.8.10"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)

    reports {
        html.required.set(true)
        xml.required.set(true)
    }

    val fileFilter = listOf("**/*Test*")

    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude(fileFilter) }
            }
        )
    )

    sourceDirectories.setFrom(files("src/main/java"))
    additionalSourceDirs.setFrom(files("src/main/java"))
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.3.1")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    // Inject annotations
    implementation("javax.inject:javax.inject:1")

    // Micrometer — prometheus registry includes micrometer-core transitively
    api("io.micrometer:micrometer-registry-prometheus:1.13.6")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("MetricsLibrary")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
