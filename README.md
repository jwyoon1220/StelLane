# StelLane

StelLane은 Kotlin/JVM 기반의 리듬 게임 프로젝트입니다.  
멀티모듈 구조로 게임 클라이언트, 엔진, 도메인 로직, 에디터/빌더를 분리해 구성되어 있습니다.

## 모듈 구성

- `app` : 게임 실행 클라이언트 (메인 메뉴, 선곡, 플레이, 설정, 에디터 상태 등)
- `core` : 도메인 모델(`Song`, `Chart`, `Note`), 판정/점수, 곡 로딩/파싱
- `engine` : 게임 루프, 입력 처리, VLC 기반 비디오 백그라운드
- `editor` : 채보 편집 보조 로직(타임라인/퀀타이즈)
- `builder` : 곡 메타/리소스 생성용 Swing 툴
- `assets` : 폰트 및 라이선스 리소스

## 기술 스택

- Kotlin `2.1.21`
- Java Toolchain `21`
- Gradle Wrapper `9.3.0`
- GLFW/OpenGL(LWJGL)
- VLCJ `4.8.2`

## 요구 사항

- JDK 21
- (비디오 기능 사용 시) VLC 런타임 라이브러리
  - 로컬 `vlc/` 폴더 또는 시스템 VLC 설치 환경

## 빠른 시작

### 1) 전체 테스트

```bash
sh ./gradlew test --no-daemon
```

> 참고: `engine` 테스트는 VLC 및 테스트 비디오 파일 환경에 따라 실패하거나 skip 될 수 있습니다.

### 2) 게임 실행

```bash
./gradlew :app:runGame
```

실행 전 `:app:prepareRunEnv`가 함께 수행되어 `run/` 폴더에 실행 환경이 준비됩니다.

### 3) 곡 빌더 실행(이 빌더 대신 게임 내부 곡 편집 기능을 사용해주세요)

```bash
./gradlew :builder:run
```

### 4) 배포 이미지 생성

```bash
./gradlew :app:deploy
```

`dist/` 아래에 배포용 앱 이미지가 생성됩니다.

## 곡 데이터 구조

기본적으로 `run/songs`를 기준으로 곡 데이터를 읽습니다.

예시:

```text
run/
  songs/
    STAR_TRAIL.json
    STAR_TRAIL/
      video.mp4
      cover.png
      easy.json
      hard.json
```

- `songs/*.json` : 곡 메타(`Song`)
- `songs/<곡폴더>/*.json` : 난이도별 채보(`Chart`)
- 미디어 경로는 메타 파일 기준으로 곡 폴더 내 파일명을 참조합니다.

## 테스트 관련 참고

- `engine/src/test`와 `app/src/test` 일부는 VLC/비디오 파일 의존 통합 테스트입니다.
- 로컬 환경에서 재생 백엔드가 없으면 테스트가 실패 또는 skip 될 수 있습니다.

## 라이선스

프로젝트 라이선스는 루트 `LICENSE`와 `engine/LICENSE`의 Apache License 2.0과 GNU GPL 3.0에 의해 라이선스 됩니다.  
추가 리소스(폰트/오픈소스 고지)는 `assets/src/main/resources`를 참고하세요.
