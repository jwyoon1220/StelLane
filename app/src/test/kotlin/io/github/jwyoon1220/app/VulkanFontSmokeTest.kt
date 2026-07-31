package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.RenderApi
import io.github.jwyoon1220.engine.WindowMode
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.render.RendererContext
import io.github.jwyoon1220.engine.vulkan.VulkanBackend
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * Vulkan RenderCommand 실행 스모크 테스트 — engine 모듈의 VulkanContextSmokeTest와 달리
 * app 모듈에서 실행되므로 실제 assets 모듈의 폰트 리소스(MaruBuri/Pretendard/Inter TTF)에
 * 접근할 수 있습니다. engine 모듈 테스트는 이 리소스가 클래스패스에 없어 폰트 베이킹 자체를
 * 검증할 수 없었습니다.
 *
 * 실제 씬(더미 [Scene])이 [RenderCommand.LegacyDrawContext]로 도형/텍스트(라틴+한글)/그라디언트
 * 3종/이미지를 그리게 하고, [VulkanBackend]가 이걸 [io.github.jwyoon1220.engine.vulkan.VulkanDrawContext]로
 * 실제로 실행하는 경로(task #15가 연결한 진짜 파이프라인)를 검증합니다.
 *
 * Vulkan을 지원하지 않는 환경(헤드리스 CI 등)에서는 조용히 skip합니다.
 */
class VulkanFontSmokeTest {

    private class TestContentScene(private val designW: Float, private val designH: Float) : Scene() {
        private val testImage = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
            val g2 = createGraphics()
            g2.color = java.awt.Color(255, 209, 102, 255)
            g2.fillOval(0, 0, 64, 64)
            g2.dispose()
        }

        override fun enter() {
            super.enter()
            register(object : RenderProducer {
                override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
                override fun produce(world: World, out: MutableList<RenderCommand>) {
                    out.add(RenderCommand.LegacyDrawContext {
                        val g: DrawContext = this
                        g.renderColor = RenderColor.of(255, 107, 157)
                        g.fillRoundRect(designW / 2f - 150f, designH / 2f - 100f, 300f, 120f, 24f)
                        g.renderColor = RenderColor.of(59, 206, 242)
                        g.fillCircle(designW / 2f, designH / 2f + 60f, 50f)
                        g.stroke = java.awt.BasicStroke(3f)
                        g.renderColor = RenderColor.WHITE
                        g.drawLine(0f, 0f, designW, designH)
                        g.drawRect(20f, 20f, designW - 40f, designH - 40f)

                        g.font = FontRegistry.interBold(48f)
                        g.renderColor = RenderColor.WHITE
                        g.drawStringCentered("StelLane Vulkan", designW / 2f, 80f)
                        g.font = FontRegistry.pretendardRegular(28f)
                        g.drawStringCentered("한글 SDF 텍스트 테스트", designW / 2f, 130f)

                        g.fillLinearGradient(
                            60f, designH - 220f, 260f, 80f, 60f, designH - 220f, 60f, designH - 140f,
                            RenderColor.of(255, 107, 157), RenderColor.of(59, 206, 242)
                        )
                        g.fillRadialGradient(
                            340f, designH - 220f, 120f, 120f, 400f, designH - 180f, 10f, 60f,
                            RenderColor.WHITE, RenderColor.of(120, 60, 220, 0)
                        )
                        g.fillBoxGradientRect(
                            designW - 260f, designH - 220f, 160f, 80f, 16f, 24f,
                            RenderColor.of(163, 112, 247, 200), RenderColor.of(163, 112, 247, 0)
                        )
                        g.drawImage(testImage, designW / 2f - 32f, designH - 90f, 64f, 64f)
                    })
                }
            })
        }
    }

    @Test
    fun `renders shapes, gradients, images, and Latin+Korean text through real RenderCommand execution`() {
        assumeTrue(GLFWWindow.isVulkanSupported(), "이 환경은 Vulkan을 지원하지 않습니다 — 스모크 테스트를 건너뜁니다")

        val window = GLFWWindow.createWindow(
            title  = "StelLane Vulkan Font Smoke Test",
            width  = 800,
            height = 600,
            mode   = WindowMode.WINDOWED,
            api    = RenderApi.VULKAN
        )

        try {
            val backend = VulkanBackend(enableValidation = true)
            backend.init(RendererContext(window = window, designWidth = 1280, designHeight = 720))
            val scene = TestContentScene(1280f, 720f)
            scene.enter()

            repeat(10) {
                backend.renderFrame(scene, window.framebufferWidth, window.framebufferHeight, 1f, 0f, 0f)
            }

            backend.destroy()
        } finally {
            window.destroy()
        }
    }
}
