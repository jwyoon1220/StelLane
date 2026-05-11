package io.github.jwyoon1220.app.ui.ingame

import io.github.jwyoon1220.core.data.Decoration
import java.io.File
import java.awt.Color

class DecorEditInGameWindow(
    val initial: Decoration,
    val songDir: File,
    val onSave: (Decoration) -> Unit
) : UIWindow("decor_edit", "장식 편집 - ${initial.id.ifEmpty { "New" }}", 300, 100, 400, 360) {
    
    private val tfId = UITextField(120, 10, 150, 24, initial.id)
    private val tfImage = UITextField(120, 40, 150, 24, initial.image)
    private val tfTimeMs = UITextField(120, 70, 150, 24, initial.timeMs.toString())
    private val tfDurMs = UITextField(120, 100, 150, 24, initial.durationMs.toString())
    private val tfX = UITextField(120, 130, 60, 24, initial.x.toString())
    private val tfY = UITextField(190, 130, 60, 24, initial.y.toString())
    private val tfW = UITextField(120, 160, 60, 24, initial.width.toString())
    private val tfH = UITextField(190, 160, 60, 24, initial.height.toString())
    private val tfScaleX = UITextField(120, 190, 60, 24, initial.scaleX.toString())
    private val tfScaleY = UITextField(190, 190, 60, 24, initial.scaleY.toString())
    private val tfDepth = UITextField(120, 220, 150, 24, initial.depth.toString())

    init {
        components.add(UILabel(20, 10, "ID"))
        components.add(tfId)
        
        components.add(UILabel(20, 40, "Image Path"))
        components.add(tfImage)
        components.add(UIButton(280, 40, 80, 24, "탐색") {
            val fc = javax.swing.JFileChooser(songDir).apply {
                dialogTitle = "이미지 선택"
                fileFilter  = javax.swing.filechooser.FileNameExtensionFilter("이미지", "png", "jpg", "jpeg", "webp")
            }
            val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            if (fc.showOpenDialog(owner) == javax.swing.JFileChooser.APPROVE_OPTION) {
                tfImage.text = fc.selectedFile.toRelativeString(songDir).replace("\\\\", "/")
            }
        })
        
        components.add(UILabel(20, 70, "Time (ms)"))
        components.add(tfTimeMs)
        
        components.add(UILabel(20, 100, "Duration (ms)"))
        components.add(tfDurMs)
        
        components.add(UILabel(20, 130, "X / Y"))
        components.add(tfX)
        components.add(tfY)
        
        components.add(UILabel(20, 160, "Width / Height"))
        components.add(tfW)
        components.add(tfH)
        
        components.add(UILabel(20, 190, "Scale X / Y"))
        components.add(tfScaleX)
        components.add(tfScaleY)

        components.add(UILabel(20, 220, "Depth"))
        components.add(tfDepth)
        
        components.add(UIButton(100, 280, 80, 30, "저장") {
            val dec = initial.copy(
                id = tfId.text.trim(),
                image = tfImage.text.trim(),
                timeMs = tfTimeMs.text.toLongOrNull() ?: initial.timeMs,
                durationMs = tfDurMs.text.toLongOrNull() ?: initial.durationMs,
                x = tfX.text.toFloatOrNull() ?: initial.x,
                y = tfY.text.toFloatOrNull() ?: initial.y,
                width = tfW.text.toFloatOrNull() ?: initial.width,
                height = tfH.text.toFloatOrNull() ?: initial.height,
                scaleX = tfScaleX.text.toFloatOrNull() ?: initial.scaleX,
                scaleY = tfScaleY.text.toFloatOrNull() ?: initial.scaleY,
                depth = tfDepth.text.toIntOrNull() ?: initial.depth
            )
            onSave(dec)
            UIManager.removeWindow(id)
        })
        components.add(UIButton(200, 280, 80, 30, "취소") {
            UIManager.removeWindow(id)
        })
    }
}

