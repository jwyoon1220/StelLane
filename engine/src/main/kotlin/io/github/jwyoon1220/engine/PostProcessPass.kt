package io.github.jwyoon1220.engine

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*

/**
 * FBO 기반 화면 후처리 패스.
 *
 * [beginCapture] 호출 후 씬을 렌더링하고 [endCapture]로 캡처를 종료합니다.
 * [apply]는 [GlScreenEffectData] 목록을 순차적으로 적용합니다 (핑퐁 FBO).
 * 마지막 효과의 출력은 기본 프레임버퍼(화면)에 직접 기록됩니다.
 */
class PostProcessPass {

    private companion object {
        // 전체 화면 쿼드 버텍스 셰이더 (NDC, UV 자동 계산)
        val VERT = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            out vec2 vUV;
            void main() {
                vUV = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAG_GRAYSCALE = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            out vec4 FragColor;
            void main() {
                vec4 c = texture(uTex, vUV);
                float gray = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                FragColor = vec4(mix(c.rgb, vec3(gray), uIntensity), c.a);
            }
        """.trimIndent()

        val FRAG_FADE = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform vec4 uColor;
            out vec4 FragColor;
            void main() {
                vec4 c = texture(uTex, vUV);
                FragColor = mix(c, uColor, uIntensity);
            }
        """.trimIndent()

        val FRAG_BLUR = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform vec2 uTexelSize;
            out vec4 FragColor;
            void main() {
                float spread = uIntensity * 3.0;
                vec4 color = vec4(0.0);
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        color += texture(uTex, vUV + vec2(float(x), float(y)) * uTexelSize * spread);
                    }
                }
                FragColor = color / 25.0;
            }
        """.trimIndent()

        // ── 신규 빌트인 셰이더 ────────────────────────────────────────────────────

        /** CRT 효과: 스캔라인 + 색수차 + 비네팅 */
        val FRAG_CRT = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform vec2 uTexelSize;
            out vec4 FragColor;
            void main() {
                // 스캔라인
                float line = sin(vUV.y / uTexelSize.y * 3.14159) * 0.5 + 0.5;
                float scan = 1.0 - (1.0 - line) * uIntensity * 0.4;

                // 색수차 (가장자리로 갈수록 강해짐)
                vec2 center = vUV - 0.5;
                float ca = length(center) * uIntensity * 0.006;
                float r = texture(uTex, vUV + normalize(center) * ca).r;
                float g = texture(uTex, vUV).g;
                float b = texture(uTex, vUV - normalize(center) * ca).b;

                // 비네팅
                float vign = 1.0 - dot(center * 1.6, center * 1.6) * uIntensity;
                vign = clamp(vign, 0.0, 1.0);

                FragColor = vec4(vec3(r, g, b) * scan * vign, texture(uTex, vUV).a);
            }
        """.trimIndent()

        /** 블룸: 밝은 영역에서 빛이 번지는 효과 (단일 패스 근사) */
        val FRAG_BLOOM = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform vec2 uTexelSize;
            out vec4 FragColor;
            void main() {
                vec4 base = texture(uTex, vUV);
                vec4 bloom = vec4(0.0);
                float spread = uIntensity * 6.0 + 1.0;
                float total = 0.0;
                for (int x = -4; x <= 4; x++) {
                    for (int y = -4; y <= 4; y++) {
                        vec4 s = texture(uTex, vUV + vec2(float(x), float(y)) * uTexelSize * spread);
                        float brightness = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
                        float w = max(0.0, brightness - 0.4) * 2.5;
                        bloom += s * w;
                        total += w;
                    }
                }
                if (total > 0.0) bloom /= total;
                FragColor = vec4(base.rgb + bloom.rgb * uIntensity * 1.2, base.a);
            }
        """.trimIndent()

        /** 픽셀화: 화면을 저해상도로 렌더링하는 레트로 효과 */
        val FRAG_PIXELATE = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform vec2 uTexelSize;
            out vec4 FragColor;
            void main() {
                float px = mix(1.0 / uTexelSize.x, 48.0, uIntensity);
                float py = mix(1.0 / uTexelSize.y, 27.0, uIntensity);
                vec2 uv = floor(vUV * vec2(px, py)) / vec2(px, py) + 0.5 / vec2(px, py);
                FragColor = texture(uTex, uv);
            }
        """.trimIndent()

        /** 색수차: RGB 채널을 중앙에서 바깥쪽으로 분리 */
        val FRAG_CHROMAB = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            out vec4 FragColor;
            void main() {
                vec2 center = vUV - 0.5;
                float ca = uIntensity * 0.018;
                vec2 dir = normalize(center + vec2(0.0001));
                float r = texture(uTex, vUV + dir * ca * length(center) * 2.0).r;
                float g = texture(uTex, vUV).g;
                float b = texture(uTex, vUV - dir * ca * length(center) * 2.0).b;
                FragColor = vec4(r, g, b, texture(uTex, vUV).a);
            }
        """.trimIndent()

        /** 세피아: 따뜻한 갈색 빈티지 톤 */
        val FRAG_SEPIA = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            out vec4 FragColor;
            void main() {
                vec4 c = texture(uTex, vUV);
                vec3 sep = vec3(
                    dot(c.rgb, vec3(0.393, 0.769, 0.189)),
                    dot(c.rgb, vec3(0.349, 0.686, 0.168)),
                    dot(c.rgb, vec3(0.272, 0.534, 0.131))
                );
                FragColor = vec4(mix(c.rgb, sep, uIntensity), c.a);
            }
        """.trimIndent()

        /** 색상 반전 */
        val FRAG_INVERT = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            out vec4 FragColor;
            void main() {
                vec4 c = texture(uTex, vUV);
                FragColor = vec4(mix(c.rgb, 1.0 - c.rgb, uIntensity), c.a);
            }
        """.trimIndent()

        /** GL 비네팅: 가장자리를 uColor 색으로 어둡게 */
        val FRAG_VIGNETTE_GL = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform vec4 uColor;
            out vec4 FragColor;
            void main() {
                vec4 c = texture(uTex, vUV);
                vec2 uv = (vUV - 0.5) * 2.0;
                float dist = dot(uv, uv);
                float vign = 1.0 - clamp(dist * uIntensity * 0.8, 0.0, 1.0);
                FragColor = vec4(mix(uColor.rgb, c.rgb, vign), c.a);
            }
        """.trimIndent()

        /** 필름 그레인: 시간 기반 랜덤 노이즈 */
        val FRAG_FILMGRAIN = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform float uTime;
            out vec4 FragColor;
            float rand(vec2 co) {
                return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
            }
            void main() {
                vec4 c = texture(uTex, vUV);
                float noise = rand(vUV + fract(uTime * 7.3)) * 2.0 - 1.0;
                c.rgb = clamp(c.rgb + noise * uIntensity * 0.18, 0.0, 1.0);
                FragColor = c;
            }
        """.trimIndent()

        /** 글리치: 수평 블록 분산 + 색수차 */
        val FRAG_GLITCH = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform float uIntensity;
            uniform float uTime;
            uniform vec2 uTexelSize;
            out vec4 FragColor;
            float rand(float n) { return fract(sin(n) * 43758.5453); }
            void main() {
                float t  = floor(uTime * 10.0);
                float bh = 0.04 + rand(t * 0.3) * 0.08;
                float block = floor(vUV.y / bh);
                float disp  = 0.0;
                if (rand(block + t) > (1.0 - uIntensity * 0.6)) {
                    disp = (rand(block + t + 1.0) - 0.5) * uIntensity * 0.06;
                }
                vec2 uv = vec2(fract(vUV.x + disp), vUV.y);
                float ca = uIntensity * uTexelSize.x * 4.0 * abs(disp) * 50.0;
                float r = texture(uTex, vec2(uv.x + ca, uv.y)).r;
                float g = texture(uTex, uv).g;
                float b = texture(uTex, vec2(uv.x - ca, uv.y)).b;
                FragColor = vec4(r, g, b, 1.0);
            }
        """.trimIndent()

        val FRAG_PASSTHROUGH = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uTex;
            out vec4 FragColor;
            void main() { FragColor = texture(uTex, vUV); }
        """.trimIndent()
    }

    // ── FBO / 텍스처 ──────────────────────────────────────────────────────────
    private var fboA = 0; private var texA = 0
    private var fboB = 0; private var texB = 0
    private var rbo  = 0  // depth+stencil (FBO-A에만 필요 — NanoVG stencil 사용)
    private var fbW  = 0; private var fbH = 0

    // ── 화면 쿼드 VAO ─────────────────────────────────────────────────────────
    private var quadVao = 0
    private var quadVbo = 0

    // ── 빌트인 셰이더 (lazy — GL 컨텍스트가 준비된 후 첫 사용 시 초기화) ───────
    private val lazyGrayscale   = lazy { Shader(VERT, FRAG_GRAYSCALE) }
    private val lazyFade        = lazy { Shader(VERT, FRAG_FADE) }
    private val lazyBlur        = lazy { Shader(VERT, FRAG_BLUR) }
    private val lazyCrt         = lazy { Shader(VERT, FRAG_CRT) }
    private val lazyBloom       = lazy { Shader(VERT, FRAG_BLOOM) }
    private val lazyPixelate    = lazy { Shader(VERT, FRAG_PIXELATE) }
    private val lazyChromab     = lazy { Shader(VERT, FRAG_CHROMAB) }
    private val lazySepia       = lazy { Shader(VERT, FRAG_SEPIA) }
    private val lazyInvert      = lazy { Shader(VERT, FRAG_INVERT) }
    private val lazyVignetteGl  = lazy { Shader(VERT, FRAG_VIGNETTE_GL) }
    private val lazyFilmgrain   = lazy { Shader(VERT, FRAG_FILMGRAIN) }
    private val lazyGlitch      = lazy { Shader(VERT, FRAG_GLITCH) }
    private val lazyPassthrough = lazy { Shader(VERT, FRAG_PASSTHROUGH) }

    // ── 커스텀 셰이더 캐시 (절대 경로 → Shader, 컴파일 실패 시 null) ─────────
    private val customCache = HashMap<String, Shader?>()

    // ── FBO 초기화 / 재생성 ───────────────────────────────────────────────────
    private fun ensureFbos(w: Int, h: Int) {
        if (w == fbW && h == fbH && fboA != 0) return
        destroyFbos()
        fbW = w; fbH = h

        // FBO-A : depth24+stencil8 RBO 포함 (NanoVG stencil 필요)
        fboA = glGenFramebuffers()
        texA = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texA)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        rbo = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, rbo)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h)
        glBindFramebuffer(GL_FRAMEBUFFER, fboA)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texA, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rbo)

        // FBO-B : 핑퐁용 색상 전용
        fboB = glGenFramebuffers()
        texB = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texB)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glBindFramebuffer(GL_FRAMEBUFFER, fboB)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texB, 0)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
        glBindRenderbuffer(GL_RENDERBUFFER, 0)
    }

    // ── 전체 화면 쿼드 VAO 초기화 ─────────────────────────────────────────────
    private fun ensureQuad() {
        if (quadVao != 0) return
        quadVao = glGenVertexArrays()
        quadVbo = glGenBuffers()
        glBindVertexArray(quadVao)
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        val verts = floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)
        glEnableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    // ── 공개 API ─────────────────────────────────────────────────────────────

    /** 씬 렌더링 전 호출 — FBO-A에 렌더링하도록 바인딩하고 클리어합니다. */
    fun beginCapture(w: Int, h: Int) {
        ensureFbos(w, h)
        ensureQuad()
        glBindFramebuffer(GL_FRAMEBUFFER, fboA)
        glViewport(0, 0, w, h)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)
    }

    /** 씬 렌더링 완료 후 호출 — 기본 프레임버퍼로 복귀합니다. */
    fun endCapture() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /**
     * 캡처된 씬에 [effects]를 순서대로 적용합니다.
     * 핑퐁 FBO를 사용하며 마지막 결과는 기본 프레임버퍼(화면)에 출력됩니다.
     *
     * @param timeSeconds 앱 시작 이후 경과 시간(초) — filmgrain/glitch 등 시간 기반 셰이더에서 사용
     */
    fun apply(effects: List<GlScreenEffectData>, w: Int, h: Int, timeSeconds: Float = 0f) {
        if (effects.isEmpty()) return
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        var srcTex    = texA
        var dstFbo    = fboB
        var dstIsPing = true

        val last = effects.size - 1
        for ((idx, eff) in effects.withIndex()) {
            if (idx == last) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                glViewport(0, 0, w, h)
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, dstFbo)
                glViewport(0, 0, w, h)
            }

            val shader = resolveShader(eff)
            shader.use()
            // 모든 셰이더에 공통 유니폼 전송 (사용하지 않는 것은 드라이버가 무시)
            shader.uniform1i("uTex",        0)
            shader.uniform1f("uIntensity",  eff.intensity)
            shader.uniform1f("uTime",       timeSeconds)
            shader.uniform2f("uTexelSize",  1f / w, 1f / h)
            shader.uniform4f("uColor",      eff.r, eff.g, eff.b, eff.a)

            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, srcTex)
            glBindVertexArray(quadVao)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            glBindVertexArray(0)

            if (idx != last) {
                srcTex    = if (dstIsPing) texB else texA
                dstFbo    = if (dstIsPing) fboA else fboB
                dstIsPing = !dstIsPing
            }
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        glUseProgram(0)
    }

    fun destroy() {
        destroyFbos()
        if (quadVao != 0) { glDeleteVertexArrays(quadVao); quadVao = 0 }
        if (quadVbo != 0) { glDeleteBuffers(quadVbo); quadVbo = 0 }
        if (lazyGrayscale.isInitialized())  lazyGrayscale.value.destroy()
        if (lazyFade.isInitialized())       lazyFade.value.destroy()
        if (lazyBlur.isInitialized())       lazyBlur.value.destroy()
        if (lazyCrt.isInitialized())        lazyCrt.value.destroy()
        if (lazyBloom.isInitialized())      lazyBloom.value.destroy()
        if (lazyPixelate.isInitialized())   lazyPixelate.value.destroy()
        if (lazyChromab.isInitialized())    lazyChromab.value.destroy()
        if (lazySepia.isInitialized())      lazySepia.value.destroy()
        if (lazyInvert.isInitialized())     lazyInvert.value.destroy()
        if (lazyVignetteGl.isInitialized()) lazyVignetteGl.value.destroy()
        if (lazyFilmgrain.isInitialized())  lazyFilmgrain.value.destroy()
        if (lazyGlitch.isInitialized())     lazyGlitch.value.destroy()
        if (lazyPassthrough.isInitialized()) lazyPassthrough.value.destroy()
        customCache.values.filterNotNull().forEach { it.destroy() }
        customCache.clear()
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    private fun resolveShader(eff: GlScreenEffectData): Shader = when (eff.type) {
        "grayscale"  -> lazyGrayscale.value
        "fade"       -> lazyFade.value
        "blur"       -> lazyBlur.value
        "crt"        -> lazyCrt.value
        "bloom"      -> lazyBloom.value
        "pixelate"   -> lazyPixelate.value
        "chromab"    -> lazyChromab.value
        "sepia"      -> lazySepia.value
        "invert"     -> lazyInvert.value
        "vignette_gl"-> lazyVignetteGl.value
        "filmgrain"  -> lazyFilmgrain.value
        "glitch"     -> lazyGlitch.value
        "shader"     -> {
            val file = eff.shaderFile ?: return lazyPassthrough.value
            val path = file.absolutePath
            if (!customCache.containsKey(path)) {
                customCache[path] = runCatching { Shader(VERT, file.readText()) }.getOrNull()
            }
            customCache[path] ?: lazyPassthrough.value
        }
        else -> lazyPassthrough.value
    }

    private fun destroyFbos() {
        if (fboA != 0) { glDeleteFramebuffers(fboA); fboA = 0 }
        if (texA != 0) { glDeleteTextures(texA);     texA = 0 }
        if (fboB != 0) { glDeleteFramebuffers(fboB); fboB = 0 }
        if (texB != 0) { glDeleteTextures(texB);     texB = 0 }
        if (rbo  != 0) { glDeleteRenderbuffers(rbo); rbo  = 0 }
        fbW = 0; fbH = 0
    }
}
