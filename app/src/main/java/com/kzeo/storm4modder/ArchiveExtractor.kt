package com.kzeo.storm4modder

import com.github.junrar.Junrar
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ArchiveExtractor {

    fun extractArchive(
        archiveFile: File,
        destDir: File,
        onLog: (String) -> Unit
    ): Boolean {
        if (!destDir.exists()) destDir.mkdirs()
        val ext = archiveFile.extension.lowercase()

        return when (ext) {
            "zip" -> extractZip(archiveFile, destDir, onLog)
            "7z" -> extract7z(archiveFile, destDir, onLog)
            "rar" -> extractRar(archiveFile, destDir, onLog)
            "xfbin", "nut", "nud", "awb", "acb", "hca", "cpk" -> {
                val target = File(destDir, archiveFile.name)
                archiveFile.copyTo(target, overwrite = true)
                onLog("[INFO] File aset tunggal disalin: ${archiveFile.name}")
                true
            }
            else -> {
                onLog("[ERROR] Format file tidak didukung: .$ext")
                false
            }
        }
    }

    private fun extractZip(zipFile: File, destDir: File, onLog: (String) -> Unit): Boolean {
        return try {
            var count = 0
            ZipArchiveInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("Zip Slip terdeteksi: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        count++
                    }
                    entry = zis.nextEntry
                }
            }
            onLog("[OK] ZIP diekstrak: $count file.")
            true
        } catch (e: Exception) {
            onLog("[ERROR] Gagal ekstrak ZIP: ${e.message}")
            false
        }
    }

    private fun extract7z(sevenZFile: File, destDir: File, onLog: (String) -> Unit): Boolean {
        return try {
            var count = 0
            SevenZFile.builder().setFile(sevenZFile).get().use { sz ->
                var entry = sz.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("7z Path Traversal terdeteksi: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(8192)
                            var bytesRead: Int
                            while (sz.read(buf).also { bytesRead = it } != -1) {
                                fos.write(buf, 0, bytesRead)
                            }
                        }
                        count++
                    }
                    entry = sz.nextEntry
                }
            }
            onLog("[OK] 7Z diekstrak: $count file.")
            true
        } catch (e: Exception) {
            onLog("[ERROR] Gagal ekstrak 7Z: ${e.message}")
            false
        }
    }

    private fun extractRar(rarFile: File, destDir: File, onLog: (String) -> Unit): Boolean {
        return try {
            val extracted = Junrar.extract(rarFile, destDir)
            onLog("[OK] RAR diekstrak: ${extracted.size} item.")
            true
        } catch (e: Exception) {
            onLog("[ERROR] Gagal ekstrak RAR (pastikan bukan RAR5 terkunci): ${e.message}")
            false
        }
    }
}
