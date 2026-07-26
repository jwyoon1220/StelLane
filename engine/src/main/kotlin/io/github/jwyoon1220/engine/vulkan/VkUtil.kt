package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.vulkan.VK10.VK_SUCCESS

/** VkResult가 VK_SUCCESS가 아니면 예외를 던집니다. Vulkan 호출부에서 매번 반복되는 체크를 통일합니다. */
fun vkCheck(result: Int, message: String) {
    if (result != VK_SUCCESS) throw IllegalStateException("$message (VkResult=$result)")
}
