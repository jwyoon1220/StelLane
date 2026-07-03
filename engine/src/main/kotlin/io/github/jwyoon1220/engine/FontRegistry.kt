package io.github.jwyoon1220.engine

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * NanoVG 폰트를 초기화·관리하는 싱글턴.
 * [loadAll] 을 vg 핸들이 생성된 직후 한 번만 호출하세요.
 */
object FontRegistry {
    private val log = LoggerFactory.getLogger(FontRegistry::class.java)

    private var regularId:    Int = -1
    private var boldId:       Int = -1
    private var semiBoldId:   Int = -1
    private var lightId:      Int = -1
    private var extraLightId: Int = -1

    // Inter (OFL) — Latin/숫자 전용 폰트. 점수, UI 숫자, 영문 레이블에 사용.
    private var interRegularId:    Int = -1
    private var interBoldId:       Int = -1
    private var interSemiBoldId:   Int = -1
    private var interMediumId:     Int = -1
    private var interLightId:      Int = -1
    private var interExtraLightId: Int = -1

    // 메모리에 고정(pin)된 폰트 버퍼 목록 (JVM GC 로부터 보호)
    private val pinnedBuffers = ObjectArrayList<ByteBuffer>()

    /**
     * 클래스패스(assets 모듈)에서 MaruBuri 폰트들을 로드해 NanoVG 에 등록합니다.
     * Renderer.init() 에서 glfw/OpenGL 컨텍스트가 생성된 후 호출해야 합니다.
     */
    fun loadAll(vg: Long) {
        regularId    = load(vg, "fonts/MaruBuri-Regular.ttf",    "regular")
        boldId       = load(vg, "fonts/MaruBuri-Bold.ttf",       "bold")
        semiBoldId   = load(vg, "fonts/MaruBuri-SemiBold.ttf",   "semibold")
        lightId      = load(vg, "fonts/MaruBuri-Light.ttf",      "light")
        extraLightId = load(vg, "fonts/MaruBuri-ExtraLight.ttf", "extralight")

        interRegularId    = load(vg, "fonts/Inter-Regular.ttf",    "inter-regular")
        interBoldId       = load(vg, "fonts/Inter-Bold.ttf",       "inter-bold")
        interSemiBoldId   = load(vg, "fonts/Inter-SemiBold.ttf",   "inter-semibold")
        interMediumId     = load(vg, "fonts/Inter-Medium.ttf",     "inter-medium")
        interLightId      = load(vg, "fonts/Inter-Light.ttf",      "inter-light")
        interExtraLightId = load(vg, "fonts/Inter-ExtraLight.ttf", "inter-extralight")

        log.info("[FontRegistry] MaruBuri: regular={} bold={} semiBold={} light={} extraLight={}",
            regularId, boldId, semiBoldId, lightId, extraLightId)
        log.info("[FontRegistry] Inter: regular={} bold={} semiBold={} medium={} light={} extraLight={}",
            interRegularId, interBoldId, interSemiBoldId, interMediumId, interLightId, interExtraLightId)
    }

    // ── MaruBuri DrawFont 생성 헬퍼 ────────────────────────────────────────
    fun regular   (size: Float): DrawFont = DrawFont(effectiveId(regularId),    size)
    fun bold      (size: Float): DrawFont = DrawFont(effectiveId(boldId),       size)
    fun semiBold  (size: Float): DrawFont = DrawFont(effectiveId(semiBoldId),   size)
    fun light     (size: Float): DrawFont = DrawFont(effectiveId(lightId),      size)
    fun extraLight(size: Float): DrawFont = DrawFont(effectiveId(extraLightId), size)

    val regular:    DrawFont get() = regular(12f)
    val bold:       DrawFont get() = bold(12f)
    val semiBold:   DrawFont get() = semiBold(12f)
    val light:      DrawFont get() = light(12f)
    val extraLight: DrawFont get() = extraLight(12f)

    // ── Inter DrawFont 생성 헬퍼 ───────────────────────────────────────────
    fun interRegular   (size: Float): DrawFont = DrawFont(effectiveId(interRegularId),    size)
    fun interBold      (size: Float): DrawFont = DrawFont(effectiveId(interBoldId),       size)
    fun interSemiBold  (size: Float): DrawFont = DrawFont(effectiveId(interSemiBoldId),   size)
    fun interMedium    (size: Float): DrawFont = DrawFont(effectiveId(interMediumId),     size)
    fun interLight     (size: Float): DrawFont = DrawFont(effectiveId(interLightId),      size)
    fun interExtraLight(size: Float): DrawFont = DrawFont(effectiveId(interExtraLightId), size)

    val interRegular:    DrawFont get() = interRegular(12f)
    val interBold:       DrawFont get() = interBold(12f)
    val interSemiBold:   DrawFont get() = interSemiBold(12f)
    val interMedium:     DrawFont get() = interMedium(12f)
    val interLight:      DrawFont get() = interLight(12f)
    val interExtraLight: DrawFont get() = interExtraLight(12f)

    // ── 내부 유틸 ───────────────────────────────────────────────────────────
    private fun effectiveId(id: Int): Int =
        if (id >= 0) id else regularId.coerceAtLeast(0)

    private fun load(vg: Long, path: String, name: String): Int {
        val bytes = runCatching {
            FontRegistry::class.java.classLoader
                .getResourceAsStream(path)
                ?.readBytes()
        }.getOrNull() ?: run {
            log.warn("[FontRegistry] 폰트 리소스를 찾을 수 없음: {}", path)
            return -1
        }

        // NanoVG 는 ByteBuffer 가 GC 되지 않아야 하므로 오프힙 메모리에 복사
        val buf = MemoryUtil.memAlloc(bytes.size)
        buf.put(bytes).flip()
        pinnedBuffers.add(buf)   // 해제하지 않음 (앱 종료까지 유지)

        val id = nvgCreateFontMem(vg, name, buf, false)
        if (id < 0) log.warn("[FontRegistry] 폰트 생성 실패: {} ({})", name, path)
        return id
    }
}
