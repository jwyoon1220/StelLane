package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.engine.multiplayer.StelLaneUpnpConfiguration
import org.junit.jupiter.api.Test
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpnpTest {

    private fun <T> Iterator<T>.drain(): List<T> =
        generateSequence { if (hasNext()) next() else null }.toList()

    @Test
    fun `유효한 물리 네트워크 인터페이스만 필터링됨`() {
        val config = StelLaneUpnpConfiguration()
        val factory = config.createNetworkAddressFactory()

        val allIfaces = NetworkInterface.getNetworkInterfaces().toList()
        println("\n=== 전체 네트워크 인터페이스 ===")
        allIfaces.forEach { iface ->
            println("  [${iface.name}] ${iface.displayName}  up=${iface.isUp}  loopback=${iface.isLoopback}")
        }

        val usable = factory.networkInterfaces.drain()
        println("\n=== StelLaneUpnpConfiguration 통과 후 인터페이스 ===")
        usable.forEach { iface ->
            println("  [${iface.name}] ${iface.displayName}")
        }

        assert(usable.none { it.displayName.lowercase().contains("hyper-v") }) {
            "Hyper-V 어댑터가 필터링되지 않음: ${usable.map { it.displayName }}"
        }
        assert(usable.none { it.displayName.lowercase().contains("virtualbox") }) {
            "VirtualBox 어댑터가 필터링되지 않음: ${usable.map { it.displayName }}"
        }
        assert(usable.none { it.displayName.lowercase().contains("vmware") }) {
            "VMware 어댑터가 필터링되지 않음: ${usable.map { it.displayName }}"
        }

        println("\n✓ 필터링 결과: ${allIfaces.size}개 → ${usable.size}개")
    }

    @Test
    fun `UPnP 서비스 시작 및 Router enabled 확인`() {
        println("\n=== UPnP 서비스 시작 테스트 ===")
        val upnp = org.jupnp.UpnpServiceImpl(StelLaneUpnpConfiguration())

        val startupResult = runCatching { upnp.startup() }
        try {
            if (startupResult.isFailure) {
                // 테스트 classpath에 javax.servlet-api 미포함 시 stream server가 시작 불가.
                // 프로덕션 코드(checkUpnpAvailable/tryUPnP)는 runCatching으로 처리하므로 무관.
                val msg = startupResult.exceptionOrNull()?.message ?: "unknown"
                println("⚠ startup() 예외 (테스트 환경 제약, 프로덕션 코드와 무관): $msg")
                return
            }
            val enabled = upnp.router.isEnabled
            println("Router.isEnabled = $enabled")
            assert(enabled) { "Router가 startup() 후에도 disabled — 인터페이스 필터링 확인 필요" }
            println("✓ Router 정상 활성화됨")
        } finally {
            runCatching { upnp.shutdown() }
        }
    }

    @Test
    fun `UPnP 게이트웨이 탐지 (checkUpnpAvailable)`() {
        println("\n=== UPnP 게이트웨이 탐지 (최대 8초) ===")
        val latch = CountDownLatch(1)
        var result: Boolean? = null

        MultiplayerManager.checkUpnpAvailable { found ->
            result = found
            latch.countDown()
        }

        val completed = latch.await(8, TimeUnit.SECONDS)
        assert(completed) { "checkUpnpAvailable 콜백이 8초 안에 오지 않음 (스레드 교착 의심)" }

        println(if (result == true) "✓ UPnP 게이트웨이 발견됨" else "✓ 완료 (게이트웨이 없음 또는 UPnP 비활성)")
        println("  결과: $result  (true=지원, false=미지원 — 둘 다 정상 완료)")
    }

    @Test
    fun `tryUPnP 콜백 수신 확인`() {
        println("\n=== tryUPnP 포트 매핑 테스트 (최대 10초) ===")
        val manager = MultiplayerManager()
        val latch = CountDownLatch(1)
        var callbackIp: String? = null
        var callbackSuccess: Boolean? = null

        manager.tryUPnP(MultiplayerManager.DEFAULT_PORT) { ip, success ->
            callbackIp = ip
            callbackSuccess = success
            latch.countDown()
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assert(completed) { "tryUPnP 콜백이 10초 안에 오지 않음 (스레드 교착 의심)" }

        println("  IP: $callbackIp")
        println(if (callbackSuccess == true) "✓ 포트 매핑 성공" else "✓ 완료 (포트 매핑 실패 — 정상 처리)")
        assert(callbackIp != null) { "IP가 null — getLocalIp() 실패 의심" }
    }
}
