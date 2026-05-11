dependencies {
    implementation(project(":core"))
    implementation("uk.co.caprica:vlcj:4.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.18")
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
