package io.github.jwyoon1220.app.multiplayer

import com.google.protobuf.ByteString
import io.github.jwyoon1220.app.multiplayer.proto.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.*
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.net.NetworkInterface
import java.nio.file.Path

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
    }

    // ── 공개 상태 (게임 루프에서 읽기 전용) ─────────────────────────────────────

    var isHost = false
        private set

    var localPlayerId: String = java.util.UUID.randomUUID().toString()
    var localPlayerName: String = io.github.jwyoon1220.app.AppSettings.nickname
    var localRole: String = "player"

    /** 원격 플레이어 상태 맵. 네트워크 스레드에서 쓰기, 게임 루프에서 읽기. */
    val remotePlayers: Object2ObjectOpenHashMap<String, RemotePlayerState> = Object2ObjectOpenHashMap()

    /** accuracy 기준 내림차순 정렬된 순위 리스트. */
    val rankings: ObjectArrayList<RemotePlayerState> = ObjectArrayList()

    private val sessions = ObjectOpenHashSet<DefaultWebSocketSession>()
    private val sessionsLock = Any()
    private val sessionToPlayer = Object2ObjectOpenHashMap<DefaultWebSocketSession, String>()

    // ── 콜백 ─────────────────────────────────────────────────────────────────────

    var onStartGame: ((songRelPath: String, difficulty: String, files: List<FileEntry>) -> Unit)? = null
    var onPlayerListUpdated: (() -> Unit)? = null
    var onGameOver: ((List<RankEntry>) -> Unit)? = null
    /** 호스트가 연결을 끊었을 때 클라이언트 측에서 호출됩니다. */
    var onHostDisconnected: (() -> Unit)? = null

    // ── 서버/클라이언트 ─────────────────────────────────────────────────────────

    private var serverEngine: EmbeddedServer<*, *>? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var clientSession: DefaultClientWebSocketSession? = null
    private val httpClient = HttpClient(ClientCIO) { install(ClientWebSockets) }

    private var lastBroadcastScore = -1
    private var lastBroadcastCounts = IntArray(4) { -1 }

    // ── 호스트 API ──────────────────────────────────────────────────────────────

    fun hostGame(port: Int = DEFAULT_PORT) {
        isHost = true
        remotePlayers.clear(); rankings.clear()
        synchronized(sessionsLock) { sessions.clear(); sessionToPlayer.clear() }

        serverEngine = embeddedServer(ServerCIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/") { handleHostSession(this) }
            }
        }.start(wait = false)

        log.info("[MultiPlayer] 호스트 서버 시작: port={}", port)
    }

    fun broadcastStart(songDir: File, songRelPath: String, difficulty: String) {
        val files = collectSongFiles(songDir)
        val start = StartMsg.newBuilder()
            .setSongRelPath(songRelPath)
            .setDifficulty(difficulty)
            .setCountdownMs(3000)
            .addAllFiles(files)
            .build()
        broadcastEnvelope(Envelope.newBuilder().setStart(start).build())
    }

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
        remotePlayers.clear(); rankings.clear()

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
            remotePlayers[localPlayerId]?.finished = true
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
            if (pid != null) { remotePlayers.remove(pid); broadcastPlayerList() }
        }
    }

    private suspend fun onHostReceive(session: DefaultWebSocketSession, env: Envelope) {
        when (env.payloadCase) {
            Envelope.PayloadCase.JOIN -> {
                val join = env.join
                val pid = join.playerId.ifBlank { java.util.UUID.randomUUID().toString() }
                synchronized(sessionsLock) { sessionToPlayer[session] = pid }
                remotePlayers[pid] = RemotePlayerState(pid, join.playerName, join.role)
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
                remotePlayers[env.laneUpdate.playerId]?.laneHeld = env.laneUpdate.laneHeldList.toBooleanArray()
                broadcastEnvelope(env)
            }
            Envelope.PayloadCase.FINISH -> {
                remotePlayers[env.finish.playerId]?.finished = true
                broadcastEnvelope(env)
                checkGameOver()
            }
            Envelope.PayloadCase.FILE_REQUEST -> {
                val relPath = env.fileRequest.relPath
                sendFileToSession(session, relPath)
            }
            else -> {}
        }
    }

    private var chart_totalNotes = 0
    fun setTotalNotes(n: Int) { chart_totalNotes = n }

    private suspend fun sendFileToSession(session: DefaultWebSocketSession, relPath: String) {
        val songFile = pendingSongDir?.let { File(it, relPath) } ?: return
        if (!songFile.exists()) { log.warn("[MultiPlayer] 파일 없음: {}", relPath); return }
        log.info("[MultiPlayer] 파일 전송: {} ({} bytes)", relPath, songFile.length())

        songFile.inputStream().buffered(CHUNK_SIZE).use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            var offset = 0L; var n: Int
            while (input.read(buf).also { n = it } != -1) {
                val isLast = input.available() == 0
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

    private val receivingBuffers = Object2ObjectOpenHashMap<String, java.io.ByteArrayOutputStream>()

    private suspend fun handleClientFrame(env: Envelope) {
        when (env.payloadCase) {
            Envelope.PayloadCase.PLAYER_LIST -> {
                env.playerList.playersList.forEach { info ->
                    if (info.id != localPlayerId)
                        remotePlayers[info.id] = RemotePlayerState(info.id, info.name, info.role)
                }
                onPlayerListUpdated?.invoke()
            }
            Envelope.PayloadCase.START -> {
                val start = env.start
                for (fe in start.filesList) {
                    if (MultiplayerCacheManager.getCachedPath(fe.sha256) == null) requestFile(fe.relPath)
                }
                onStartGame?.invoke(start.songRelPath, start.difficulty, start.filesList)
            }
            Envelope.PayloadCase.SCORE_UPDATE -> {
                val su = env.scoreUpdate
                if (su.playerId != localPlayerId) {
                    val state = remotePlayers.getOrPut(su.playerId) {
                        RemotePlayerState(su.playerId, su.playerId, "player")
                    }
                    state.score = su.score; state.combo = su.combo; state.accuracy = su.accuracy
                    su.countsList.toIntArray().also { System.arraycopy(it, 0, state.counts, 0, 4) }
                    rebuildRankings()
                }
            }
            Envelope.PayloadCase.LANE_UPDATE -> {
                val lu = env.laneUpdate
                if (lu.playerId != localPlayerId)
                    remotePlayers[lu.playerId]?.laneHeld = lu.laneHeldList.toBooleanArray()
            }
            Envelope.PayloadCase.FINISH -> { remotePlayers[env.finish.playerId]?.finished = true }
            Envelope.PayloadCase.GAME_OVER -> { onGameOver?.invoke(env.gameOver.rankingsList) }
            Envelope.PayloadCase.FILE_CHUNK -> {
                val chunk = env.fileChunk
                val buf = receivingBuffers.getOrPut(chunk.relPath) { java.io.ByteArrayOutputStream() }
                buf.write(chunk.data.toByteArray())
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
        val players = remotePlayers.values.map { p ->
            PlayerInfo.newBuilder().setId(p.id).setName(p.name).setRole(p.role).build()
        }
        val selfInfo = PlayerInfo.newBuilder()
            .setId(localPlayerId).setName(localPlayerName).setRole(localRole).build()
        broadcastEnvelope(Envelope.newBuilder().setPlayerList(
            PlayerListMsg.newBuilder().addAllPlayers(players + selfInfo).build()
        ).build())
    }

    private fun updateRemoteScore(pid: String, score: Int, combo: Int, counts: IntArray, accuracy: Float) {
        val state = remotePlayers.getOrPut(pid) { RemotePlayerState(pid, pid, "player") }
        state.score = score; state.combo = combo; state.accuracy = accuracy
        System.arraycopy(counts, 0, state.counts, 0, 4)
        rebuildRankings()
    }

    private fun rebuildRankings() {
        val players = remotePlayers.values
            .filter { it.role == "player" }
            .sortedWith(compareByDescending<RemotePlayerState> { it.accuracy }.thenByDescending { it.score })
        rankings.clear(); players.forEach { rankings.add(it) }
    }

    private fun checkGameOver() {
        val players = remotePlayers.values.filter { it.role == "player" }
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
                val upnp = org.jupnp.UpnpServiceImpl(org.jupnp.DefaultUpnpServiceConfiguration())
                upnp.startup()
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
