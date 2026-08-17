plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":editor"))
    // 메인 메뉴의 "Story Editor" 항목이 게임과 같은 프로세스에서 스토리 에디터(Swing) 창을 띄우기 위한 의존성
    // (story-editor는 core에만 의존하므로 역방향 의존이 생기지 않습니다).
    implementation(project(":story-editor"))
    runtimeOnly(project(":assets")) // 에셋 모듈의 리소스를 포함
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("commons-cli:commons-cli:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.github.jwyoon1220.app.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-XX:+UseZGC")
}

// run을 위한 작업 디렉토리 설정
val runDir = rootProject.file("run")

val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "모든 의존성이 포함된 단일 실행 가능 Jar를 생성합니다."
    
    archiveBaseName.set("StelLane-app")
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

val prepareRunEnv by tasks.registering(Copy::class) {
    group = "execution"
    description = "실행에 필요한 파일들을 run/ 폴더로 복사하고 환경을 구성합니다."
    
    dependsOn(fatJar)
    from(fatJar)
    into(runDir)
    rename { "StelLane-app.jar" }
    
    doLast {
        // assets 리소스 복사 (story/ 는 아래에서 run/story 로 별도 복사하므로 여기서는 제외)
        val assetDir = rootProject.project(":assets").file("src/main/resources")
        if (assetDir.exists()) {
            copy {
                from(assetDir)
                into(file("${runDir}/assets"))
                exclude("story/**")
            }
        }

        // 디폴트 songs 폴더 생성
        val songsDir = file("${runDir}/songs")
        if (!songsDir.exists()) {
            songsDir.mkdirs()
        }

        // story 팩 데이터 복사 — songs와 마찬가지로 SongManager/StoryManager가 <workingDir>/story 를
        // 런타임에 스캔합니다. assets 모듈의 story/ 가 기본 제공 팩(sonata_forgotten)의 원본이며, 유저는
        // run/story 에 새 폴더(<packId>/{pack.json, chapters/, images/, videos/})를 직접 추가하거나
        // 게임 내 "Story Editor" 메뉴로 자기만의 스토리 팩을 만들 수 있습니다.
        val storySrc = rootProject.project(":assets").file("src/main/resources/story")
        val storyDir = file("${runDir}/story")
        if (storySrc.exists()) {
            copy {
                from(storySrc)
                into(storyDir)
            }
        } else if (!storyDir.exists()) {
            storyDir.mkdirs()
        }

        // VLC 동봉 — run/ 폴더를 참고용으로 완전히 구성해두기 위한 복사본.
        // 실제 실행 시 VLC 검색은 runGame 태스크가 설정하는 jna.library.path/PATH가 담당합니다.
        val vlcSrc = rootProject.file("vlc")
        if (vlcSrc.exists()) {
            copy {
                from(vlcSrc)
                into(file("${runDir}/vlc"))
            }
        }
    }
}

// 커스텀 runGame 테스크 생성: run 폴더에서 실행
// -Pvulkan 으로 실험적 Vulkan 백엔드를 켤 수 있습니다: ./gradlew :app:runGame -Pvulkan
tasks.register<JavaExec>("runGame") {
    group = "application"
    description = "prepareRunEnv 수행 후 run/ 디렉토리에서 게임을 실행합니다. -Pvulkan으로 Vulkan 백엔드 사용."
    dependsOn(prepareRunEnv)
    args(listOfNotNull("--debug", "--console", "--vulkan".takeIf { project.hasProperty("vulkan") }))
    jvmArgs("-XX:+UseZGC")

    // 이 태스크가 사용하는 java 실행 파일은 (배포판과 달리) vlc/ 폴더와 같은 곳에 있지 않으므로,
    // vlcj 기본 NativeDiscovery가 찾을 수 있도록 jna.library.path/PATH로 직접 알려줍니다
    // (engine/build.gradle.kts의 test 태스크와 동일한 패턴).
    val vlcDir = rootProject.file("vlc")
    if (vlcDir.exists()) {
        jvmArgs("-Djna.library.path=${vlcDir.absolutePath}")
        environment("PATH", "${vlcDir.absolutePath};${System.getenv("PATH") ?: ""}")
    }

    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runDir
}

// ── jlink: 최소 JRE 생성 ─────────────────────────────────────────────────────
val jlinkJre by tasks.registering(Exec::class) {
    group = "distribution"
    description = "jlink으로 최소 JRE를 생성합니다."

    val javaHome  = System.getProperty("java.home")!!
    val outputDir = layout.buildDirectory.dir("jre").get().asFile

    doFirst { outputDir.deleteRecursively() }

    commandLine(
        "$javaHome/bin/jlink",
        "--strip-debug",
        "--compress=zip-6",
        "--no-header-files",
        "--no-man-pages",
        "--add-modules", "java.desktop,java.logging,java.management,java.naming,java.prefs,jdk.unsupported",
        "--output", outputDir.absolutePath
    )
}

// ── jpackage 스테이징: fat jar만 별도 폴더로 복사 ────────────────────────────
val stageFatJar by tasks.registering(Copy::class) {
    group = "distribution"
    dependsOn(fatJar)
    from(fatJar)
    into(layout.buildDirectory.dir("jpackage-input"))
    rename { "StelLane-app.jar" }
}

// ── jpackage 출력 폴더 정리 (항상 실행, jpackage는 기존 디렉토리를 거부함) ────
val cleanJpackageDir by tasks.registering(Delete::class) {
    group = "distribution"
    delete(rootProject.layout.projectDirectory.dir("dist"))
}

// ── jpackage: 포터블 앱 이미지 생성 ──────────────────────────────────────────
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "jpackage로 포터블 앱 이미지를 생성합니다."
    dependsOn(jlinkJre, stageFatJar, cleanJpackageDir)

    val javaHome  = System.getProperty("java.home")!!
    val jreDir    = layout.buildDirectory.dir("jre").get().asFile
    val inputDir  = layout.buildDirectory.dir("jpackage-input").get().asFile
    val outputDir = rootProject.layout.projectDirectory.dir("dist").asFile   // 루트/dist 로 출력

    commandLine(
        "$javaHome/bin/jpackage",
        "--type",          "app-image",
        "--name",          "StelLane",
        "--input",         inputDir.absolutePath,
        "--main-jar",      "StelLane-app.jar",
        "--main-class",    "io.github.jwyoon1220.app.MainKt",
        "--runtime-image", jreDir.absolutePath,
        "--dest",          outputDir.absolutePath,
        "--java-options",  "-Dfile.encoding=UTF-8",
        "--java-options",  "-XX:+UseZGC"
    )
}

// ── deploy: jpackage 후 VLC DLL + songs 폴더 구성 ────────────────────────────
tasks.register("deploy") {
    group = "distribution"
    description = "앱 이미지 생성 후 VLC DLL 및 run/songs 콘텐츠를 복사하고 배포 폴더를 구성합니다."
    dependsOn("jpackageImage") // jpackageImage 태스크가 플러그인에 의해 정의되어 있으므로 문자열이나 태스크 객체로 지정

    doLast {
        val appDir     = rootProject.layout.projectDirectory.dir("dist/StelLane").asFile
        val vlcSrc     = rootProject.file("vlc")
        val isWindows  = org.gradle.internal.os.OperatingSystem.current().isWindows

        // jpackage app-image 레이아웃은 OS별로 다릅니다:
        //  - Windows: <name>/runtime/bin/javaw.exe,  <name>/app/<name>.jar
        //  - Linux:   <name>/lib/runtime/bin/java,    <name>/lib/app/<name>.jar
        val runtimeBinRel = if (isWindows) "runtime/bin" else "lib/runtime/bin"
        val appJarRel     = if (isWindows) "app/StelLane-app.jar" else "lib/app/StelLane-app.jar"

        // VLC 검색은 vlcj 기본 NativeDiscovery에 맡깁니다(VideoBackground 참고) — jlink 런타임의
        // java/javaw 실행 파일과 같은 폴더에 동봉하면 별도 경로 설정 없이 찾아냅니다:
        //  - Windows: 같은 폴더는 DLL 검색 순서상 최우선으로 탐색됨.
        //  - Linux: 같은 폴더만으로는 부족해서(동적 링커가 실행 파일 폴더를 자동 탐색하지 않음)
        //    아래 런처 스크립트에서 LD_LIBRARY_PATH로 명시적으로 잡아줍니다.
        val vlcDest    = File(appDir, runtimeBinRel)
        val songsSrc   = rootProject.file("run/songs") // 복사할 원본 run/songs 폴더
        val songsDest  = File(appDir, "songs")
        val storySrc   = rootProject.file("run/story") // 복사할 원본 run/story 폴더
        val storyDest  = File(appDir, "story")

        // 1. VLC 동봉 — FFmpeg/OpenAL과 달리 VLC(libvlc)는 클래스패스 자동 추출 대상이 아니라
        //    시스템에 별도 설치가 필요한 서드파티 앱입니다. 안 넣으면 VLC 미설치 PC에서
        //    "VLC 초기화 실패"로 영상 배경이 통째로 빠집니다.
        if (vlcSrc.exists()) {
            copy {
                from(vlcSrc)
                into(vlcDest)
            }
            println("VLC copied from ${vlcSrc.absolutePath} to ${vlcDest.absolutePath}")
        } else {
            println("WARNING: vlc/ folder not found at project root — deployed build will require system-installed VLC.")
        }

        // 2. run/songs 폴더 내의 파일들을 jpackage 앱 이미지 내부의 songs/ 폴더로 복사
        if (songsSrc.exists()) {
            // Gradle의 내장 copy API 사용 (성능 및 대용량 파일 복사에 안정적)
            copy {
                from(songsSrc)
                into(songsDest)
            }
            println("Songs copied from ${songsSrc.absolutePath} to ${songsDest.absolutePath}")
        } else {
            // 원본 run/songs가 없는 경우 빈 디렉터리만 생성
            if (!songsDest.exists()) {
                songsDest.mkdirs()
            }
            println("WARNING: run/songs/ folder not found. Created an empty songs directory.")
        }

        // 3. run/story 폴더 내의 파일들을 jpackage 앱 이미지 내부의 story/ 폴더로 복사 (songs와 동일한 패턴)
        if (storySrc.exists()) {
            copy {
                from(storySrc)
                into(storyDest)
            }
            println("Story copied from ${storySrc.absolutePath} to ${storyDest.absolutePath}")
        } else {
            if (!storyDest.exists()) {
                storyDest.mkdirs()
            }
            println("WARNING: run/story/ folder not found. Created an empty story directory.")
        }

        // 4. 네이티브 런처 실패 환경 대비 폴백 런처 스크립트 생성
        //    (Windows: 일부 PC에서 "Failed to launch JVM" 우회 / Linux: bin/StelLane 대체용)
        if (isWindows) {
            val launchBat = File(appDir, "Launch-StelLane.bat")
            launchBat.writeText(
                """
                @echo off
                setlocal
                set APPDIR=%~dp0
                "%APPDIR%runtime\\bin\\javaw.exe" -XX:+UseZGC -Dfile.encoding=UTF-8 -cp "%APPDIR%app\\StelLane-app.jar" io.github.jwyoon1220.app.MainKt %*
                endlocal
                """.trimIndent()
            )

            val debugBat = File(appDir, "Launch-StelLane-Debug.bat")
            debugBat.writeText(
                """
                @echo off
                setlocal
                set APPDIR=%~dp0
                echo [StelLane] Starting debug launcher...
                "%APPDIR%runtime\\bin\\java.exe" -XX:+UseZGC -Dfile.encoding=UTF-8 -cp "%APPDIR%app\\StelLane-app.jar" io.github.jwyoon1220.app.MainKt --debug --console %*
                echo.
                echo [StelLane] Exit code: %ERRORLEVEL%
                pause
                endlocal
                """.trimIndent()
            )
        } else {
            val d = "\$" // 트리플쿼트 문자열 안에서 셸 변수(${'$'}...)를 리터럴로 넣기 위한 이스케이프

            val launchSh = File(appDir, "Launch-StelLane.sh")
            launchSh.writeText(
                """
                #!/bin/sh
                APPDIR="${d}(cd "${d}(dirname "${d}0")" && pwd)"
                export LD_LIBRARY_PATH="${d}{APPDIR}/$runtimeBinRel:${d}{LD_LIBRARY_PATH}"
                exec "${d}{APPDIR}/$runtimeBinRel/java" -XX:+UseZGC -Dfile.encoding=UTF-8 -cp "${d}{APPDIR}/$appJarRel" io.github.jwyoon1220.app.MainKt "${d}@"
                """.trimIndent() + "\n"
            )
            launchSh.setExecutable(true)

            val debugSh = File(appDir, "Launch-StelLane-Debug.sh")
            debugSh.writeText(
                """
                #!/bin/sh
                APPDIR="${d}(cd "${d}(dirname "${d}0")" && pwd)"
                export LD_LIBRARY_PATH="${d}{APPDIR}/$runtimeBinRel:${d}{LD_LIBRARY_PATH}"
                echo "[StelLane] Starting debug launcher..."
                "${d}{APPDIR}/$runtimeBinRel/java" -XX:+UseZGC -Dfile.encoding=UTF-8 -cp "${d}{APPDIR}/$appJarRel" io.github.jwyoon1220.app.MainKt --debug --console "${d}@"
                status=${d}?
                echo
                echo "[StelLane] Exit code: ${d}{status}"
                """.trimIndent() + "\n"
            )
            debugSh.setExecutable(true)
        }

        println("Deploy complete: ${appDir.absolutePath}")
    }
}