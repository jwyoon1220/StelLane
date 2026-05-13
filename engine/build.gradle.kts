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

    // ── LWJGL ──────────────────────────────────────────────────────────────
    val lwjglVersion  = rootProject.extra["lwjglVersion"] as String
    val lwjglNatives  = rootProject.extra["lwjglNatives"] as String

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    // 코어 + GLFW (윈도우/입력) + OpenGL (렌더링 컨텍스트) + NanoVG (2D 드로잉)
    // + STB (이미지/폰트 로딩) + OpenAL (오디오, 선택적)
    // + tinyfd (네이티브 파일 다이얼로그)
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-nanovg")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("org.lwjgl:lwjgl-openal")
    implementation("org.lwjgl:lwjgl-tinyfd")

    // 네이티브 바이너리 (Windows x64)
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

tasks.test {
    useJUnitPlatform()
    // 테스트 워킹 디렉토리를 프로젝트 루트로 설정해 run/songs 경로가 맞게 함
    workingDir = rootProject.projectDir
    // 시스템 VLC가 없을 경우 프로젝트 내 vlc/ 폴더를 경로에 추가
    val vlcDir = rootProject.file("vlc")
    if (vlcDir.exists()) {
        jvmArgs("-Djna.library.path=${vlcDir.absolutePath}")
        environment("PATH", "${vlcDir.absolutePath};${System.getenv("PATH") ?: ""}")
    }
}
