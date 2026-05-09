package io.github.jwyoon1220.app.ui

import io.github.jwyoon1220.core.song.SongManager
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

class SongImportDialog(private val songManager: SongManager) {

    fun show() {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

        val chooser = JFileChooser().apply {
            dialogTitle     = "Select Song Metadata JSON"
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter      = FileNameExtensionFilter("JSON Files (*.json)", "json")
        }

        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return

        val selected = chooser.selectedFile
        if (!selected.exists() || !selected.name.endsWith(".json", ignoreCase = true)) {
            JOptionPane.showMessageDialog(owner, "Please select a valid .json file.", "Invalid File", JOptionPane.ERROR_MESSAGE)
            return
        }

        runCatching {
            val destDir = File(songManager.workingDir, "songs").also { it.mkdirs() }

            // JSON 메타파일 복사
            Files.copy(selected.toPath(), File(destDir, selected.name).toPath(), StandardCopyOption.REPLACE_EXISTING)

            // 동명 리소스 폴더 복사 (exist check)
            val resourceFolder = File(selected.parentFile, selected.nameWithoutExtension)
            if (resourceFolder.exists() && resourceFolder.isDirectory) {
                copyDirectory(resourceFolder, File(destDir, selected.nameWithoutExtension))
            }

            songManager.refresh()

            JOptionPane.showMessageDialog(
                owner,
                "'${selected.nameWithoutExtension}' imported successfully!",
                "Import Complete",
                JOptionPane.INFORMATION_MESSAGE
            )
        }.onFailure { ex ->
            JOptionPane.showMessageDialog(owner, "Import failed: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun copyDirectory(src: File, dest: File) {
        dest.mkdirs()
        src.walkTopDown().forEach { file ->
            val target = File(dest, file.relativeTo(src).path)
            if (file.isDirectory) target.mkdirs()
            else Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
