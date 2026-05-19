package io.github.jwyoon1220.app.ui.ingame

import io.github.jwyoon1220.core.data.Decoration
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.awt.Color

/**
 * 장식 속성 편집 인게임 창.
 * Swing JFileChooser 대신 AWT FileDialog (네이티브 OS 파일 탐색기) 사용.
 */
class DecorEditInGameWindow(
    val initial: Decoration,
    val songDir: File,
    val onSave: (Decoration) -> Unit
) : UIWindow("decor_edit_${initial.id}", "장식 편집 — ${initial.id.ifEmpty { "New" }}", 200, 80, 440, 400) {

    private val tfId     = UITextField(110, 8,  200, 24, initial.id)
    private val tfImage  = UITextField(110, 38, 160, 24, initial.image)
    private val tfTimeMs = UITextField(110, 68, 130, 24, initial.timeMs.toString())
    private val tfDurMs  = UITextField(110, 98, 130, 24, initial.durationMs.toString())
    private val tfX      = UITextField(110, 128, 60, 24, initial.x.toString())
    private val tfY      = UITextField(180, 128, 60, 24, initial.y.toString())
    private val tfW      = UITextField(110, 158, 60, 24, initial.width.toString())
    private val tfH      = UITextField(180, 158, 60, 24, initial.height.toString())
    private val tfPivX   = UITextField(110, 188, 60, 24, initial.pivotX.toString())
    private val tfPivY   = UITextField(180, 188, 60, 24, initial.pivotY.toString())
    private val tfOpac   = UITextField(110, 218, 60, 24, initial.opacity.toString())
    private val tfRot    = UITextField(200, 218, 60, 24, initial.rotation.toString())
    private val tfDepth  = UITextField(110, 248, 60, 24, initial.depth.toString())

    init {
        fun lbl(x: Int, y: Int, text: String) = UILabel(x, y, text, Color(160, 135, 210))

        components += lbl(10,  8,  "ID")
        components += tfId
        components += lbl(10,  38, "Image")
        components += tfImage
        // 네이티브 파일 탐색기 버튼
        components += UIButton(278, 38, 70, 24, "탐색…") {
            val dlg = FileDialog(null as Frame?, "이미지 선택", FileDialog.LOAD).apply {
                directory = songDir.absolutePath
                file = "*.png;*.jpg;*.jpeg;*.webp"
            }
            dlg.isVisible = true
            val chosen = dlg.file
            if (chosen != null) {
                val fullPath = File(dlg.directory, chosen)
                tfImage.text = runCatching {
                    fullPath.toRelativeString(songDir).replace("\\", "/")
                }.getOrDefault(fullPath.absolutePath)
            }
        }

        components += lbl(10,  68,  "Time (ms)")
        components += tfTimeMs
        components += lbl(10,  98,  "Duration (ms)")
        components += tfDurMs
        components += lbl(10,  128, "X")
        components += tfX
        components += lbl(170, 128, "Y")
        components += tfY
        components += lbl(10,  158, "Width")
        components += tfW
        components += lbl(170, 158, "Height")
        components += tfH
        components += lbl(10,  188, "Pivot X")
        components += tfPivX
        components += lbl(170, 188, "Pivot Y")
        components += tfPivY
        components += lbl(10,  218, "Opacity")
        components += tfOpac
        components += lbl(190, 218, "Rotation")
        components += tfRot
        components += lbl(10,  248, "Depth")
        components += tfDepth

        // ── 저장 / 취소 버튼 ────────────────────────────────────────────────
        components += UIButton(60, 305, 110, 30, "저장") {
            val dec = initial.copy(
                id         = tfId.text.trim(),
                image      = tfImage.text.trim(),
                timeMs     = tfTimeMs.text.toLongOrNull()  ?: initial.timeMs,
                durationMs = tfDurMs.text.toLongOrNull()   ?: initial.durationMs,
                x          = tfX.text.toFloatOrNull()      ?: initial.x,
                y          = tfY.text.toFloatOrNull()      ?: initial.y,
                width      = tfW.text.toFloatOrNull()      ?: initial.width,
                height     = tfH.text.toFloatOrNull()      ?: initial.height,
                pivotX     = tfPivX.text.toFloatOrNull()   ?: initial.pivotX,
                pivotY     = tfPivY.text.toFloatOrNull()   ?: initial.pivotY,
                opacity    = tfOpac.text.toFloatOrNull()   ?: initial.opacity,
                rotation   = tfRot.text.toFloatOrNull()    ?: initial.rotation,
                depth      = tfDepth.text.toIntOrNull()    ?: initial.depth
            )
            onSave(dec)
            UIManager.removeWindow(id)
        }
        components += UIButton(190, 305, 110, 30, "취소") {
            UIManager.removeWindow(id)
        }
    }
}
