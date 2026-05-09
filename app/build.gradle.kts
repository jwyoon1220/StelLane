plugins {
    kotlin("jvm")
    application
    id("org.beryx.runtime")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":editor"))
    runtimeOnly(project(":assets")) // 에셋 모듈의 리소스를 포함
    implementation("it.unimi.dsi:fastutil:8.5.15")
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
    
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runDir
}

// EXE 배포를 위한 badass-runtime-plugin 설정
runtime {
    options.set(listOf("--strip-debug", "--compress=zip-6", "--no-header-files", "--no-man-pages"))
    modules.set(listOf("java.desktop", "java.logging", "jdk.unsupported"))
    
    jpackage {
        imageName = "StelLane"
        skipInstaller = true // 압축 해제 후 바로 실행 가능한 형태 (포터블)
        targetPlatformName = "win"
        // VLC 네이티브 DLL 경로 지정 (배포 폴더 내 vlc/ 서브폴더)
        jvmArgs = listOf("-Dfile.encoding=UTF-8", "-Djna.library.path=\$APPDIR/vlc")
        // 필요시 icon 추가: icon = "src/main/resources/icon.ico"
    }
}

// deploy: jpackage 후 VLC DLL을 결과물 폴더에 복사
tasks.register("deploy") {
    group = "distribution"
    description = "jpackage 후 VLC DLL을 app/build/jpackage/StelLane/vlc/ 에 복사합니다."
    dependsOn("jpackage")
    doLast {
        val jpackageDir = layout.buildDirectory.dir("jpackage/StelLane").get().asFile
        val vlcSrc = rootProject.file("vlc")
        val vlcDest = File(jpackageDir, "vlc")
        if (vlcSrc.exists()) {
            vlcDest.mkdirs()
            vlcSrc.walkTopDown().forEach { file ->
                val rel = file.relativeTo(vlcSrc)
                val target = File(vlcDest, rel.path)
                if (file.isDirectory) target.mkdirs()
                else file.copyTo(target, overwrite = true)
            }
            println("VLC DLLs copied to ${vlcDest.absolutePath}")
        } else {
            println("WARNING: vlc/ folder not found at ${vlcSrc.absolutePath}. Place VLC DLLs there before deploying.")
        }
    }
}
