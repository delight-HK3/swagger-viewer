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
version = "1.0.0"

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
            url = "https://github.com/delight-HK3"
        }
        ideaVersion {
            sinceBuild = "242"
            untilBuild.set(null as String?)
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