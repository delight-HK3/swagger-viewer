buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.commonmark:commonmark:0.21.0")
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community, 2024.3 기준. 필요시 버전 변경 가능.
        create("IC", "2024.3")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("com.intellij.java")

    }

    // YAML -> JSON 변환용 (IntelliJ 플랫폼에 Jackson이 포함되어 있지만, 명시적으로 추가해 버전 충돌 방지)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.delight-HK3.swagger-viewer"
        name = "Swagger Viewer"
        version = project.version.toString()
        description = providers.provider {
            val markdown = file("docs/marketplaces/jetbrains/en.md").readText()
            val parser = org.commonmark.parser.Parser.builder().build()
            val renderer = org.commonmark.renderer.html.HtmlRenderer.builder().build()
            renderer.render(parser.parse(markdown))
        }.get()
        vendor {
            name = "delight-HK3"
            email = "dabin49140@gmail.com"
            url = "https://github.com/delight-HK3"
        }
        changeNotes = """
            <h3>1.0.1 - Fix compatibility</h3>
            <ul>
                <li>Updated compatibility for the latest IntelliJ IDEA version.</li>
                <li>replace implicit Query.iterator() calls with findAll()/findFirst()</li>
            </ul>
            <h3>1.0.0 - Initial Release</h3>
            <ul>
                <li>Real-time Swagger annotation preview via PSI static analysis (no build or app launch required)</li>
                <li>Supports @Operation, @ApiResponse, @Parameter, @OpenAPIDefinition and more</li>
                <li>OpenAPI YAML/JSON spec file preview in Tool Window</li>
                <li>Works on both IntelliJ IDEA Community and Ultimate</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // sinceBuild(242) 기준 최소 지원 버전
            ide("IC", "2024.2.6")
            // 현재 개발 대상 버전
            ide("IC", "2024.3")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

tasks.runIde {
    // 락 방지 및 캐시 안정성 향상
    jvmArgs("-Didea.is.internal=true", "-Xmx2g")
    systemProperty("idea.gradle.jvmcompat.update", "false")
}