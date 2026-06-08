package io.github.jwyoon1220.engine

import org.lwjgl.opengl.GL20.*

/** GLSL 셰이더 프로그램 래퍼 — 컴파일·링크·유니폼 설정을 캡슐화합니다. */
class Shader(vertSrc: String, fragSrc: String) {
    val programId: Int

    init {
        val vert = compile(GL_VERTEX_SHADER, vertSrc)
        val frag = compile(GL_FRAGMENT_SHADER, fragSrc)
        val prog = glCreateProgram()
        glAttachShader(prog, vert)
        glAttachShader(prog, frag)
        glLinkProgram(prog)
        glDeleteShader(vert)
        glDeleteShader(frag)
        check(glGetProgrami(prog, GL_LINK_STATUS) != 0) {
            "Shader link failed: ${glGetProgramInfoLog(prog)}"
        }
        programId = prog
    }

    fun use() = glUseProgram(programId)

    fun uniform1i(name: String, v: Int)                                   = glUniform1i(loc(name), v)
    fun uniform1f(name: String, v: Float)                                 = glUniform1f(loc(name), v)
    fun uniform2f(name: String, x: Float, y: Float)                       = glUniform2f(loc(name), x, y)
    fun uniform4f(name: String, x: Float, y: Float, z: Float, w: Float)   = glUniform4f(loc(name), x, y, z, w)

    fun destroy() = glDeleteProgram(programId)

    private fun loc(name: String) = glGetUniformLocation(programId, name)

    companion object {
        private fun compile(type: Int, src: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, src)
            glCompileShader(id)
            check(glGetShaderi(id, GL_COMPILE_STATUS) != 0) {
                "Shader compile failed: ${glGetShaderInfoLog(id)}"
            }
            return id
        }
    }
}
