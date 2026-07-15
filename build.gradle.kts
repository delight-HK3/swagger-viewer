plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community, 2024.2 기준. 필요시 버전 변경 가능.
        create("IC", "2024.3")

        // JCEF, YAML 플러그인 의존성을 사용하려면 bundledPlugin 추가
        bundledPlugin("org.jetbrains.plugins.yaml")
        // Java PSI (AnnotatedElementsSearch 등) - Spring Boot 어노테이션 스캔에 필요
        bundledPlugin("com.intellij.java")

        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // YAML -> JSON 변환용 (IntelliJ 플랫폼에 Jackson이 포함되어 있지만, 명시적으로 추가해 버전 충돌 방지)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        description = providers.fileContents(layout.projectDirectory.file("docs/marketplaces/jetbrains/en.md")).asText
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "243.*"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
