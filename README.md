<div align="center">

# ✨ StelLane

**별이 흐르는 리듬 위를 달려라**

*A rhythm game built with Kotlin/JVM — StelLive Fan Game, Your way is our way.*

---

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/JDK-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.3.0-02303A?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org)
[![License](https://img.shields.io/badge/License-Apache%202.0%20%2F%20GPL%203.0-blue?style=flat-square)](./LICENSE)
[![Fan Game](https://img.shields.io/badge/Fan%20Game-%EC%8A%A4%ED%85%94%EB%9D%BC%EC%9D%B4%EB%B8%8C-FF6B9D?style=flat-square)](https://stellive.me)

> 🩷 이 프로젝트는 **스텔라이브(Stellive)** 팬이 만든 비공식 팬게임입니다.  
> Stellive 및 소속 스트리머와는 공식적인 관계가 없습니다.

</div>

---

## 🎮 이게 뭔가요?

StelLane은 **스텔라이브(Stellive)** 팬이 만든 **Kotlin/JVM** 기반 리듬 게임입니다.  
GLFW/OpenGL로 렌더링하고, VLC로 배경 영상을 재생하며, 직접 채보를 만들어 플레이할 수 있어요.

> 좋아하는 스텔라이브 멤버들의 노래에 맞춰 노트를 받아치고, 점수로 덕력을 증명하세요. 🌟

---

## 🏗️ 모듈 구성

```
StelLane/
├── 🎮 app       — 게임 클라이언트 (메뉴, 선곡, 플레이, 설정, 에디터)
├── 🧩 core      — 도메인 모델(Song/Chart/Note), 판정/점수, 파싱
├── ⚙️  engine    — 게임 루프, 입력 처리, VLC 비디오 백그라운드
├── 🎼 editor    — 채보 편집 보조 로직 (타임라인/퀀타이즈)
├── 🔨 builder   — 곡 메타/리소스 생성용 Swing 툴
└── 🎨 assets    — 폰트 및 라이선스 리소스
```

---

## 🛠️ 기술 스택

| 항목 | 버전/내용 |
|------|----------|
| 언어 | Kotlin `2.1.21` |
| JVM | Java Toolchain `21` |
| 빌드 | Gradle Wrapper `9.3.0` |
| 렌더링 | GLFW / OpenGL (LWJGL) |
| 영상 재생 | VLCJ `4.8.2` |

---

## 📋 요구 사항

- **JDK 21** 이상
- **VLC 런타임** (비디오 배경 기능 사용 시)
  - 로컬 `vlc/` 폴더 또는 시스템에 VLC가 설치된 환경

---

## 🚀 빠른 시작

### 테스트 실행

```bash
./gradlew test --no-daemon
```

> `engine` 모듈 테스트는 VLC 및 테스트 영상 파일 유무에 따라 skip되거나 실패할 수 있습니다.

### 게임 실행

```bash
./gradlew :app:runGame
```

실행 전 `:app:prepareRunEnv`가 자동으로 실행되어 `run/` 폴더에 실행 환경이 준비됩니다.

### 배포 이미지 빌드

```bash
./gradlew :app:deploy
```

`dist/` 폴더 아래에 배포용 앱 이미지가 생성됩니다.

### 곡 빌더 실행

```bash
./gradlew :builder:run
```

> 게임 내부의 곡 편집 기능을 사용하는 것을 권장합니다. 이 빌더는 보조 도구입니다.

---

## 🎵 곡 데이터 구조

곡 데이터는 기본적으로 `run/songs/` 경로를 기준으로 읽습니다.

```
run/
└── songs/
    ├── STAR_TRAIL.json          ← 곡 메타 (Song)
    └── STAR_TRAIL/
        ├── video.mp4            ← 배경 영상
        ├── cover.png            ← 커버 이미지
        ├── easy.json            ← 이지 채보 (Chart)
        └── hard.json            ← 하드 채보 (Chart)
```

- `songs/*.json` — 곡 메타 정보 (`Song`)
- `songs/<곡폴더>/*.json` — 난이도별 채보 (`Chart`)
- 미디어 경로는 메타 파일 기준으로 곡 폴더 내 파일명을 참조합니다.

---

## ⚠️ 테스트 관련 주의

`engine/src/test` 및 `app/src/test` 일부는 VLC와 영상 파일에 의존하는 통합 테스트입니다.  
로컬에 재생 백엔드가 없으면 테스트가 **실패하거나 skip**될 수 있습니다.

---

## 📜 라이선스

이 프로젝트는 두 가지 라이선스로 구성됩니다:

- 루트 코드 — [Apache License 2.0](./LICENSE)
- `engine` 모듈 — [GNU GPL 3.0](./engine/LICENSE)

추가 리소스(폰트, 오픈소스 고지)는 `assets/src/main/resources`를 참고하세요.

---

<div align="center">

*별이 빛나는 밤, 오늘도 레인을 달립니다* 🌠

<sub>이 프로젝트는 스텔라이브의 비공식 팬게임입니다. Stellive 및 소속 크리에이터의 콘텐츠를 존중합니다. 🩷</sub>

</div>
