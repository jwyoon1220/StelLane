plugins {
    kotlin("jvm")
    application
}

val lwjglVersion = rootProject.extra["lwjglVersion"] as String
val lwjglNatives = rootProject.extra["lwjglNatives"] as String
val imguiVersion = "1.86.11"

dependencies {
    implementation(project(":core"))
    runtimeOnly(project(":assets"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("org.yaml:snakeyaml:2.2")

    // ── LWJGL (standalone ImGui window) ─────────────────────────────────────
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-tinyfd")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-tinyfd::$lwjglNatives")

    // ── Dear ImGui ──────────────────────────────────────────────────────────
    implementation("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-windows:$imguiVersion")
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
