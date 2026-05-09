plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":editor"))
    runtimeOnly(project(":assets")) // 에셋 모듈의 리소스를 포함
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("commons-cli:commons-cli:1.9.0")
}

application {
    mainClass.set("io.github.jwyoon1220.app.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
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
        // assets 리소스 복사
        val assetDir = rootProject.project(":assets").file("src/main/resources")
        if (assetDir.exists()) {
            copy {
                from(assetDir)
                into(file("${runDir}/assets"))
            }
        }
        
        // 디폴트 songs 폴더 생성
        val songsDir = file("${runDir}/songs")
        if (!songsDir.exists()) {
            songsDir.mkdirs()
        }
    }
}

// 커스텀 runGame 테스크 생성: run 폴더에서 실행
tasks.register<JavaExec>("runGame") {
    group = "application"
    description = "prepareRunEnv 수행 후 run/ 디렉토리에서 게임을 실행합니다."
    dependsOn(prepareRunEnv)
    args("--debug", "--console")

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
    delete(layout.buildDirectory.dir("jpackage"))
}

// ── jpackage: 포터블 앱 이미지 생성 ──────────────────────────────────────────
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "jpackage로 포터블 앱 이미지를 생성합니다."
    dependsOn(jlinkJre, stageFatJar, cleanJpackageDir)

    val javaHome  = System.getProperty("java.home")!!
    val jreDir    = layout.buildDirectory.dir("jre").get().asFile
    val inputDir  = layout.buildDirectory.dir("jpackage-input").get().asFile
    val outputDir = layout.buildDirectory.dir("jpackage").get().asFile

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
        "--java-options",  "-Djna.library.path=\$APPDIR/vlc"
    )
}

// ── deploy: jpackage 후 VLC DLL + songs 폴더 구성 ────────────────────────────
tasks.register("deploy") {
    group = "distribution"
    description = "앱 이미지 생성 후 VLC DLL 및 run/songs 콘텐츠를 복사하고 배포 폴더를 구성합니다."
    dependsOn("jpackageImage") // jpackageImage 태스크가 플러그인에 의해 정의되어 있으므로 문자열이나 태스크 객체로 지정

    doLast {
        val appDir    = layout.buildDirectory.dir("jpackage/StelLane").get().asFile
        val vlcSrc    = rootProject.file("vlc")
        val vlcDest   = File(appDir, "vlc")
        val songsSrc  = rootProject.file("run/songs") // 복사할 원본 run/songs 폴더
        val songsDest = File(appDir, "songs")

        // 1. VLC DLL 복사
        if (vlcSrc.exists()) {
            vlcDest.mkdirs()
            vlcSrc.walkTopDown().forEach { file ->
                val rel    = file.relativeTo(vlcSrc)
                val target = File(vlcDest, rel.path)
                if (file.isDirectory) target.mkdirs()
                else file.copyTo(target, overwrite = true)
            }
            println("VLC DLLs copied to ${vlcDest.absolutePath}")
        } else {
            println("WARNING: vlc/ folder not found at ${vlcSrc.absolutePath}. Place VLC DLLs there before deploying.")
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

        println("Deploy complete: ${appDir.absolutePath}")
    }
}