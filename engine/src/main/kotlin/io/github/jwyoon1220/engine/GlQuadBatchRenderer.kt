package io.github.jwyoon1220.engine

import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL20.glUniform2f
import org.lwjgl.opengl.GL20.glUniform4f
import org.lwjgl.opengl.GL20.glUseProgram
import org.lwjgl.opengl.GL20.glVertexAttribPointer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.system.MemoryUtil
import java.awt.Color
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * 엔진 레벨 OpenGL 배치 쿼드 렌더러 (GLSL + 텍스처 바인딩).
 *
 * - 논리 좌표계(1280x720)를 사용합니다.
 * - 같은 텍스처를 연속으로 그릴 때 draw call을 최소화합니다.
 */
class GlQuadBatchRenderer(
    private val designWidth: Float,
    private val designHeight: Float,
    private val maxQuads: Int = 4096
) {
    companion object {
        private const val STRIDE_FLOATS = 8 // pos2 + uv2 + color4
        private const val VERTS_PER_QUAD = 6
        private const val COLOR_DIV = 255f
    }

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var whiteTexture = 0

    private var uViewportLoc = -1
    private var uDesignLoc = -1
    private var uFramebufferLoc = -1
    private var uTexLoc = -1

    private val maxVertices = maxQuads * VERTS_PER_QUAD
    private val vertexData = FloatArray(maxVertices * STRIDE_FLOATS)
    private var vertexCount = 0
    private var boundTexture = -1
    private var frameBegun = false
    private lateinit var uploadBuffer: FloatBuffer

    fun init() {
        if (program != 0) return

        val vs = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aUV;
            layout (location = 2) in vec4 aColor;

            uniform vec4 uViewport; // x, y, w, h (physical pixels)
            uniform vec2 uDesign;   // logical size
            uniform vec2 uFramebuffer; // physical framebuffer size

            out vec2 vUV;
            out vec4 vColor;

            void main() {
                float px = uViewport.x + (aPos.x / uDesign.x) * uViewport.z;
                float py = uViewport.y + (aPos.y / uDesign.y) * uViewport.w;

                float nx = (px / uFramebuffer.x) * 2.0 - 1.0;
                float ny = 1.0 - (py / uFramebuffer.y) * 2.0;

                gl_Position = vec4(nx, ny, 0.0, 1.0);
                vUV = aUV;
                vColor = aColor;
            }
        """.trimIndent()

        val fs = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            uniform sampler2D uTex;
            out vec4 FragColor;

            void main() {
                FragColor = texture(uTex, vUV) * vColor;
            }
        """.trimIndent()

        var vertexShader = 0
        var fragmentShader = 0
        val programId = glCreateProgram()
        try {
            vertexShader = compileShader(GL_VERTEX_SHADER, vs)
            fragmentShader = compileShader(GL_FRAGMENT_SHADER, fs)
            glAttachShader(programId, vertexShader)
            glAttachShader(programId, fragmentShader)
            glLinkProgram(programId)
            check(glGetProgrami(programId, GL_LINK_STATUS) != 0) {
                "OpenGL program link failed: ${glGetProgramInfoLog(programId)}"
            }
        } catch (e: Exception) {
            glDeleteProgram(programId)
            throw e
        } finally {
            if (vertexShader != 0) glDeleteShader(vertexShader)
            if (fragmentShader != 0) glDeleteShader(fragmentShader)
        }
        program = programId

        vao = glGenVertexArrays()
        vbo = glGenBuffers()
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * STRIDE_FLOATS * Float.SIZE_BYTES).toLong(), GL_DYNAMIC_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, STRIDE_FLOATS * Float.SIZE_BYTES, 0L)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE_FLOATS * Float.SIZE_BYTES, (2 * Float.SIZE_BYTES).toLong())
        glVertexAttribPointer(2, 4, GL_FLOAT, false, STRIDE_FLOATS * Float.SIZE_BYTES, (4 * Float.SIZE_BYTES).toLong())
        glEnableVertexAttribArray(0)
        glEnableVertexAttribArray(1)
        glEnableVertexAttribArray(2)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        uViewportLoc = glGetUniformLocation(program, "uViewport")
        uDesignLoc = glGetUniformLocation(program, "uDesign")
        uFramebufferLoc = glGetUniformLocation(program, "uFramebuffer")
        uTexLoc = glGetUniformLocation(program, "uTex")

        whiteTexture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, whiteTexture)
        val px: ByteBuffer = MemoryUtil.memAlloc(4)
        px.put(0, 0xFF.toByte())
        px.put(1, 0xFF.toByte())
        px.put(2, 0xFF.toByte())
        px.put(3, 0xFF.toByte())
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, px)
        MemoryUtil.memFree(px)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glBindTexture(GL_TEXTURE_2D, 0)

        uploadBuffer = MemoryUtil.memAllocFloat(maxVertices * STRIDE_FLOATS)
    }

    fun destroy() {
        if (program == 0) return
        flush()
        if (::uploadBuffer.isInitialized) MemoryUtil.memFree(uploadBuffer)
        glDeleteTextures(whiteTexture)
        glDeleteBuffers(vbo)
        glDeleteVertexArrays(vao)
        glDeleteProgram(program)
        whiteTexture = 0
        vbo = 0
        vao = 0
        program = 0
        frameBegun = false
    }

    fun begin(framebufferWidth: Int, framebufferHeight: Int, offsetX: Float, offsetY: Float, drawW: Float, drawH: Float) {
        if (program == 0) init()
        if (frameBegun) end()
        if (framebufferWidth <= 0 || framebufferHeight <= 0 || drawW <= 0f || drawH <= 0f) {
            frameBegun = false
            return
        }

        vertexCount = 0
        boundTexture = -1
        frameBegun = true

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(program)
        glUniform4f(uViewportLoc, offsetX, offsetY, drawW, drawH)
        glUniform2f(uDesignLoc, designWidth, designHeight)
        glUniform2f(uFramebufferLoc, framebufferWidth.toFloat(), framebufferHeight.toFloat())
        glUniform1i(uTexLoc, 0)
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glActiveTexture(GL_TEXTURE0)
    }

    fun end() {
        if (!frameBegun) return
        flush()
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
        glBindTexture(GL_TEXTURE_2D, 0)
        glUseProgram(0)
        glDisable(GL_BLEND)
        frameBegun = false
    }

    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: Color, textureId: Int = whiteTexture) {
        drawGradientRect(x, y, w, h, color, color, color, color, textureId)
    }

    /** 텍스처 샘플 결과와 정점 색상을 곱해 그립니다(기본: whiteTexture). */
    fun drawGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        topLeft: Color, topRight: Color, bottomRight: Color, bottomLeft: Color,
        textureId: Int = whiteTexture
    ) {
        if (!frameBegun || w <= 0f || h <= 0f) return
        ensureTexture(textureId)
        ensureCapacity(VERTS_PER_QUAD)

        val x0 = x
        val y0 = y
        val x1 = x + w
        val y1 = y + h

        putVertex(x0, y0, 0f, 0f, topLeft)
        putVertex(x1, y0, 1f, 0f, topRight)
        putVertex(x1, y1, 1f, 1f, bottomRight)

        putVertex(x0, y0, 0f, 0f, topLeft)
        putVertex(x1, y1, 1f, 1f, bottomRight)
        putVertex(x0, y1, 0f, 1f, bottomLeft)
    }

    private fun ensureTexture(textureId: Int) {
        if (boundTexture == textureId) return
        flush()
        boundTexture = textureId
        glBindTexture(GL_TEXTURE_2D, boundTexture)
    }

    private fun ensureCapacity(extraVerts: Int) {
        if (vertexCount + extraVerts <= maxVertices) return
        flush()
    }

    private fun putVertex(x: Float, y: Float, u: Float, v: Float, c: Color) {
        val base = vertexCount * STRIDE_FLOATS
        vertexData[base] = x
        vertexData[base + 1] = y
        vertexData[base + 2] = u
        vertexData[base + 3] = v
        vertexData[base + 4] = c.red / COLOR_DIV
        vertexData[base + 5] = c.green / COLOR_DIV
        vertexData[base + 6] = c.blue / COLOR_DIV
        vertexData[base + 7] = c.alpha / COLOR_DIV
        vertexCount++
    }

    private fun flush() {
        if (!frameBegun || vertexCount <= 0) return
        uploadBuffer.clear()
        uploadBuffer.put(vertexData, 0, vertexCount * STRIDE_FLOATS)
        uploadBuffer.flip()
        glBufferSubData(GL_ARRAY_BUFFER, 0L, uploadBuffer)
        glDrawArrays(GL_TRIANGLES, 0, vertexCount)
        vertexCount = 0
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) {
            "OpenGL shader compile failed: ${glGetShaderInfoLog(shader)}"
        }
        return shader
    }
}
