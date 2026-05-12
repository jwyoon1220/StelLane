plugins {
    kotlin("jvm") version "2.1.21" apply false
    java
}

// LWJGL 버전 — 한 곳에서 관리
val lwjglVersion = "3.3.4"
val lwjglNatives = "natives-windows"

allprojects {
    group = "io.github.jwyoon1220"
    version = "B0.1.2"
    
    repositories {
        mavenCentral()
    }
}

// 모든 서브프로젝트에서 extra 프로퍼티를 참조할 수 있도록 설정
extra["lwjglVersion"] = lwjglVersion
extra["lwjglNatives"] = lwjglNatives

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}
