plugins {
    id("com.google.protobuf") version "0.9.4"
}

val ktorVersion = "3.1.3"

dependencies {
    implementation(project(":core"))
    implementation("uk.co.caprica:vlcj:4.8.2")

    // ── Dear ImGui ──────────────────────────────────────────────────────────
    val imguiVersion = "1.86.11"
    api("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-windows:$imguiVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // ── 멀티플레이어: Ktor + Protobuf + UPnP + Jackson ────────────────────
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    // ── 멀티플레이어: Ktor + Protobuf + UPnP ──────────────────────────────
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    api("com.google.protobuf:protobuf-kotlin:4.29.3")
    implementation("org.jupnp:org.jupnp:3.0.2")
    implementation("org.jupnp:org.jupnp.support:3.0.2")

    // ── LWJGL ──────────────────────────────────────────────────────────────
    val lwjglVersion  = rootProject.extra["lwjglVersion"] as String
    val lwjglNatives  = rootProject.extra["lwjglNatives"] as String

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-nanovg")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("org.lwjgl:lwjgl-openal")
    implementation("org.lwjgl:lwjgl-tinyfd")

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-nanovg::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-openal::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-tinyfd::$lwjglNatives")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.29.3" }
    generateProtoTasks { all().forEach { task -> task.builtins { create("kotlin") } } }
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    val vlcDir = rootProject.file("vlc")
    if (vlcDir.exists()) {
        jvmArgs("-Djna.library.path=${vlcDir.absolutePath}")
        environment("PATH", "${vlcDir.absolutePath};${System.getenv("PATH") ?: ""}")
    }
}
