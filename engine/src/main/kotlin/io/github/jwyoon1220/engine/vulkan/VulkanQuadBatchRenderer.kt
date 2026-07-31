package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.QuadBatchRenderer
import io.github.jwyoon1220.engine.render.RenderColor
import org.slf4j.LoggerFactory

/**
 * [QuadBatchRenderer]의 Vulkan 구현 — [Vulkan2DBatcher]에 MODE_SOLID 정점을 밀어 넣습니다.
 * 단색/그라디언트 쿼드는 텍스처를 샘플링하지 않으므로([Vulkan2DPipeline]의 프래그먼트 셰이더가
 * `vMode < 0.5`일 때 `uTex`를 아예 참조하지 않음) 현재 바인딩된 텍스처와 무관하게 그릴 수 있습니다.
 *
 * [drawTexturedRect]와 GL 텍스처 핸들을 받는 [drawRect]/[drawGradientRect]의 non-default
 * textureId 경로는 지원하지 않습니다 — GL 정수 텍스처 핸들 체계를 Vulkan 디스크립터 셋으로
 * 매핑할 방법이 없고, 실제 사용처(EditorScene의 비디오 미리보기)는 ImGui 기반 에디터 UI와
 * 마찬가지로 이미 Vulkan에서 지원하지 않는 known gap입니다.
 */
class VulkanQuadBatchRenderer(private val batcher: Vulkan2DBatcher) : QuadBatchRenderer {
    private val log = LoggerFactory.getLogger(VulkanQuadBatchRenderer::class.java)
    private var warnedTexturedRect = false

    override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: RenderColor, textureId: Int) {
        drawGradientRect(x, y, w, h, color, color, color, color, textureId)
    }

    override fun drawGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        topLeft: RenderColor, topRight: RenderColor, bottomRight: RenderColor, bottomLeft: RenderColor,
        textureId: Int
    ) {
        if (w <= 0f || h <= 0f) return
        if (textureId != -1 && !warnedTexturedRect) {
            warnedTexturedRect = true
            log.warn("[Vulkan] QuadBatchRenderer.drawGradientRect(textureId={})는 지원하지 않습니다 — 단색/그라디언트만 그립니다", textureId)
        }
        batcher.pushQuadGradient(
            x, y, w, h,
            floatArrayOf(topLeft.rf, topLeft.gf, topLeft.bf, topLeft.af),
            floatArrayOf(topRight.rf, topRight.gf, topRight.bf, topRight.af),
            floatArrayOf(bottomRight.rf, bottomRight.gf, bottomRight.bf, bottomRight.af),
            floatArrayOf(bottomLeft.rf, bottomLeft.gf, bottomLeft.bf, bottomLeft.af),
            Vulkan2DVertex.MODE_SOLID
        )
    }

    override fun drawTexturedRect(x: Float, y: Float, w: Float, h: Float, textureId: Int) {
        if (!warnedTexturedRect) {
            warnedTexturedRect = true
            log.warn("[Vulkan] QuadBatchRenderer.drawTexturedRect(textureId={})는 지원하지 않습니다(GL 텍스처 핸들 전용 — 에디터 비디오 미리보기 known gap)", textureId)
        }
    }
}
