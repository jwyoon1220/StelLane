package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.render.RenderColor

/**
 * [OpenGLRenderable] Scene이 쓰는 저수준 배치 쿼드 렌더러 계약 — NanoVG/DrawContext 오버헤드를
 * 피해야 하는 고빈도 그리기(예: PlayScene의 노트 렌더링, 매 프레임 수백 개)를 위한 별도 경로입니다.
 * [io.github.jwyoon1220.engine.GlQuadBatchRenderer](OpenGL)와
 * [io.github.jwyoon1220.engine.vulkan.VulkanQuadBatchRenderer](Vulkan) 양쪽 백엔드가 구현합니다.
 */
interface QuadBatchRenderer {
    /** [textureId]가 -1(기본값)이면 각 백엔드의 기본 흰 텍스처(단색 채우기)를 씁니다. */
    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: RenderColor, textureId: Int = -1)

    /** [textureId]가 -1(기본값)이면 각 백엔드의 기본 흰 텍스처(단색 채우기)를 씁니다. */
    fun drawGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        topLeft: RenderColor, topRight: RenderColor, bottomRight: RenderColor, bottomLeft: RenderColor,
        textureId: Int = -1
    )

    /**
     * [textureId]로 지정한 텍스처를 색 보정 없이 그립니다. GL 백엔드에서는 원시 GL 텍스처 핸들을
     * 그대로 받습니다(예: [VideoBackground.getGlTextureId]) — Vulkan 백엔드는 이 정수 핸들 체계를
     * 공유하지 않으므로 지원하지 않고 경고 후 무시합니다(에디터의 비디오 미리보기 전용 — ImGui
     * 기반 에디터 UI와 마찬가지로 아직 Vulkan에서 지원하지 않는 known gap).
     */
    fun drawTexturedRect(x: Float, y: Float, w: Float, h: Float, textureId: Int)
}
