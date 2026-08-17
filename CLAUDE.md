# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

StelLane is a Kotlin/JVM rhythm game (unofficial Stellive fan game). GLFW/LWJGL windowing, a
pluggable OpenGL(NanoVG)/Vulkan renderer, VLC (vlcj) for video backgrounds, and a custom ECS for
gameplay scenes. Source comments and docs are largely in Korean.

## Modules

Gradle multi-module project (`settings.gradle.kts`): `app`, `core`, `engine`, `editor`, `assets`, `story-editor`.

- **core** — pure domain layer, no rendering/IO deps beyond Jackson: `Song`/`Chart`/`Note` data
  models (`core/data`), chart JSON parsing (`core/song/ChartParser.kt`, `SongManager.kt`), judgment
  (`core/judgment`), scoring (`core/scoring/ScoreEngine.kt`), replay frames (`core/replay`), and
  story mode data (`core/story`).
- **engine** — windowing (GLFW), the ECS core (`engine/ecs`), the render abstraction
  (`engine/render`), the Vulkan backend (`engine/vulkan`), VLC video background
  (`VideoBackground.kt`), input (`InputManager.kt`), and multiplayer networking (`engine/multiplayer`,
  Ktor + Protobuf). Depends on `core`.
- **app** — the actual game client: all scenes (`app/ecs/*Scene.kt`), `Main.kt` entry point,
  settings, UI dialogs, and note rendering. Depends on `core`, `engine`, `editor`, and (runtime-only)
  `assets`.
- **editor** — in-game chart-editing support logic (timeline, quantizer, editor render/playback
  systems). Depends on `core`.
- **assets** — resource-only module (fonts, license text, default story data); no Kotlin source.
- **story-editor** — a Swing tool for authoring story packs (`core/story/StoryPack`/`StoryChapter`
  JSON: pack metadata, chapters, cutscenes with image/video backgrounds, dialogue lines, required
  songs), separate from the in-game chart editor. Runs standalone (`./gradlew :story-editor:run`,
  its own `main()`) or embedded in-process from the game's main menu ("Story Editor" item, see
  `MainMenuScene.openStoryEditor()`). Depends only on `core` (for the data model + Jackson).

Module dependency direction is strictly `core` ← `engine`/`editor`/`story-editor` ← `app`. `app`
additionally depends on `story-editor` (to embed `StoryEditorFrame`), but `story-editor` itself never
depends on `app`/`engine`. Don't introduce reverse dependencies (e.g. `core` must never depend on
`engine`).

## Common commands

```bash
# Run all tests
./gradlew test --no-daemon

# Run tests for a single module
./gradlew :core:test
./gradlew :engine:test

# Run a single test class / method
./gradlew :core:test --tests "io.github.jwyoon1220.core.judgment.JudgmentSystemTest"
./gradlew :engine:test --tests "*.VideoBackgroundTest.someMethod"

# Run the game (writes a run/ working directory with fat jar, assets, bundled VLC)
./gradlew :app:runGame
# Experimental Vulkan backend:
./gradlew :app:runGame -Pvulkan

# Build a portable distributable app image (jlink + jpackage) into dist/
./gradlew :app:deploy

# Run the standalone story editor tool (authors run/story or assets/.../story pack/chapter JSON)
# Same tool is also reachable in-game via MainMenuScene's "Story Editor" item.
./gradlew :story-editor:run
```

Notes:
- `engine`/`app` tests that touch VLC or video files are integration tests — they may skip or fail
  without a local playback backend / test video files present (see `engine/src/test` and
  `app/src/test/.../VideoDumpTest.kt`, `VulkanFontSmokeTest.kt`).
- `engine`'s test task sets `-Djna.library.path` and `PATH` to the repo-root `vlc/` folder when it
  exists, so vlcj can find bundled VLC without a system install.
- `:app:runGame` depends on `:app:prepareRunEnv`, which builds a fat jar and populates `run/`
  (assets, an empty `run/songs/`, and a copy of `vlc/`). The game reads song data from `run/songs/`
  at runtime (see "Song data layout" below).
- `--vulkan`/`-Pvulkan` selects the experimental Vulkan render backend; without it, `Main.kt` shows a
  renderer-choice dialog (Swing) before any GLFW/LWJGL context is touched. Dear ImGui (via
  `imgui-java-lwjgl3`) is OpenGL-only, so ImGui-based UI (editor import/export dialogs, decoration
  editing) is skipped entirely in Vulkan mode.
- `--screenshot` is a debug flag: captures a raw framebuffer PNG ~4s after launch then exits — used
  for pixel-level OpenGL-vs-Vulkan comparisons.

## Architecture

### Scene/ECS model (`engine/ecs`)

- `World` — per-scene entity/component store (`entities`, `set`/`get`/`has`/`remove`,
  `entitiesWith<T>()` queries). Main-thread only.
- `Scene` (abstract, in `app/ecs/*Scene.kt`) — owns a `World` and an `EventBus`, registers
  `EcsSystem`s via `register(...)`, and drives them each frame via `tickSystems`. Systems run in
  registration order.
- `EcsSystem.update(world, input, deltaTime)` is the base unit of gameplay logic. `RenderProducer`
  systems additionally `produce()` `RenderCommand`s each frame instead of drawing directly.
  `MainThreadSystem`/`RenderProducer` must only run on the GLFW main thread.
- `SceneRouter` (`engine/GameState.kt`) — holds the current `Scene`, calls `exit()`/`enter()` on
  navigation.
- Two render paths coexist: the ECS path (systems emit `RenderCommand`s, gathered via
  `Scene.gatherRenderCommands()`) and a legacy path (`Scene.render(DrawContext)` overridden directly,
  e.g. `RenderCommand.LegacyDrawContext`). New scenes should prefer the ECS/RenderCommand path.

### Renderer backend abstraction (`engine/render`, `engine/vulkan`)

- `Renderer` (`engine/Renderer.kt`) is backend-agnostic: it never calls any `org.lwjgl.opengl.*` or
  `org.lwjgl.vulkan.*` API directly. It only computes framebuffer size and letterbox/pillarbox
  scale/offset, then delegates a full frame to `RendererBackend.renderFrame(...)`.
  Clearing, video-background compositing, RenderCommand execution, post-processing, and UI overlay
  are entirely the backend's responsibility.
- `RendererBackend` implementations: `NanoVGBackend` (OpenGL + NanoVG + GL post-processing + ImGui —
  the default/complete path) and `VulkanBackend` (`engine/vulkan/`, experimental — RenderCommand
  execution is a work in progress).
- `RendererFactory` is a registry (`register(id, creator)` / `create(id, ctx)`); `Main.kt` registers
  `"vulkan"` and sets `renderer.backendId` only when `--vulkan` is chosen, otherwise NanoVG is used.
- If you touch rendering, keep graphics-API symbols confined to the specific backend file — do not
  leak `org.lwjgl.opengl.*`/`org.lwjgl.vulkan.*` into `Renderer`, `Scene`, or systems.

### Game loop

`GameLoop.start()` (`engine/GameLoop.kt`) is a blocking main-thread loop: poll GLFW events → build an
`InputSnapshot` and inject it into the current `Scene` → `SceneRouter.update(delta)` → `Renderer.renderFrame()`
→ `swapBuffers()` → frame-pace to `targetFPS` (sleep + spin-wait for precision). `targetFPS = 0` means
uncapped.

### Chart/song data and gameplay

- `core/data/Song.kt`, `Chart.kt`, `Note.kt` — domain models; `MutableChart`/`DecorationData` support
  the in-game editor.
- `core/song/ChartParser.kt` / `DecorationParser.kt` parse the JSON formats; `SongManager` scans
  `<workingDir>/songs/*.json` for song metadata and builds `SongEntry` list (see `SongEntryExt.kt` in
  `app` for extensions).
- `core/judgment/JudgmentSystem.kt` classifies a hit by `|hitTime - noteTime|` against fixed windows
  (PERFECT/GREAT/GOOD/MISS in ms); `core/scoring/ScoreEngine.kt` turns judgments into score.
- Gameplay scenes (`PlayScene`, `MultiplayerPlayScene`) wire spawn/input/judgment/render systems
  together via the ECS; `app/render/NoteRenderer.kt` and `engine/data/pool/VisualNote.kt` +
  `ObjectPool` handle pooled note visuals to avoid per-frame allocation.
- Story mode (`core/story/*`, `app/story/*`) is data-driven, user-authorable, and organized into
  **story packs** — independent, self-contained story "themes" so users can keep multiple unrelated
  stories side by side without their chapters/images colliding. `StoryManager`
  (`core/story/StoryManager.kt`) scans `<workingDir>/story/*/` at runtime (via `GameContext.storyManager`,
  constructed/loaded in `Main.kt` alongside `SongManager`): each subfolder with a `pack.json`
  (`StoryPack`: id/title/description/order) is one pack, and its `chapters/*.json` files are loaded
  into a `LoadedStoryPack`. Character/background portraits live in that pack's own `<packId>/images/`
  and cutscene background videos in `<packId>/videos/` (own only that pack's, unlike shared song
  media) — resolved by `DialogueRenderer`/`StoryCutsceneScene` as `File(pack.imagesDir|videosDir, name)`.
  A `StoryScene.backgroundVideo` (if set) takes priority over `backgroundImage`: `StoryCutsceneScene`
  plays it full-screen via `ctx.videoBackground` (looping if it finishes before the dialogue does) the
  same way `MainMenuScene`/`PlayScene` play video backgrounds, and stops it on `exit()`.
  `DialogueRenderer.renderBackground` skips its own image/solid-fill when a video is active so the
  video shows through, still layering the dialogue-readability scrim. In-game flow is
  `StoryPackSelectScene` (pick a pack) → `StorySelectScene` (pick a chapter within that pack) →
  `StoryFlowController`. Progress persists via `StoryProgressManager`, keyed per-pack via
  `StoryProgressManager.key(packId, chapterId)` so two packs can reuse the same chapter id without
  clobbering each other's saved progress. The default story ships as the `sonata_forgotten` pack under
  `assets/src/main/resources/story/` (excluded from the jar's classpath resources — see
  `assets/build.gradle.kts`) and is copied into `run/story/`/the deploy app image by
  `app/build.gradle.kts` (`prepareRunEnv`/`deploy`), just like `run/songs/`. Users author their own
  packs either by hand (new `story/<packId>/{pack.json, chapters/, images/, videos/}` folder) or with
  the `story-editor` Swing tool — standalone (`./gradlew :story-editor:run`) or opened in-process from
  the game's main menu ("Story Editor" item) — which manages packs (create/edit/delete) and
  edits/saves each pack's chapter JSON + copied images/videos in place.

### Song data layout on disk

Song data is read relative to `run/songs/` at runtime:

```
run/songs/
├── STAR_TRAIL.json        # song metadata (Song)
└── STAR_TRAIL/
    ├── video.mp4           # background video
    ├── cover.png           # cover image
    ├── easy.json           # chart (Chart), per difficulty
    └── hard.json
```

Media paths in a chart/song JSON are resolved relative to the song's own folder.

### Multiplayer (`engine/multiplayer`)

`MultiplayerManager` runs a Ktor server (host, `hostGame`) or client (`joinGame`/`spectate`) over
WebSockets, with protobuf messages (`engine/src/main/proto/multiplayer.proto`, generated via the
`com.google.protobuf` Gradle plugin — Kotlin builtins). Video sync uses an HLS server
(`HlsServer.kt`) to stream background video to remote players, plus `VideoSyncCoordinator` for
playback calibration. Networking runs on its own thread/coroutine pool — the game loop only *reads*
`remotePlayers`/`rankings`; all writes happen from network coroutines. UPnP (`org.jupnp`) is used for
optional port mapping when hosting.

### VLC / video background

`VideoBackground` (`engine/VideoBackground.kt`) wraps vlcj with a software callback surface (no AWT
heavyweight `Canvas`), decoding on a dedicated single-thread executor so EDT/VLC callback threads can
call it safely. The repo bundles a full VLC runtime under `vlc/` (DLLs + plugins) so the game works
without a system VLC install — `prepareRunEnv` and `deploy` both copy `vlc/` into their output dirs;
if a bundled VLC isn't found, video backgrounds are silently disabled (`isAvailable = false`) rather
than crashing.

## Language/locale note

Most in-repo comments, KDoc, and commit messages are Korean. Match the existing style (Korean KDoc)
when editing files that are already documented that way, unless the user asks otherwise.
