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
            dialogTitle       = "곡 가져오기 — JSON 또는 ZIP 파일 선택"
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter        = FileNameExtensionFilter("곡 파일 (*.json, *.zip)", "json", "zip")
        }

        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile ?: return

        when {
            selected.name.endsWith(".zip", ignoreCase = true) -> importZip(selected, owner)
            selected.name.endsWith(".json", ignoreCase = true) -> importJson(selected, owner)
            else -> JOptionPane.showMessageDialog(owner,
                "JSON 또는 ZIP 파일을 선택해 주세요.", "파일 형식 오류", JOptionPane.ERROR_MESSAGE)
        }
    }

    // ── JSON + 폴더 가져오기 (기존 방식) ─────────────────────────────────────
    private fun importJson(selected: File, owner: java.awt.Window?) {
        runCatching {
            val destDir = File(songManager.workingDir, "songs").also { it.mkdirs() }
            Files.copy(selected.toPath(), File(destDir, selected.name).toPath(), StandardCopyOption.REPLACE_EXISTING)

            val resourceFolder = File(selected.parentFile, selected.nameWithoutExtension)
            if (resourceFolder.exists() && resourceFolder.isDirectory) {
                copyDirectory(resourceFolder, File(destDir, selected.nameWithoutExtension))
            }
            songManager.refresh()
            JOptionPane.showMessageDialog(owner,
                "'${selected.nameWithoutExtension}' 을 성공적으로 가져왔습니다!",
                "가져오기 완료", JOptionPane.INFORMATION_MESSAGE)
        }.onFailure { ex ->
            JOptionPane.showMessageDialog(owner, "가져오기 실패: ${ex.message}", "오류", JOptionPane.ERROR_MESSAGE)
        }
    }

    // ── ZIP 가져오기 ─────────────────────────────────────────────────────────
    private fun importZip(zipFile: File, owner: java.awt.Window?) {
        val songsDir = File(songManager.workingDir, "songs")
        runCatching {
            val ok = SongZipUtil.import(zipFile, songsDir) { songName ->
                JOptionPane.showConfirmDialog(owner,
                    "'$songName' 이 이미 있습니다. 덮어씌울까요?",
                    "충돌", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
            }
            if (ok) {
                songManager.refresh()
                JOptionPane.showMessageDialog(owner,
                    "ZIP 가져오기가 완료되었습니다.", "가져오기 완료", JOptionPane.INFORMATION_MESSAGE)
            } else {
                JOptionPane.showMessageDialog(owner,
                    "ZIP 가져오기가 취소되었습니다.", "취소", JOptionPane.INFORMATION_MESSAGE)
            }
        }.onFailure { ex ->
            JOptionPane.showMessageDialog(owner, "ZIP 가져오기 실패: ${ex.message}", "오류", JOptionPane.ERROR_MESSAGE)
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

