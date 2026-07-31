package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkLayerProperties
import org.slf4j.LoggerFactory

/**
 * VkInstance 생성 + (옵션) 검증 레이어/디버그 메신저.
 *
 * 검증 레이어(VK_LAYER_KHRONOS_validation)는 Vulkan SDK가 설치된 환경에서만 사용 가능합니다.
 * 요청했지만 시스템에 없으면 경고 로그만 남기고 레이어 없이 계속 진행합니다 — 개발 편의 기능이라
 * 없다고 기동을 막을 이유가 없습니다.
 */
class VulkanInstance private constructor(
    val handle: VkInstance,
    private val debugMessenger: Long
) {
    companion object {
        private val log = LoggerFactory.getLogger(VulkanInstance::class.java)
        private const val VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation"

        fun create(appName: String, enableValidation: Boolean): VulkanInstance = stackPush().use { stack ->
            val useValidation = enableValidation && isLayerAvailable(stack, VALIDATION_LAYER)
            if (enableValidation && !useValidation) {
                log.warn("[Vulkan] {} 레이어를 사용할 수 없어 검증 레이어 없이 계속합니다 (Vulkan SDK 설치 여부 확인)", VALIDATION_LAYER)
            }

            val appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8(appName))
                .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(stack.UTF8("StelLane Engine"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK_API_VERSION_1_2)

            val requiredExt = GLFWVulkan.glfwGetRequiredInstanceExtensions()
                ?: error("GLFW가 필요한 Vulkan 인스턴스 확장 목록을 반환하지 않았습니다 (Vulkan 로더 미설치?)")

            val extensions: PointerBuffer =
                if (useValidation) {
                    val withDebug = stack.mallocPointer(requiredExt.remaining() + 1)
                    withDebug.put(requiredExt).put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)).flip()
                    withDebug
                } else requiredExt

            val createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(extensions)

            var debugCreateInfo: VkDebugUtilsMessengerCreateInfoEXT? = null
            if (useValidation) {
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)))
                debugCreateInfo = debugMessengerCreateInfo(stack)
                createInfo.pNext(debugCreateInfo.address())
            }

            val pInstance = stack.mallocPointer(1)
            vkCheck(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance 실패")
            val instance = VkInstance(pInstance[0], createInfo)

            var messenger = NULL
            if (useValidation && debugCreateInfo != null) {
                val pMessenger = stack.mallocLong(1)
                vkCheck(
                    vkCreateDebugUtilsMessengerEXT(instance, debugCreateInfo, null, pMessenger),
                    "vkCreateDebugUtilsMessengerEXT 실패"
                )
                messenger = pMessenger[0]
                log.info("[Vulkan] 검증 레이어 활성화됨 ({})", VALIDATION_LAYER)
            }

            VulkanInstance(instance, messenger)
        }

        private fun isLayerAvailable(stack: MemoryStack, layerName: String): Boolean {
            val count = stack.mallocInt(1)
            vkEnumerateInstanceLayerProperties(count, null)
            if (count[0] == 0) return false
            val layers = VkLayerProperties.malloc(count[0], stack)
            vkEnumerateInstanceLayerProperties(count, layers)
            return layers.any { it.layerNameString() == layerName }
        }

        private fun debugMessengerCreateInfo(stack: MemoryStack): VkDebugUtilsMessengerCreateInfoEXT =
            VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                )
                .messageType(
                    VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                    VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                    VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
                )
                .pfnUserCallback { severity, _, pCallbackData, _ ->
                    val data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)
                    val msg = "[VulkanValidation] ${data.pMessageString()}"
                    if (severity and VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT != 0) log.error(msg)
                    else log.warn(msg)
                    VK_FALSE
                }
    }

    /** 디버그 메신저(있으면) + 인스턴스 해제. 이 인스턴스에 종속된 모든 객체(서피스 등)를 먼저 정리한 뒤 호출하세요. */
    fun destroy() {
        if (debugMessenger != NULL) vkDestroyDebugUtilsMessengerEXT(handle, debugMessenger, null)
        vkDestroyInstance(handle, null)
    }
}
