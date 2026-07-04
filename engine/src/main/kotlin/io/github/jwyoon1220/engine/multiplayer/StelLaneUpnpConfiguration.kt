package io.github.jwyoon1220.engine.multiplayer

import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.transport.impl.NetworkAddressFactoryImpl
import org.jupnp.transport.spi.NetworkAddressFactory
import java.net.NetworkInterface

/**
 * Hyper-V, VirtualBox, VMware 등 가상 네트워크 어댑터를 제외하고
 * 실제 물리 인터페이스만 사용하는 jUPnP 설정.
 *
 * 가상 어댑터가 혼재하면 Router가 disabled 상태로 남아 UPnP 패킷을
 * 모두 무시하는 문제가 있으므로, 이 설정으로 인터페이스 범위를 좁힙니다.
 */
internal class StelLaneUpnpConfiguration : DefaultUpnpServiceConfiguration() {

    override fun createNetworkAddressFactory(
        streamListenPort: Int,
        multicastResponsePort: Int
    ): NetworkAddressFactory {
        return object : NetworkAddressFactoryImpl(streamListenPort, multicastResponsePort) {
            override fun isUsableNetworkInterface(iface: NetworkInterface): Boolean {
                if (!super.isUsableNetworkInterface(iface)) return false
                val display = iface.displayName.lowercase()
                val name    = iface.name.lowercase()
                return !display.contains("hyper-v")      &&
                       !display.contains("virtualbox")   &&
                       !display.contains("vmware")       &&
                       !name.startsWith("vbox")          &&
                       !name.startsWith("vmnet")         &&
                       !name.startsWith("veth")          &&
                       !name.startsWith("docker")        &&
                       !name.startsWith("br-")
            }
        }
    }
}
