plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    runtimeOnly(project(":assets"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
}

application {
    mainClass.set("io.github.jwyoon1220.builder.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

val fatJar by tasks.registering(Jar::class) {
    group = "build"
    archiveBaseName.set("StelLane-builder")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to application.mainClass.get()) }
    val sourcesMain = sourceSets.main.get()
    from(sourcesMain.output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}
