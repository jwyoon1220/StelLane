package io.github.jwyoon1220.engine.render

/**
 * 렌더러 백엔드 팩토리 — 이름으로 [RendererBackend] 구현체를 생성합니다.
 *
 * ## 사용법 — 백엔드 등록 (앱 초기화 시 1회)
 * ```kotlin
 * RendererFactory.register("nanovg") { ctx -> NanoVGBackend(ctx) }
 * RendererFactory.register("debug")  { _   -> DebugBackend()    }
 * ```
 *
 * ## 사용법 — 백엔드 생성
 * ```kotlin
 * val backendId = config.getString("renderer", "nanovg")
 * val backend   = RendererFactory.create(backendId, rendererContext)
 * ```
 *
 * ## 설계 원칙
 * - 등록과 생성을 분리해 새 백엔드를 팩토리 코드 수정 없이 추가할 수 있습니다.
 * - [RendererFactory]는 싱글톤 오브젝트입니다 — 앱 생명주기와 동일합니다.
 * - 스레드 안전하지 않습니다 (메인 스레드에서만 사용).
 */
object RendererFactory {

    private val registry = LinkedHashMap<String, (RendererContext) -> RendererBackend>()

    /**
     * 백엔드를 [id] 키로 등록합니다.
     * 같은 [id]로 다시 등록하면 기존 항목을 덮어씁니다.
     *
     * @param id      백엔드 식별자 (소문자 권장, 예: "nanovg")
     * @param creator [RendererContext]를 받아 새 [RendererBackend]를 반환하는 팩토리 람다
     */
    fun register(id: String, creator: (RendererContext) -> RendererBackend) {
        registry[id] = creator
    }

    /**
     * 등록된 백엔드 ID 목록을 반환합니다.
     * 구성 UI나 로그에서 사용 가능한 백엔드를 표시할 때 활용합니다.
     */
    fun available(): Set<String> = registry.keys.toSet()

    /**
     * [id]에 해당하는 [RendererBackend]를 생성합니다.
     *
     * @throws IllegalArgumentException [id]가 등록되어 있지 않은 경우
     */
    fun create(id: String, ctx: RendererContext): RendererBackend {
        val creator = registry[id]
            ?: throw IllegalArgumentException(
                "Unknown renderer backend '$id'. Available: ${available()}"
            )
        return creator(ctx)
    }

    /**
     * [id]가 등록되어 있으면 생성하고, 없으면 [fallbackId]로 시도합니다.
     * 둘 다 없으면 [IllegalArgumentException]을 던집니다.
     */
    fun createOrFallback(id: String, fallbackId: String, ctx: RendererContext): RendererBackend {
        return if (registry.containsKey(id)) create(id, ctx) else create(fallbackId, ctx)
    }

    /** 등록된 모든 백엔드를 제거합니다. 테스트 환경에서 격리에 사용합니다. */
    internal fun reset() = registry.clear()
}
