package io.github.jwyoon1220.engine

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
import org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
import org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_KEY_HOME
import org.lwjgl.glfw.GLFW.GLFW_KEY_END
import org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP
import org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN
import org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_MOD_ALT
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.lwjgl.glfw.GLFW.GLFW_REPEAT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE

/**
 * GLFW 키/마우스 상수를 모아둔 싱글턴.
 * java.awt.event.KeyEvent.VK_* 를 대체합니다.
 */
object Keys {
    // ── 특수 키 ──────────────────────────────────────────────────────────────
    const val SPACE     = GLFW_KEY_SPACE         // 32
    const val ESCAPE    = GLFW_KEY_ESCAPE        // 256
    const val ENTER     = GLFW_KEY_ENTER         // 257
    const val TAB       = GLFW_KEY_TAB           // 258
    const val BACKSPACE = GLFW_KEY_BACKSPACE     // 259
    const val DELETE    = GLFW_KEY_DELETE        // 261
    const val RIGHT     = GLFW_KEY_RIGHT         // 262
    const val LEFT      = GLFW_KEY_LEFT          // 263
    const val DOWN      = GLFW_KEY_DOWN          // 264
    const val UP        = GLFW_KEY_UP            // 265
    const val HOME      = GLFW_KEY_HOME          // 268
    const val END       = GLFW_KEY_END           // 269
    const val PAGE_UP   = GLFW_KEY_PAGE_UP       // 266
    const val PAGE_DOWN = GLFW_KEY_PAGE_DOWN     // 267
    const val LEFT_SHIFT    = GLFW_KEY_LEFT_SHIFT    // 340
    const val RIGHT_SHIFT   = GLFW_KEY_RIGHT_SHIFT   // 344
    const val LEFT_CONTROL  = GLFW_KEY_LEFT_CONTROL  // 341
    const val RIGHT_CONTROL = GLFW_KEY_RIGHT_CONTROL // 345

    // ── 문자 키 (GLFW 값 = ASCII 대문자) ─────────────────────────────────────
    const val A = 65; const val B = 66; const val C = 67; const val D = 68
    const val E = 69; const val F = 70; const val G = 71; const val H = 72
    const val I = 73; const val J = 74; const val K = 75; const val L = 76
    const val M = 77; const val N = 78; const val O = 79; const val P = 80
    const val Q = 81; const val R = 82; const val S = 83; const val T = 84
    const val U = 85; const val V = 86; const val W = 87; const val X = 88
    const val Y = 89; const val Z = 90

    // ── 숫자 키 ──────────────────────────────────────────────────────────────
    const val N0 = 48; const val N1 = 49; const val N2 = 50; const val N3 = 51
    const val N4 = 52; const val N5 = 53; const val N6 = 54; const val N7 = 55
    const val N8 = 56; const val N9 = 57

    // ── 기호 키 ──────────────────────────────────────────────────────────────
    const val MINUS  = GLFW_KEY_MINUS   // 45
    const val EQUAL  = GLFW_KEY_EQUAL   // 61 (+ 도 같은 키)
    const val PLUS   = GLFW_KEY_KP_ADD  // 334 (키패드 +)
    const val COMMA  = GLFW_KEY_COMMA   // 44
    const val PERIOD = GLFW_KEY_PERIOD  // 46

    // ── 마우스 버튼 ───────────────────────────────────────────────────────────
    const val MOUSE_LEFT   = GLFW_MOUSE_BUTTON_LEFT   // 0
    const val MOUSE_RIGHT  = GLFW_MOUSE_BUTTON_RIGHT  // 1
    const val MOUSE_MIDDLE = GLFW_MOUSE_BUTTON_MIDDLE // 2

    // ── 액션 ──────────────────────────────────────────────────────────────────
    const val PRESS   = GLFW_PRESS   // 1
    const val RELEASE = GLFW_RELEASE // 0
    const val REPEAT  = GLFW_REPEAT  // 2

    // ── 수정자 ────────────────────────────────────────────────────────────────
    fun isCtrl (mods: Int) = (mods and GLFW_MOD_CONTROL) != 0
    fun isShift(mods: Int) = (mods and GLFW_MOD_SHIFT)   != 0
    fun isAlt  (mods: Int) = (mods and GLFW_MOD_ALT)     != 0
}
