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
GLFW/OpenGL(또는 실험적 Vulkan)로 렌더링하고, VLC로 배경 영상을 재생하며, 직접 채보를 만들어 플레이할 수 있어요.

> 좋아하는 스텔라이브 멤버들의 노래에 맞춰 노트를 받아치고, 점수로 덕력을 증명하세요. 🌟

---

## ✨ 주요 기능

| 기능 | 설명 |
|------|------|
| 🎯 **정밀 판정 & 스코어링** | Perfect(±20ms)/Great(±100ms)/Good(±150ms)/Miss 4단계 판정, 콤보 기반 최대 100만점 스코어링, SS~D 등급, 0.5×~35× 노트 속도 조절 |
| 🛠️ **인게임 채보 에디터** | 줌 가능한 타임라인 + BPM 퀀타이즈(1/4·1/8·1/16), 곡에 맞춰 직접 태핑하는 실시간 레코딩 모드, 다중 선택/복사·붙여넣기/실행취소 |
| 🎨 **데코레이션 & 포스트프로세싱** | 타임라인 기반 이미지 오버레이(페이드/이동/회전/스케일 트윈), 블룸·CRT·글리치·필름그레인 등 GLSL 화면 이펙트를 채보와 함께 편집 |
| 📖 **스토리 모드** | 비주얼노벨풍 컷신 + 곡 플레이가 이어지는 진행형 스토리, 챕터 순차 잠금해제와 진행도 저장. 컷신 배경은 이미지뿐 아니라 **영상**도 지원. `run/story/`에 챕터 JSON을 추가하면 **누구나 자신만의 스토리를 만들 수 있음**(곡과 동일한 방식) |
| ✍️ **스토리 에디터** | 챕터/컷신/대사를 폼 기반으로 편집하는 독립 Swing 툴(`story-editor`). 배경 이미지·영상 첨부, 대사·필요 곡 테이블 편집, 저장 시 `story/images/`·`story/videos/`로 미디어 자동 복사 — 미니멀한 Ren'Py류 편집기 |
| 🌐 **온라인 멀티플레이** | 방 호스팅/참가/관전, 실시간 정확도 랭킹, 클라이언트 간 영상 동기화 재생, UPnP 자동 포트매핑 |
| 🖥️ **듀얼 렌더러** | 안정적인 OpenGL/NanoVG 백엔드와 실험적 Vulkan 백엔드 중 실행 시 선택 가능 (`--vulkan`/`--opengl`) |
| 🎬 **VLC 배경 영상** | 메뉴/에디터/플레이/스토리 컷신 전반에서 영상을 배경으로 재생, VLC 미설치 시에도 안전하게 자동 비활성화 |
| 🎵 **자작곡 제작** | 인게임 곡 생성 폼(메타데이터 입력 + 커버/오디오/비디오 파일 선택)으로 바로 채보 제작을 시작해 에디터로 이어짐 |
| ⚙️ **세부 설정** | 창 모드, A/V 싱크 오프셋, 음악/타격음 볼륨, FPS 제한, VSync, 최초 실행 EULA 동의 흐름, 인게임 라이선스·크레딧 뷰어 |

---

## 🏗️ 모듈 구성

```
StelLane/
├── 🎮 app          — 게임 클라이언트 (메뉴, 선곡, 플레이, 설정, 에디터)
├── 🧩 core         — 도메인 모델(Song/Chart/Note), 판정/점수, 파싱
├── ⚙️  engine       — 게임 루프, 입력 처리, VLC 비디오 백그라운드
├── 🎼 editor       — 채보 편집 보조 로직 (타임라인/퀀타이즈)
├── ✍️ story-editor — 스토리 챕터/컷신/대사 작성용 Swing 툴
└── 🎨 assets       — 폰트, 라이선스, 기본 스토리 데이터
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

### 스토리 에디터 실행

```bash
./gradlew :story-editor:run
```

> 챕터/컷신/대사를 폼 기반으로 작성하는 독립 Swing 도구입니다. 배경 이미지·영상을 선택하면 자동으로
> `story/images/`·`story/videos/`에 복사되고, 저장하면 `StoryManager`가 읽는 챕터 JSON이 그대로 생성됩니다.

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

## ⚠️ 저작권 안내 및 면책 조항

StelLane은 **맵 데이터(채보)** 및 관련 **음원·영상**을 직접 배포하지 않습니다.

| 항목 | 내용 |
|------|------|
| 🎵 미디어 파일 | 게임에 로드되는 음원·영상·이미지는 **사용자가 직접 준비**하며, 저작권법 준수 여부에 대한 **모든 법적 책임은 사용자 본인**에게 있습니다. |
| 🚫 무단 공유 금지 | 저작권자의 허락 없이 음원·영상을 포함한 맵 파일을 온라인상에 공유·배포하는 행위는 저작권법에 위반될 수 있습니다. |
| 🌐 멀티플레이 | 멀티플레이 호스팅 시 공유되는 콘텐츠의 저작권 문제에 대한 책임은 **호스트 사용자 본인**에게 있으며, 개발팀은 일체의 책임을 지지 않습니다. |
| 🩷 팬게임 | 스텔라이브(Stellive) 및 소속 크리에이터의 비공식 팬게임으로, 원저작자의 저작물을 존중합니다. 영리 목적 사용을 금합니다. |

> **면책 조항 (Disclaimer)**  
> StelLane은 리듬 게임 엔진 및 플레이어를 제공할 뿐이며, 개발자는 사용자가 로드·공유하는 음원·영상·이미지 등 외부 콘텐츠의 저작권 침해에 대해 일체의 법적 책임을 부담하지 않습니다.

저작권 침해 신고, 차단 요청, 기타 문의는 아래로 연락해 주세요.

[![Contact](https://img.shields.io/badge/Contact-stellane%40parin.asia-blue?style=flat-square&logo=gmail&logoColor=white)](mailto:stellane@parin.asia)

---

## 📜 라이선스

이 프로젝트는 **GNU General Public License v3.0**으로 라이선스됩니다.

- 전체 소스 코드 — [GNU GPL 3.0](./LICENSE)

추가 리소스(폰트, 오픈소스 고지)는 `assets/src/main/resources`를 참고하세요.

---

## 생성형 인공지능 사용에 관한 안내
 - 본 프로젝트는 생성형 인공지능을 이용해 이미지 등을 생성했습니다.

---
<div align="center">

*별이 빛나는 밤, 오늘도 레인을 달립니다* 🌠

<sub>이 프로젝트는 스텔라이브의 비공식 팬게임입니다. Stellive 및 소속 크리에이터의 콘텐츠를 존중합니다. 🩷</sub>

</div>
