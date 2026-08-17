plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
}

application {
    mainClass.set("io.github.jwyoon1220.storyeditor.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "모든 의존성이 포함된 단일 실행 가능 Jar를 생성합니다."

    archiveBaseName.set("StelLane-story-editor")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }

    val sourcesMain = sourceSets.main.get()
    from(sourcesMain.output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
