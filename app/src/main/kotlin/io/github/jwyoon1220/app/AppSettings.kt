package io.github.jwyoon1220.app

import java.util.prefs.Preferences

enum class WindowMode { WINDOWED, BORDERLESS, EXCLUSIVE }

/** 앱 전역 설정 — java.util.prefs 를 통해 OS 레지스트리/파일에 자동 저장됩니다. */
object AppSettings {
    private val prefs = Preferences.userNodeForPackage(AppSettings::class.java)

    var windowMode: WindowMode
        get() = try { WindowMode.valueOf(prefs.get("windowMode", WindowMode.BORDERLESS.name)) }
                catch (_: Exception) { WindowMode.BORDERLESS }
        set(v) { prefs.put("windowMode", v.name) }

    /** 오디오/비디오 보정 오프셋 (ms). 양수 = 노트를 더 일찍 표시. */
    var calibrationOffsetMs: Long
        get() = prefs.getLong("calibrationOffsetMs", 0L)
        set(v) { prefs.putLong("calibrationOffsetMs", v) }

    /** 목표 FPS 제한 (30~240). */
    var targetFps: Int
        get() = prefs.getInt("targetFps", 60).coerceIn(30, 240)
        set(v) { prefs.putInt("targetFps", v.coerceIn(30, 240)) }
}
