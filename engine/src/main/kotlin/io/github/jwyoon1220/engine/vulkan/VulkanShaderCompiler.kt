package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc.*
import java.nio.ByteBuffer

/** GLSL 소스를 런타임에 SPIR-V로 컴파일합니다 (shaderc). 앱 실행 시 1회씩만 호출하세요 — 매 프레임 호출 금지. */
object VulkanShaderCompiler {

    /** 컴파일된 SPIR-V 바이트코드 — 힙 메모리 사본이라 shaderc 결과 해제 후에도 안전하게 씁니다. */
    fun compileToSpirV(source: String, kind: Int, tag: String): ByteBuffer {
        val compiler = shaderc_compiler_initialize()
        check(compiler != 0L) { "shaderc_compiler_initialize 실패" }
        return try {
            val result = shaderc_compile_into_spv(compiler, source, kind, tag, "main", 0L)
            try {
                val status = shaderc_result_get_compilation_status(result)
                check(status == shaderc_compilation_status_success) {
                    "[$tag] 셰이더 컴파일 실패 (status=$status): ${shaderc_result_get_error_message(result)}"
                }
                val bytes = shaderc_result_get_bytes(result) ?: error("[$tag] shaderc_result_get_bytes가 null")
                // shaderc_result_release 이후에도 유효하도록 힙에 복사
                val copy = MemoryUtil.memAlloc(bytes.remaining())
                copy.put(bytes.duplicate()).flip()
                copy
            } finally {
                shaderc_result_release(result)
            }
        } finally {
            shaderc_compiler_release(compiler)
        }
    }
}
