package io.github.jwyoon1220.app.ui

import io.github.jwyoon1220.core.data.SongEntry
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 곡 ZIP 내보내기 / 가져오기 유틸.
 *
 * ZIP 구조:
 *   <folderName>.json        ← 메타파일
 *   <folderName>/...         ← 리소스 파일 (커버, 오디오, 비디오, 채보, decoration.json …)
 */
object SongZipUtil {

    /**
     * [entry] 를 [destFile] (ZIP) 로 내보낸다.
     */
    fun export(entry: SongEntry, destFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destFile))).use { zos ->
            // 메타 JSON
            addFileToZip(zos, entry.metaFile, entry.metaFile.name)

            // 리소스 폴더
            if (entry.songDir.exists() && entry.songDir.isDirectory) {
                val base = entry.songDir.parentFile
                entry.songDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(base).path.replace('\\', '/')
                        addFileToZip(zos, file, relativePath)
                    }
                }
            }
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { fis -> fis.copyTo(zos) }
        zos.closeEntry()
    }

    /**
     * [zipFile] 을 [songsDir] 로 가져온다.
     *
     * @param onConflict 이미 같은 이름의 곡이 있을 때 덮어씌울지 여부를 묻는 콜백 (true = 덮어쓰기)
     * @return 성공 여부
     */
    fun import(zipFile: File, songsDir: File, onConflict: (songName: String) -> Boolean = { true }): Boolean {
        songsDir.mkdirs()

        // 1) ZIP 내 최상위 JSON 이름(곡명)을 먼저 확인
        var songFolderName: String? = null
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimEnd('/')
                if (!entry.isDirectory && !name.contains('/') && name.endsWith(".json")) {
                    songFolderName = name.removeSuffix(".json")
                    break
                }
                entry = zis.nextEntry
            }
        }

        if (songFolderName == null) return false

        // 2) 충돌 확인
        val existsMeta = File(songsDir, "$songFolderName.json").exists()
        val existsDir  = File(songsDir, songFolderName!!).exists()
        if ((existsMeta || existsDir) && !onConflict(songFolderName!!)) return false

        // 3) 실제 압축 해제
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val targetFile = File(songsDir, entry.name.replace('\\', '/'))
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return true
    }
}
