package io.github.jwyoon1220.app

import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * assets 모듈의 클래스패스에서 MaruBuri 폰트를 로드합니다.
 * 로드 실패 시 시스템 기본 폰트로 대체합니다.
 */
object FontLoader {

    private val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()

    val regular:    Font by lazy { load("fonts/MaruBuri-Regular.ttf")    ?: Font("SansSerif", Font.PLAIN, 12) }
    val bold:       Font by lazy { load("fonts/MaruBuri-Bold.ttf")       ?: Font("SansSerif", Font.BOLD,  12) }
    val semiBold:   Font by lazy { load("fonts/MaruBuri-SemiBold.ttf")   ?: Font("SansSerif", Font.BOLD,  12) }
    val light:      Font by lazy { load("fonts/MaruBuri-Light.ttf")      ?: Font("SansSerif", Font.PLAIN, 12) }
    val extraLight: Font by lazy { load("fonts/MaruBuri-ExtraLight.ttf") ?: Font("SansSerif", Font.PLAIN, 12) }

    private fun load(path: String): Font? = runCatching {
        FontLoader::class.java.classLoader
            .getResourceAsStream(path)
            ?.use { Font.createFont(Font.TRUETYPE_FONT, it).also { f -> ge.registerFont(f) } }
    }.getOrNull()

    fun regular(size: Float):    Font = regular.deriveFont(size)
    fun bold(size: Float):       Font = bold.deriveFont(size)
    fun semiBold(size: Float):   Font = semiBold.deriveFont(size)
    fun light(size: Float):      Font = light.deriveFont(size)
    fun extraLight(size: Float): Font = extraLight.deriveFont(size)
}
