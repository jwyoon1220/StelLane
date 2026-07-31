package io.github.jwyoon1220.engine

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.lwjgl.nanovg.NanoVG.nvgCreateFontMem
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * 폰트 TTF 바이트 + 백엔드 무관 "슬롯" 인덱스를 관리하는 싱글턴.
 *
 * [DrawFont.id]는 더 이상 NanoVG 폰트 핸들이 아니라 이 객체가 매기는 **슬롯 인덱스**입니다
 * (예: [REGULAR]=0, [BOLD]=1, ...). 실제 백엔드별 핸들(NanoVG 폰트 id, Vulkan SDF 아틀라스 등)은
 * 각 백엔드가 슬롯 인덱스를 키로 지연 생성/캐싱합니다 — [nvgFontId]가 NanoVG 쪽 예시입니다.
 * 이렇게 분리해야 NanoVG가 초기화되지 않은 백엔드(Vulkan)에서도 `DrawFont`가 어떤 폰트 파일을
 * 가리키는지 알 수 있습니다.
 */
object FontRegistry {
    private val log = LoggerFactory.getLogger(FontRegistry::class.java)

    // ── 폰트 슬롯 (DrawFont.id 값) ────────────────────────────────────────────
    const val REGULAR = 0
    const val BOLD = 1
    const val SEMIBOLD = 2
    const val LIGHT = 3
    const val EXTRALIGHT = 4
    const val PRETENDARD_REGULAR = 5
    const val PRETENDARD_BOLD = 6
    const val PRETENDARD_SEMIBOLD = 7
    const val PRETENDARD_LIGHT = 8
    const val PRETENDARD_EXTRALIGHT = 9
    const val INTER_REGULAR = 10
    const val INTER_BOLD = 11
    const val INTER_SEMIBOLD = 12
    const val INTER_MEDIUM = 13
    const val INTER_LIGHT = 14
    const val INTER_EXTRALIGHT = 15
    private const val SLOT_COUNT = 16

    private val paths = arrayOf(
        "fonts/MaruBuri-Regular.ttf", "fonts/MaruBuri-Bold.ttf", "fonts/MaruBuri-SemiBold.ttf",
        "fonts/MaruBuri-Light.ttf", "fonts/MaruBuri-ExtraLight.ttf",
        "fonts/Pretendard-Regular.ttf", "fonts/Pretendard-Bold.ttf", "fonts/Pretendard-SemiBold.ttf",
        "fonts/Pretendard-Light.ttf", "fonts/Pretendard-ExtraLight.ttf",
        "fonts/Inter-Regular.ttf", "fonts/Inter-Bold.ttf", "fonts/Inter-SemiBold.ttf",
        "fonts/Inter-Medium.ttf", "fonts/Inter-Light.ttf", "fonts/Inter-ExtraLight.ttf"
    )
    private val names = arrayOf(
        "regular", "bold", "semibold", "light", "extralight",
        "pretendard-regular", "pretendard-bold", "pretendard-semibold", "pretendard-light", "pretendard-extralight",
        "inter-regular", "inter-bold", "inter-semibold", "inter-medium", "inter-light", "inter-extralight"
    )

    // 메모리에 고정(pin)된 폰트 버퍼 (JVM GC 로부터 보호 — 앱 종료까지 해제하지 않음)
    private val fontBytes = arrayOfNulls<ByteBuffer>(SLOT_COUNT)
    private val pinnedBuffers = ObjectArrayList<ByteBuffer>()
    @Volatile private var bytesLoaded = false

    /** 슬롯 이름(로그/NanoVG 폰트 이름용). */
    fun nameOf(slot: Int): String = names.getOrElse(slot) { "regular" }

    /** 슬롯의 TTF 바이트를 반환합니다(리소스를 못 찾았으면 null). 백엔드 무관 — 최초 호출 시 전체 로드. */
    fun bytesOf(slot: Int): ByteBuffer? {
        ensureBytesLoaded()
        return fontBytes.getOrNull(slot)
    }

    @Synchronized
    private fun ensureBytesLoaded() {
        if (bytesLoaded) return
        for (i in 0 until SLOT_COUNT) {
            val bytes = runCatching {
                FontRegistry::class.java.classLoader.getResourceAsStream(paths[i])?.readBytes()
            }.getOrNull()
            if (bytes == null) {
                log.warn("[FontRegistry] 폰트 리소스를 찾을 수 없음: {}", paths[i])
                continue
            }
            // 오프힙 메모리에 복사 — NanoVG/Vulkan 모두 GC되지 않는 안정된 버퍼가 필요
            val buf = MemoryUtil.memAlloc(bytes.size)
            buf.put(bytes).flip()
            pinnedBuffers.add(buf)
            fontBytes[i] = buf
        }
        bytesLoaded = true
    }

    // ── NanoVG 전용: (vg, slot) → nvg 폰트 id 지연 캐시 ─────────────────────
    // 현재 구조상 NanoVG 컨텍스트가 동시에 여러 개 존재하지 않으므로 vg 하나만 캐싱합니다.
    private var cachedVg: Long = -1L
    private val nvgIdCache = IntArray(SLOT_COUNT) { -1 }

    /** [slot]에 대응하는 NanoVG 폰트 id를 반환합니다. 이 [vg]에서 처음 쓰이면 등록합니다. */
    fun nvgFontId(vg: Long, slot: Int): Int {
        if (vg != cachedVg) { nvgIdCache.fill(-1); cachedVg = vg }
        val cached = nvgIdCache.getOrElse(slot) { -1 }
        if (cached >= 0) return cached
        val bytes = bytesOf(slot)
        if (bytes == null) {
            if (slot != REGULAR) return nvgFontId(vg, REGULAR) // 없으면 regular로 폴백
            return -1
        }
        val id = nvgCreateFontMem(vg, nameOf(slot), bytes, false)
        if (id < 0) log.warn("[FontRegistry] NanoVG 폰트 생성 실패: slot={} ({})", slot, paths.getOrNull(slot))
        nvgIdCache[slot] = id
        return id
    }

    /** 이 [vg]에 모든 슬롯을 미리 등록합니다(하위 호환 — [NanoVGBackend.init]에서 호출). */
    fun loadAll(vg: Long) {
        ensureBytesLoaded()
        for (slot in 0 until SLOT_COUNT) nvgFontId(vg, slot)
        log.info("[FontRegistry] 폰트 {}개 로드 완료 (vg=0x{})", SLOT_COUNT, java.lang.Long.toHexString(vg))
    }

    // ── MaruBuri DrawFont 생성 헬퍼 ────────────────────────────────────────
    fun regular   (size: Float): DrawFont = DrawFont(REGULAR, size)
    fun bold      (size: Float): DrawFont = DrawFont(BOLD, size)
    fun semiBold  (size: Float): DrawFont = DrawFont(SEMIBOLD, size)
    fun light     (size: Float): DrawFont = DrawFont(LIGHT, size)
    fun extraLight(size: Float): DrawFont = DrawFont(EXTRALIGHT, size)

    val regular:    DrawFont get() = regular(12f)
    val bold:       DrawFont get() = bold(12f)
    val semiBold:   DrawFont get() = semiBold(12f)
    val light:      DrawFont get() = light(12f)
    val extraLight: DrawFont get() = extraLight(12f)

    // ── Pretendard DrawFont 생성 헬퍼 ─────────────────────────────────────
    fun pretendardRegular   (size: Float): DrawFont = DrawFont(PRETENDARD_REGULAR, size)
    fun pretendardBold      (size: Float): DrawFont = DrawFont(PRETENDARD_BOLD, size)
    fun pretendardSemiBold  (size: Float): DrawFont = DrawFont(PRETENDARD_SEMIBOLD, size)
    fun pretendardLight     (size: Float): DrawFont = DrawFont(PRETENDARD_LIGHT, size)
    fun pretendardExtraLight(size: Float): DrawFont = DrawFont(PRETENDARD_EXTRALIGHT, size)

    val pretendardRegular:    DrawFont get() = pretendardRegular(12f)
    val pretendardBold:       DrawFont get() = pretendardBold(12f)
    val pretendardSemiBold:   DrawFont get() = pretendardSemiBold(12f)
    val pretendardLight:      DrawFont get() = pretendardLight(12f)
    val pretendardExtraLight: DrawFont get() = pretendardExtraLight(12f)

    // ── Inter DrawFont 생성 헬퍼 ───────────────────────────────────────────
    fun interRegular   (size: Float): DrawFont = DrawFont(INTER_REGULAR, size)
    fun interBold      (size: Float): DrawFont = DrawFont(INTER_BOLD, size)
    fun interSemiBold  (size: Float): DrawFont = DrawFont(INTER_SEMIBOLD, size)
    fun interMedium    (size: Float): DrawFont = DrawFont(INTER_MEDIUM, size)
    fun interLight     (size: Float): DrawFont = DrawFont(INTER_LIGHT, size)
    fun interExtraLight(size: Float): DrawFont = DrawFont(INTER_EXTRALIGHT, size)

    val interRegular:    DrawFont get() = interRegular(12f)
    val interBold:       DrawFont get() = interBold(12f)
    val interSemiBold:   DrawFont get() = interSemiBold(12f)
    val interMedium:     DrawFont get() = interMedium(12f)
    val interLight:      DrawFont get() = interLight(12f)
    val interExtraLight: DrawFont get() = interExtraLight(12f)
}
