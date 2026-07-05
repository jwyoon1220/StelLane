package io.github.jwyoon1220.engine.multiplayer

import io.github.jwyoon1220.engine.multiplayer.proto.FileEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 호스트-클라이언트 간 전체 오프셋 보정 핸드셰이크를 실제 WebSocket(로컬루프백)으로 검증하는
 * 통합 테스트. VLC/VideoBackground는 사용하지 않고(GUI 불필요) 프로토콜/상태 머신만 검증합니다.
 *
 * 시나리오: 호스트가 방을 열고 곡을 시작(beginCalibration) → 클라이언트가 CalibrateStartMsg(곡 정보 +
 * 차트/오디오/영상 전체 파일 목록 + 오프셋 보정용 테스트 스트림 URL)를 즉시 수신해 ReadyMsg 응답
 * (테스트에서는 실제 영상 재생 대신 즉시 ready 처리) → 호스트가 전원 준비를 확인하고 sync_epoch_ms가
 * 채워진 StartMsg를 브로드캐스트. 실제 매치 영상은 스트리밍하지 않고(차트/오디오와 동일하게) 전체
 * 파일로 전달되므로 StartMsg에는 동기화 시각만 담깁니다.
 */
class MultiplayerCalibrationFlowTest {

    private val hostManager = MultiplayerManager()
    private val clientManager = MultiplayerManager()
    private val port = 18291

    companion object {
        private val SONG_DIR = File("run/songs/STAR_TRAIL")
    }

    @AfterEach
    fun tearDown() {
        runCatching { clientManager.stop() }
        runCatching { hostManager.stop() }
    }

    @Test
    fun `호스트가 곡을 시작하면 클라이언트가 곡 정보를 즉시 받고 보정 후 sync_epoch_ms가 포함된 StartMsg를 수신한다`() {
        assumeTrue(SONG_DIR.exists(), "테스트 곡 폴더 없음: ${SONG_DIR.absolutePath}")

        hostManager.localPlayerName = "Host"
        hostManager.hostGame(port = port)

        val connectedLatch = CountDownLatch(1)
        clientManager.localPlayerName = "Client"
        clientManager.onPlayerListUpdated = { connectedLatch.countDown() }

        val calibrateLatch = CountDownLatch(1)
        var receivedSongRelPath: String? = null
        var receivedFiles: List<FileEntry> = emptyList()
        clientManager.onCalibrateStart = { songRelPath, _, files, _ ->
            receivedSongRelPath = songRelPath
            receivedFiles = files
            calibrateLatch.countDown()
            // 실제 플레이 화면에서는 VideoBackground로 테스트 스트림을 재생하며 보정하지만,
            // 이 테스트는 프로토콜/상태 머신만 검증하므로 즉시 준비 완료로 처리
            clientManager.sendReady()
        }

        val startLatch = CountDownLatch(1)
        var receivedEpoch = 0L
        clientManager.onStartGame = { syncEpochMs ->
            receivedEpoch = syncEpochMs
            startLatch.countDown()
        }

        clientManager.joinGame("127.0.0.1", port)
        assertTrue(connectedLatch.await(10, TimeUnit.SECONDS), "클라이언트가 호스트에 연결되지 않았습니다")

        hostManager.setPendingSongDir(SONG_DIR)
        hostManager.beginCalibration(SONG_DIR, "STAR_TRAIL/normal", "normal")
        hostManager.markLocalReady() // 호스트 자신의 로컬 보정 완료 (이 테스트에서는 즉시)

        assertTrue(calibrateLatch.await(10, TimeUnit.SECONDS), "10초 내에 CalibrateStartMsg를 수신하지 못했습니다")
        assertTrue(receivedSongRelPath == "STAR_TRAIL/normal", "곡 정보가 올바르게 전달되지 않음: $receivedSongRelPath")
        assertTrue(receivedFiles.any { it.relPath.endsWith(".mp4") },
            "CALIBRATE_START의 files에는 영상도 포함되어야 합니다(전체 파일 전송 대상): ${receivedFiles.map { it.relPath }}")

        assertTrue(startLatch.await(15, TimeUnit.SECONDS), "15초 내에 StartMsg를 수신하지 못했습니다")
        assertTrue(receivedEpoch > System.currentTimeMillis() - 5_000, "sync_epoch_ms 값이 비정상적입니다: $receivedEpoch")
    }
}
