package io.github.jwyoon1220.engine.multiplayer

import com.google.protobuf.ByteString
import io.github.jwyoon1220.engine.multiplayer.proto.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.*
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.*
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.*
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MultiPlayer플레이어 네트워크 레이어.
 *
 * - 호스트: [hostGame] → Ktor WebSocket 서버 (OS 스레드 풀, 게임 루프와 완전 분리)
 * - 클라이언트: [joinGame] → Ktor WebSocket 클라이언트
 * - 관전자: [spectate] → 클라이언트와 동일, role="spectator"
 *
 * 게임 루프는 [remotePlayers], [rankings] 를 읽기만 함 (쓰기는 네트워크 코루틴).
 */
class MultiplayerManager {

    private val log = LoggerFactory.getLogger(MultiplayerManager::class.java)

    companion object {
        const val DEFAULT_PORT = 7777
        private const val CHUNK_SIZE = 256 * 1024  // 256 KB
        private const val CALIBRATION_TIMEOUT_MS = 8_000L
        private const val FINALIZE_LEAD_MS = 1_500L
        private val TEST_VIDEO_NAMES = listOf("01.mp4", "02.mp4", "03.mp4", "04.mp4", "05.mp4")

        /**
         * UPnP 게이트웨이 장치가 존재하는지만 탐지합니다 (포트 매핑 없음).
         * MultiplayerMenuScene에서 사전 안내용으로 사용합니다.
         */
        fun checkUpnpAvailable(onResult: (Boolean) -> Unit) {
            Thread({
                runCatching {
                    val upnp = org.jupnp.UpnpServiceImpl(StelLaneUpnpConfiguration())
                    upnp.startup()
                    if (!upnp.router.isEnabled) upnp.router.enable()
                    Thread.sleep(2000)
                    val found = upnp.registry.devices.any { device ->
                        device.findService(org.jupnp.model.types.UDAServiceType("WANIPConnection")) != null ||
                        device.findService(org.jupnp.model.types.UDAServiceType("WANPPPConnection")) != null
                    }
                    upnp.shutdown()
                    onResult(found)
                }.onFailure { onResult(false) }
            }, "stellane-upnp-check").apply { isDaemon = true; start() }
        }
    }

    // ── 공개 상태 (게임 루프에서 읽기 전용) ─────────────────────────────────────

    var isHost = false
        private set

    var localPlayerId: String = java.util.UUID.randomUUID().toString()
    var localPlayerName: String = "Player"
    var localRole: String = "player"

    /**
     * 원격 플레이어 상태 맵. 네트워크 스레드에서 쓰기, 게임 루프에서 읽기.
     * ConcurrentHashMap으로 동시 접근 안전화. 호스트 자신도 포함됨.
     */
    private val _remotePlayers = ConcurrentHashMap<String, RemotePlayerState>()
    val remotePlayers: Map<String, RemotePlayerState> get() = _remotePlayers

    /** accuracy 기준 내림차순 정렬된 순위 리스트. volatile reference swap으로 thread-safe. */
    @Volatile
    var rankings: List<RemotePlayerState> = emptyList()
        private set

    private val sessions = ObjectOpenHashSet<DefaultWebSocketSession>()
    private val sessionsLock = Any()
    private val sessionToPlayer = Object2ObjectOpenHashMap<DefaultWebSocketSession, String>()

    // ── 콜백 ─────────────────────────────────────────────────────────────────────

    /** 최종 동기화 신호 — 영상 position 0이 되어야 할 호스트 wall-clock. */
    var onStartGame: ((syncEpochMs: Long) -> Unit)? = null
    var onPlayerListUpdated: (() -> Unit)? = null
    var onGameOver: ((List<RankEntry>) -> Unit)? = null
    /** 호스트가 연결을 끊었을 때 클라이언트 측에서 호출됩니다. */
    var onHostDisconnected: (() -> Unit)? = null
    /**
     * 호스트가 곡 시작을 준비하며 오프셋 보정용 테스트 스트림 URL + 곡 정보/파일 목록을 공지했을 때
     * 호출됩니다. 아직 최종 sync_epoch_ms는 모르는 시점 — 이 콜백에서 곧바로 플레이 화면으로
     * 진입해 보정 대기 UI + 테스트 영상을 보여주고, 이후 [onStartGame]으로 최종 동기화 값을 받는다.
     */
    var onCalibrateStart: ((songRelPath: String, difficulty: String, files: List<FileEntry>, hlsUrl: String) -> Unit)? = null
    /** 클럭 ping에 대한 호스트의 pong 응답. [VideoSyncCoordinator]가 소비합니다. */
    var onClockPong: ((ClockPongMsg) -> Unit)? = null

    // ── 서버/클라이언트 ─────────────────────────────────────────────────────────

    private var serverEngine: EmbeddedServer<*, *>? = null
    private var boundPort: Int = DEFAULT_PORT

    /** 서버가 현재 실행 중인지 여부. HostLobbyScene 재진입 시 중복 시작 방지에 사용. */
    val isServerRunning: Boolean get() = serverEngine != null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var clientSession: DefaultClientWebSocketSession? = null
    private val httpClient = HttpClient(ClientCIO) { install(ClientWebSockets) }

    private var lastBroadcastScore = -1
    private var lastBroadcastCounts = IntArray(4) { -1 }

    // ── HLS 스트리밍 (오프셋 보정용 테스트 클립 전용 — 실제 매치 영상은 전체 파일 전송) ─────────

    private val testVideoCacheRoot = File(System.getProperty("user.home"), ".stellane/cache/hls-test")
    private val hlsServer = HlsServer(HlsServer.resolveFfmpegPath())

    private var currentTestStreamDir: File? = null
    @Volatile private var testStreamManifestName: String? = null

    private val readyPlayerIds = ConcurrentHashMap.newKeySet<String>()
    private val startFinalized = AtomicBoolean(false)

    // ── 호스트 API ──────────────────────────────────────────────────────────────

    fun hostGame(port: Int = DEFAULT_PORT) {
        // 이전 서버가 남아 있으면 먼저 정리 (ESC로 SongSelect에서 돌아올 때 재호출 방지)
        serverEngine?.stop(500, 500)
        serverEngine = null
        boundPort = port

        isHost = true
        _remotePlayers.clear()
        rankings = emptyList()
        synchronized(sessionsLock) { sessions.clear(); sessionToPlayer.clear() }

        // 호스트 자신을 remotePlayers에 등록 — checkGameOver가 finish를 올바르게 감지하기 위해 필요
        _remotePlayers[localPlayerId] = RemotePlayerState(localPlayerId, localPlayerName, localRole)

        // 오프셋 보정용 테스트 스트림은 곡 선택 전(로비 대기 중)에 미리 준비해둬, beginCalibration()이
        // 호출될 때는 이미 준비되어 있어 URL을 즉시 공지할 수 있게 한다.
        testStreamManifestName = null
        clientScope.launch(Dispatchers.IO) {
            testStreamManifestName = runCatching { pickAndEnsureTestStream() }.getOrNull()
        }

        serverEngine = embeddedServer(ServerCIO, port = port) {
            install(ServerWebSockets)
            // 일부 VLC adaptive/HLS 데먹서가 세그먼트 요청에 Range/HEAD를 사용할 것으로 기대함
            install(PartialContent)
            install(AutoHeadResponse)
            routing {
                webSocket("/") { handleHostSession(this) }
                get("/hls/test/{fileName}") { serveHlsFile(call, currentTestStreamDir, call.parameters["fileName"]) }
            }
        }.start(wait = false)

        log.info("[MultiPlayer] 호스트 서버 시작: port={}", port)
    }

    private suspend fun serveHlsFile(call: ApplicationCall, dir: File?, fileName: String?) {
        if (dir == null || fileName.isNullOrBlank() || fileName.contains("..") ||
            fileName.contains('/') || fileName.contains('\\')
        ) {
            call.respond(HttpStatusCode.NotFound); return
        }
        val file = File(dir, fileName)
        if (!file.exists() || !file.isFile) { call.respond(HttpStatusCode.NotFound); return }
        val contentType = when {
            fileName.endsWith(".m3u8") -> ContentType.parse("application/vnd.apple.mpegurl")
            fileName.endsWith(".m4s")  -> ContentType.parse("video/iso.segment") // fMP4 조각
            fileName.endsWith(".mp4")  -> ContentType.parse("video/mp4")         // fMP4 init 세그먼트
            fileName.endsWith(".ts")   -> ContentType.parse("video/mp2t")        // 레거시 대비
            else -> ContentType.Application.OctetStream
        }
        // LocalFileContent(ReadChannelContent)를 써야 PartialContent 플러그인이 Range 요청을
        // 가로채 206 Partial Content로 응답할 수 있다(respondBytes의 ByteArrayContent는 지원 안 함).
        call.respond(LocalFileContent(file, contentType))
    }

    /**
     * 곡 선택 후 매치 시작 준비: 곡 정보/파일 목록(차트+오디오+영상 전체)과 오프셋 보정용 테스트
     * 스트림 URL을 공지해 클라이언트가 곧바로 플레이 화면으로 진입, 파일 전체를 요청/전송받으며
     * 보정을 진행하게 합니다. 전원 [ReadyMsg] 수신(또는 타임아웃) 시 [finalizeStart]를 호출합니다.
     */
    fun beginCalibration(songDir: File, songRelPath: String, difficulty: String) {
        startFinalized.set(false)
        readyPlayerIds.clear()
        val fullFiles = collectSongFiles(songDir)
        _remotePlayers.values.forEach { it.calibrated = false }
        broadcastPlayerList()

        log.info("[MultiPlayer] beginCalibration: song={}", songRelPath)

        // 오프셋 보정용 테스트 스트림은 hostGame()에서 이미 준비 완료(또는 준비 중)이므로 바로 공지
        val testUrl = testStreamManifestName?.let { "http://${getLocalIp()}:$boundPort/hls/test/$it" } ?: ""
        if (testUrl.isEmpty()) log.warn("[MultiPlayer] 테스트 스트림이 아직 준비되지 않음(ffmpeg 없음 또는 세그먼트화 미완료) — 보정 스킵됨")
        val calibrateMsg = CalibrateStartMsg.newBuilder()
            .setHlsUrl(testUrl).setSongRelPath(songRelPath).setDifficulty(difficulty).addAllFiles(fullFiles).build()
        broadcastEnvelope(Envelope.newBuilder().setCalibrateStart(calibrateMsg).build())
        onCalibrateStart?.invoke(songRelPath, difficulty, fullFiles, testUrl) // 호스트 자신은 세션이 없어 로컬에서 직접 호출

        // 낙오 클라이언트 대비 타임아웃 — 강제로 매치 시작
        clientScope.launch {
            delay(CALIBRATION_TIMEOUT_MS)
            finalizeStart()
        }
    }

    private fun pickAndEnsureTestStream(): String? {
        val name = TEST_VIDEO_NAMES.random()
        val srcFile = resolveTestVideoFile(name) ?: return null
        val segDir = File(testVideoCacheRoot, "hls_${name.substringBeforeLast('.')}")
        val manifest = hlsServer.ensureTestStreamSegmented(srcFile, segDir) ?: return null
        currentTestStreamDir = segDir
        return manifest.name
    }

    /** assets 모듈에 번들된 test_video/{name} 리소스를 실 파일로 1회 추출해 캐싱합니다. */
    private fun resolveTestVideoFile(name: String): File? {
        val cacheFile = File(testVideoCacheRoot, "src_$name")
        if (cacheFile.exists()) return cacheFile
        val stream = javaClass.classLoader.getResourceAsStream("test_video/$name") ?: return null
        return runCatching {
            Files.createDirectories(testVideoCacheRoot.toPath())
            stream.use { input -> cacheFile.outputStream().use { output -> input.copyTo(output) } }
            cacheFile
        }.getOrNull()
    }

    /** 전원 준비 완료 또는 타임아웃 시 호출됩니다. 멱등이며 최초 1회만 실제로 매치를 시작시킵니다. */
    private fun onReadyReceived(playerId: String) {
        readyPlayerIds.add(playerId)
        _remotePlayers[playerId]?.calibrated = true
        broadcastPlayerList()

        val expected = _remotePlayers.keys
        log.debug("[MultiPlayer] 준비 완료 수신: {} ({}/{})", playerId, readyPlayerIds.size, expected.size)
        if (expected.isNotEmpty() && readyPlayerIds.containsAll(expected)) {
            finalizeStart()
        }
    }

    /** 호스트 자신의 로컬 보정 완료를 알립니다 (네트워크 세션이 없으므로 직접 호출). */
    fun markLocalReady() = onReadyReceived(localPlayerId)

    private fun finalizeStart() {
        if (!startFinalized.compareAndSet(false, true)) return
        log.info("[MultiPlayer] finalizeStart 시작 (전원 준비 완료 또는 타임아웃)")
        val epoch = System.currentTimeMillis() + FINALIZE_LEAD_MS
        val start = StartMsg.newBuilder()
            .setSyncEpochMs(epoch)
            .build()
        log.info("[MultiPlayer] StartMsg 브로드캐스트: syncEpochMs={}", start.syncEpochMs)
        broadcastEnvelope(Envelope.newBuilder().setStart(start).build())
        invokeLocalStartGame(start)
    }

    /** 호스트는 자신에게 WebSocket 세션이 없으므로 onStartGame을 직접 호출합니다. */
    private fun invokeLocalStartGame(start: StartMsg) {
        onStartGame?.invoke(start.syncEpochMs)
    }

    // ── 클럭 동기화 ──────────────────────────────────────────────────────────────

    fun sendClockPing(t0: Long) = sendToHost(Envelope.newBuilder().setClockPing(
        ClockPingMsg.newBuilder().setPlayerId(localPlayerId).setT0(t0).build()
    ).build())

    fun sendReady() = sendToHost(Envelope.newBuilder().setReady(
        ReadyMsg.newBuilder().setPlayerId(localPlayerId).build()
    ).build())

    private fun collectSongFiles(songDir: File): List<FileEntry> =
        songDir.walkTopDown().filter { it.isFile }.map { f ->
            val sha = MultiplayerCacheManager.sha256OfFile(f.toPath())
            FileEntry.newBuilder()
                .setRelPath(f.relativeTo(songDir).path.replace('\\', '/'))
                .setSizeBytes(f.length())
                .setSha256(sha)
                .build()
        }.toList()

    // ── 클라이언트 API ──────────────────────────────────────────────────────────

    fun joinGame(host: String, port: Int = DEFAULT_PORT, role: String = "player") {
        isHost = false; localRole = role
        _remotePlayers.clear()
        rankings = emptyList()

        clientScope.launch {
            runCatching {
                httpClient.webSocket(host = host, port = port, path = "/") {
                    clientSession = this
                    sendJoin()
                    for (frame in incoming) {
                        if (frame is Frame.Binary) handleClientFrame(Envelope.parseFrom(frame.readBytes()))
                    }
                    clientSession = null
                }
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException)
                    log.warn("[MultiPlayer] WebSocket 오류: {}", e.message)
            }
            // coroutineContext가 아직 살아있으면 외부(호스트)가 끊은 것 → 콜백 호출
            if (isActive) onHostDisconnected?.invoke()
        }
        log.info("[MultiPlayer] 클라이언트 접속: {}:{} role={}", host, port, role)
    }

    fun spectate(host: String, port: Int = DEFAULT_PORT) = joinGame(host, port, "spectator")

    // ── 점수/레인 브로드캐스트 ──────────────────────────────────────────────────

    fun broadcastScoreIfChanged(score: Int, combo: Int, counts: IntArray, totalNotes: Int) {
        if (score == lastBroadcastScore && counts.contentEquals(lastBroadcastCounts)) return
        lastBroadcastScore = score
        System.arraycopy(counts, 0, lastBroadcastCounts, 0, 4)

        val accuracy = calcAccuracy(counts, totalNotes)
        val msg = JudgmentMsg.newBuilder()
            .setPlayerId(localPlayerId)
            .setScore(score).setCombo(combo)
            .addAllCounts(counts.toList())
            .build()
        val env = Envelope.newBuilder().setJudgment(msg).build()

        if (isHost) {
            updateRemoteScore(localPlayerId, score, combo, counts, accuracy)
            broadcastEnvelope(Envelope.newBuilder().setScoreUpdate(
                ScoreUpdateMsg.newBuilder()
                    .setPlayerId(localPlayerId).setScore(score).setCombo(combo)
                    .addAllCounts(counts.toList()).setAccuracy(accuracy).build()
            ).build())
        } else {
            sendToHost(env)
        }
    }

    fun broadcastLaneHeld(laneHeld: BooleanArray) {
        val env = Envelope.newBuilder().setLaneUpdate(
            LaneUpdateMsg.newBuilder().setPlayerId(localPlayerId)
                .addAllLaneHeld(laneHeld.toList()).build()
        ).build()
        if (isHost) broadcastEnvelope(env) else sendToHost(env)
    }

    fun sendFinish(totalNotes: Int) {
        val env = Envelope.newBuilder().setFinish(
            FinishMsg.newBuilder().setPlayerId(localPlayerId).build()
        ).build()
        if (isHost) {
            _remotePlayers[localPlayerId]?.finished = true
            checkGameOver()
            broadcastEnvelope(env)
        } else {
            sendToHost(env)
        }
    }

    // ── 호스트: 세션 핸들러 ─────────────────────────────────────────────────────

    private suspend fun handleHostSession(session: DefaultWebSocketSession) {
        synchronized(sessionsLock) { sessions.add(session) }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Binary) continue
                onHostReceive(session, Envelope.parseFrom(frame.readBytes()))
            }
        } finally {
            val pid = synchronized(sessionsLock) {
                sessions.remove(session); sessionToPlayer.remove(session)
            }
            if (pid != null) {
                _remotePlayers.remove(pid)
                rebuildRankings()
                broadcastPlayerList()
                // FINISH 없이 연결이 끊겼을 때도 남은 플레이어가 모두 완료했으면 게임 종료
                checkGameOver()
            }
        }
    }

    private suspend fun onHostReceive(session: DefaultWebSocketSession, env: Envelope) {
        when (env.payloadCase) {
            Envelope.PayloadCase.JOIN -> {
                val join = env.join
                val pid = join.playerId.ifBlank { java.util.UUID.randomUUID().toString() }
                synchronized(sessionsLock) { sessionToPlayer[session] = pid }
                _remotePlayers[pid] = RemotePlayerState(pid, join.playerName, join.role)
                broadcastPlayerList()
                log.info("[MultiPlayer] 플레이어 접속: {}({})", join.playerName, pid)
            }
            Envelope.PayloadCase.JUDGMENT -> {
                val j = env.judgment
                val accuracy = calcAccuracy(j.countsList.toIntArray(), chart_totalNotes)
                updateRemoteScore(j.playerId, j.score, j.combo, j.countsList.toIntArray(), accuracy)
                broadcastEnvelope(Envelope.newBuilder().setScoreUpdate(
                    ScoreUpdateMsg.newBuilder()
                        .setPlayerId(j.playerId).setScore(j.score).setCombo(j.combo)
                        .addAllCounts(j.countsList).setAccuracy(accuracy).build()
                ).build())
            }
            Envelope.PayloadCase.LANE_UPDATE -> {
                _remotePlayers[env.laneUpdate.playerId]?.laneHeld = env.laneUpdate.laneHeldList.toBooleanArray()
                broadcastEnvelope(env)
            }
            Envelope.PayloadCase.FINISH -> {
                _remotePlayers[env.finish.playerId]?.finished = true
                broadcastEnvelope(env)
                checkGameOver()
            }
            Envelope.PayloadCase.FILE_REQUEST -> {
                val relPath = env.fileRequest.relPath
                sendFileToSession(session, relPath)
            }
            Envelope.PayloadCase.CLOCK_PING -> {
                val ping = env.clockPing
                val t1 = System.currentTimeMillis()
                val pong = ClockPongMsg.newBuilder()
                    .setPlayerId(ping.playerId).setT0(ping.t0).setT1(t1).setT2(System.currentTimeMillis()).build()
                session.send(Frame.Binary(true, Envelope.newBuilder().setClockPong(pong).build().toByteArray()))
            }
            Envelope.PayloadCase.READY -> onReadyReceived(env.ready.playerId)
            else -> {}
        }
    }

    private var chart_totalNotes = 0
    fun setTotalNotes(n: Int) { chart_totalNotes = n }

    private suspend fun sendFileToSession(session: DefaultWebSocketSession, relPath: String) {
        val songFile = pendingSongDir?.let { File(it, relPath) } ?: return
        if (!songFile.exists()) { log.warn("[MultiPlayer] 파일 없음: {}", relPath); return }
        val totalSize = songFile.length()
        log.info("[MultiPlayer] 파일 전송: {} ({} bytes)", relPath, totalSize)

        val buf = ByteArray(CHUNK_SIZE)
        var offset = 0L
        songFile.inputStream().buffered(CHUNK_SIZE).use { input ->
            while (true) {
                val n = withContext(Dispatchers.IO) { input.read(buf) }
                if (n == -1) break
                // available()==0 이 EOF를 보장하지 않으므로 누적 offset으로 판단
                val isLast = offset + n >= totalSize
                val chunk = FileChunkMsg.newBuilder()
                    .setRelPath(relPath).setOffset(offset)
                    .setData(ByteString.copyFrom(buf, 0, n)).setIsLast(isLast).build()
                session.send(Frame.Binary(true, Envelope.newBuilder().setFileChunk(chunk).build().toByteArray()))
                offset += n
            }
        }
    }

    private var pendingSongDir: File? = null
    fun setPendingSongDir(dir: File) { pendingSongDir = dir }

    // ── 클라이언트: 프레임 핸들러 ───────────────────────────────────────────────

    private val receivingBuffers = ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()

    private suspend fun handleClientFrame(env: Envelope) {
        when (env.payloadCase) {
            Envelope.PayloadCase.PLAYER_LIST -> {
                val incomingIds = env.playerList.playersList.mapTo(HashSet()) { it.id }
                // 목록에서 빠진 플레이어 제거 (로컬 자신은 보존)
                _remotePlayers.keys.removeIf { it != localPlayerId && it !in incomingIds }
                env.playerList.playersList.forEach { info ->
                    if (info.id != localPlayerId)
                        _remotePlayers[info.id] = RemotePlayerState(info.id, info.name, info.role).apply { calibrated = info.ready }
                }
                rebuildRankings()
                onPlayerListUpdated?.invoke()
            }
            Envelope.PayloadCase.START -> {
                onStartGame?.invoke(env.start.syncEpochMs)
            }
            Envelope.PayloadCase.CLOCK_PONG -> onClockPong?.invoke(env.clockPong)
            Envelope.PayloadCase.CALIBRATE_START -> {
                val cs = env.calibrateStart
                for (fe in cs.filesList) {
                    if (MultiplayerCacheManager.getCachedPath(fe.sha256) == null) requestFile(fe.relPath)
                }
                onCalibrateStart?.invoke(cs.songRelPath, cs.difficulty, cs.filesList, cs.hlsUrl)
            }
            Envelope.PayloadCase.SCORE_UPDATE -> {
                val su = env.scoreUpdate
                if (su.playerId != localPlayerId) {
                    val state = _remotePlayers.getOrPut(su.playerId) {
                        // PLAYER_LIST 수신 전 SCORE_UPDATE가 먼저 오는 경우(드묾) — 이름은 목록 수신 시 갱신됨
                        log.debug("[MultiPlayer] SCORE_UPDATE 수신 시 미등록 플레이어: {}", su.playerId)
                        RemotePlayerState(su.playerId, su.playerId, "player")
                    }
                    state.score = su.score; state.combo = su.combo; state.accuracy = su.accuracy
                    state.counts = su.countsList.toIntArray()   // volatile reference swap
                    rebuildRankings()
                }
            }
            Envelope.PayloadCase.LANE_UPDATE -> {
                val lu = env.laneUpdate
                if (lu.playerId != localPlayerId)
                    _remotePlayers[lu.playerId]?.laneHeld = lu.laneHeldList.toBooleanArray()
            }
            Envelope.PayloadCase.FINISH -> { _remotePlayers[env.finish.playerId]?.finished = true }
            Envelope.PayloadCase.GAME_OVER -> { onGameOver?.invoke(env.gameOver.rankingsList) }
            Envelope.PayloadCase.FILE_CHUNK -> {
                val chunk = env.fileChunk
                val buf = receivingBuffers.getOrPut(chunk.relPath) { java.io.ByteArrayOutputStream() }
                withContext(Dispatchers.IO) {
                    buf.write(chunk.data.toByteArray())
                }
                if (chunk.isLast) {
                    receivingBuffers.remove(chunk.relPath)
                    val bytes = buf.toByteArray()
                    val sha = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes).joinToString("") { "%02x".format(it) }
                    MultiplayerCacheManager.putCache(sha, chunk.relPath, bytes.inputStream(), bytes.size.toLong())
                    log.info("[MultiPlayer] 파일 수신 완료: {}", chunk.relPath)
                }
            }
            else -> {}
        }
    }

    private fun requestFile(relPath: String) {
        sendToHost(Envelope.newBuilder().setFileRequest(
            FileRequestMsg.newBuilder().setRelPath(relPath).build()
        ).build())
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────────

    private fun sendJoin() {
        sendToHost(Envelope.newBuilder().setJoin(
            JoinMsg.newBuilder().setPlayerId(localPlayerId)
                .setPlayerName(localPlayerName).setRole(localRole).build()
        ).build())
    }

    private fun sendToHost(env: Envelope) {
        val bytes = env.toByteArray()
        clientScope.launch {
            runCatching {
                clientSession?.send(Frame.Binary(true, bytes))
            }.onFailure { log.warn("[MultiPlayer] 전송 실패: {}", it.message) }
        }
    }

    private fun broadcastEnvelope(env: Envelope) {
        val bytes = env.toByteArray()
        val snapshot = synchronized(sessionsLock) { sessions.toList() }
        clientScope.launch {
            snapshot.forEach { session ->
                runCatching { session.send(Frame.Binary(true, bytes)) }
                    .onFailure { log.warn("[MultiPlayer] 브로드캐스트 실패: {}", it.message) }
            }
        }
    }

    private fun broadcastPlayerList() {
        val players = _remotePlayers.values.map { p ->
            PlayerInfo.newBuilder().setId(p.id).setName(p.name).setRole(p.role).setReady(p.calibrated).build()
        }
        broadcastEnvelope(Envelope.newBuilder().setPlayerList(
            PlayerListMsg.newBuilder().addAllPlayers(players).build()
        ).build())
    }

    private fun updateRemoteScore(pid: String, score: Int, combo: Int, counts: IntArray, accuracy: Float) {
        val state = _remotePlayers.getOrPut(pid) { RemotePlayerState(pid, pid, "player") }
        state.score = score; state.combo = combo; state.accuracy = accuracy
        state.counts = counts.copyOf()   // volatile reference swap — never mutate in-place
        rebuildRankings()
    }

    private fun rebuildRankings() {
        rankings = _remotePlayers.values
            .filter { it.role == "player" }
            .sortedWith(compareByDescending<RemotePlayerState> { it.accuracy }.thenByDescending { it.score })
    }

    private fun checkGameOver() {
        val players = _remotePlayers.values.filter { it.role == "player" }
        if (players.isNotEmpty() && players.all { it.finished }) {
            val rankEntries = rankings.mapIndexed { i, p ->
                RankEntry.newBuilder().setRank(i + 1).setName(p.name)
                    .setScore(p.score).setAccuracy(p.accuracy).build()
            }
            broadcastEnvelope(Envelope.newBuilder().setGameOver(
                GameOverMsg.newBuilder().addAllRankings(rankEntries).build()
            ).build())
            onGameOver?.invoke(rankEntries)
        }
    }

    fun calcAccuracy(counts: IntArray, totalNotes: Int): Float {
        if (totalNotes <= 0) return 0f
        val weighted = counts[0] * 1.0 + counts[1] * 0.7 + counts[2] * 0.3
        return (weighted / totalNotes).toFloat()
    }

    // ── UPnP (최선 시도, 실패해도 LAN 플레이 가능) ──────────────────────────────

    fun tryUPnP(port: Int, onResult: ((String, Boolean) -> Unit)? = null) {
        val localIp = getLocalIp()
        Thread({
            runCatching {
                val upnp = org.jupnp.UpnpServiceImpl(StelLaneUpnpConfiguration())
                upnp.startup()
                if (!upnp.router.isEnabled) upnp.router.enable()
                Thread.sleep(2000)

                var found = false
                for (device in upnp.registry.devices) {
                    val service = device.findService(org.jupnp.model.types.UDAServiceType("WANIPConnection"))
                        ?: device.findService(org.jupnp.model.types.UDAServiceType("WANPPPConnection"))
                        ?: continue

                    val pm = org.jupnp.support.model.PortMapping(
                        port, localIp,
                        org.jupnp.support.model.PortMapping.Protocol.TCP, "StelLane"
                    )
                    val addAction = object : org.jupnp.support.igd.callback.PortMappingAdd(service, pm) {
                        override fun success(invocation: org.jupnp.model.action.ActionInvocation<*>?) {
                            log.info("[MultiPlayer] UPnP 포트 개방 성공: {}:{}", localIp, port)
                        }
                        override fun failure(invocation: org.jupnp.model.action.ActionInvocation<*>?, response: org.jupnp.model.message.UpnpResponse?, defaultMsg: String?) {
                            log.warn("[MultiPlayer] UPnP PortMappingAdd 실패: {}", defaultMsg)
                        }
                    }
                    upnp.controlPoint.execute(addAction)
                    found = true
                    onResult?.invoke(localIp, true)
                    upnp.shutdown()
                    break
                }
                if (!found) { onResult?.invoke(localIp, false); upnp.shutdown() }
            }.onFailure {
                log.warn("[MultiPlayer] UPnP 실패 (무시): {}", it.message)
                onResult?.invoke(localIp, false)
            }
        }, "stellane-upnp").apply { isDaemon = true; start() }
    }

    fun getLocalIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    // ── 종료 ────────────────────────────────────────────────────────────────────

    fun stop() {
        clientScope.cancel()
        serverEngine?.stop(500, 500)
        httpClient.close()
        log.info("[MultiPlayer] 네트워크 종료")
    }
}
