package io.github.jwyoon1220.app.ui

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * GLFW 렌더 루프와 충돌하지 않도록 AWT EDT에서 네이티브 파일 다이얼로그를 실행합니다.
 */
object NativeFileDialog {

    fun chooseOpenFile(title: String, initialDir: File? = null, extensionsCsv: String? = null): File? {
        val exts = parseExtensions(extensionsCsv)
        val selected = showDialog(mode = FileDialog.LOAD, title = title, initialDir = initialDir, defaultFile = null, exts = exts)
            ?: return null
        return File(selected.first, selected.second)
    }

    fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: File? = null,
        requiredExtension: String? = null
    ): File? {
        val selected = showDialog(mode = FileDialog.SAVE, title = title, initialDir = initialDir, defaultFile = defaultFileName, exts = emptySet())
            ?: return null
        var out = File(selected.first, selected.second)
        val ext = requiredExtension?.trim()?.trimStart('.')
        if (!ext.isNullOrEmpty() && !out.name.endsWith(".$ext", ignoreCase = true)) {
            out = File(out.parentFile, "${out.name}.$ext")
        }
        return out
    }

    private fun showDialog(
        mode: Int,
        title: String,
        initialDir: File?,
        defaultFile: String?,
        exts: Set<String>
    ): Pair<String, String>? {
        var result: Pair<String, String>? = null
        val action = Runnable {
            val dlg = FileDialog(null as Frame?, title, mode).apply {
                if (initialDir != null) directory = initialDir.absolutePath
                if (!defaultFile.isNullOrBlank()) file = defaultFile
                if (exts.isNotEmpty()) {
                    filenameFilter = java.io.FilenameFilter { _, name ->
                        exts.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
                    }
                }
            }
            dlg.isVisible = true
            val name = dlg.file
            val dir = dlg.directory
            if (!name.isNullOrBlank() && !dir.isNullOrBlank()) {
                result = dir to name
            }
        }

        if (EventQueue.isDispatchThread()) action.run() else EventQueue.invokeAndWait(action)
        return result
    }

    private fun parseExtensions(csv: String?): Set<String> {
        if (csv.isNullOrBlank()) return emptySet()
        return csv.split(',')
            .map { it.trim().lowercase().trimStart('.', '*') }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
