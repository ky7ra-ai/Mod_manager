package com.kzeo.storm4modder

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class RoutedModFile(
    val sourceFile: File,
    val relativeTargetPath: String // e.g. "data_win32/spc/2nrtbod1.xfbin"
)

data class AnalysisResult(
    val routedFiles: List<RoutedModFile>,
    val skippedFiles: List<String>,
    val detectedVariantFolders: List<String>
)

object ModRouterEngine {

    private val BLOCKED_EXTENSIONS = setOf(
        "dll", "exe", "asi", "bat", "cmd", "vbs", "ps1", "sh",
        "txt", "md", "url", "lnk", "ini", "cfg", "log",
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "pdf", "docx"
    )

    private val BLOCKED_NAMES = setOf(
        "d3d9.dll", "d3d11.dll", "dxgi.dll", "opengl32.dll",
        "xinput1_3.dll", "xinput1_4.dll", "moddingapi.dll", "reshade.ini"
    )

    private val ALLOWED_GAME_EXTENSIONS = setOf(
        "xfbin", "nut", "nud", "awb", "acb", "hca", "cpk", "usm", "bin", "dat"
    )

    /**
     * Menganalisis isi folder hasil ekstrak dari Nexus Mods dan memetakan
     * setiap file aset valid ke struktur standar `data_win32/...`.
     */
    fun analyzeAndRoute(
        extractedRoot: File,
        selectedVariantPrefix: String? = null,
        onLog: (String) -> Unit
    ): AnalysisResult {
        val allFiles = extractedRoot.walkTopDown().filter { it.isFile }.toList()
        val routed = mutableListOf<RoutedModFile>()
        val skipped = mutableListOf<String>()

        // Cari semua direktori bernama data_win32 atau data
        val dataRoots = extractedRoot.walkTopDown()
            .filter { it.isDirectory && (it.name.equals("data_win32", true) || it.name.equals("data", true)) }
            .toList()

        val variantNames = dataRoots.mapNotNull { dir ->
            val relParent = dir.parentFile?.relativeTo(extractedRoot)?.path ?: ""
            relParent.ifEmpty { null }
        }.distinct()

        for (file in allFiles) {
            val relFromExtract = file.relativeTo(extractedRoot).path.replace('\\', '/')
            val lowerName = file.name.lowercase()
            val ext = file.extension.lowercase()

            // Filter varian jika user memilih opsi spesifik
            if (!selectedVariantPrefix.isNullOrBlank()) {
                val belongsToVariant = relFromExtract.startsWith(selectedVariantPrefix) ||
                        !variantNames.any { relFromExtract.startsWith(it) }
                if (!belongsToVariant) {
                    continue
                }
            }

            // Sanitasi file PC-only / injector / dokumentasi
            if (lowerName in BLOCKED_NAMES || ext in BLOCKED_EXTENSIONS ||
                relFromExtract.contains("moddingapi", ignoreCase = true) ||
                relFromExtract.contains("reshade", ignoreCase = true)
            ) {
                skipped.add(relFromExtract)
                continue
            }

            if (ext !in ALLOWED_GAME_EXTENSIONS) {
                skipped.add(relFromExtract)
                continue
            }

            val mappedPath = resolveTargetRelativePath(relFromExtract, file.name)
            routed.add(RoutedModFile(sourceFile = file, relativeTargetPath = mappedPath))
        }

        // Deduplikasi jika ada path target kembar (prioritaskan urutan terakhir)
        val dedupedMap = linkedMapOf<String, RoutedModFile>()
        for (item in routed) {
            dedupedMap[item.relativeTargetPath] = item
        }

        onLog("[ANALYZE] File valid terpetakan: ${dedupedMap.size} | File dibersihkan: ${skipped.size}")
        return AnalysisResult(
            routedFiles = dedupedMap.values.toList(),
            skippedFiles = skipped,
            detectedVariantFolders = variantNames
        )
    }

    private fun resolveTargetRelativePath(relPath: String, fileName: String): String {
        val segments = relPath.split("/")
        val dataWin32Idx = segments.indexOfFirst { it.equals("data_win32", ignoreCase = true) }
        if (dataWin32Idx != -1 && dataWin32Idx < segments.size - 1) {
            val subPath = segments.subList(dataWin32Idx + 1, segments.size).joinToString("/")
            return "data_win32/$subPath"
        }

        val dataIdx = segments.indexOfFirst { it.equals("data", ignoreCase = true) }
        if (dataIdx != -1 && dataIdx < segments.size - 1) {
            val subPath = segments.subList(dataIdx + 1, segments.size).joinToString("/")
            return "data_win32/$subPath"
        }

        // Jika mod hanya berisi folder spc / ui / sound / sim tanpa parent data_win32
        val knownSubRoots = setOf("spc", "ui", "sound", "sim", "stage", "movie", "patch", "launch")
        val subRootIdx = segments.indexOfFirst { it.lowercase() in knownSubRoots }
        if (subRootIdx != -1) {
            val subPath = segments.subList(subRootIdx, segments.size).joinToString("/")
            return "data_win32/$subPath"
        }

        // Auto-routing cerdas berdasarkan penamaan file CyberConnect2 NSUNS4
        val lower = fileName.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()

        return when {
            ext in setOf("awb", "acb", "hca") -> "data_win32/sound/PC/$fileName"
            ext == "usm" -> "data_win32/movie/$fileName"
            lower.startsWith("charsel") || lower.startsWith("cr_sel") -> "data_win32/ui/max/crsel/$fileName"
            lower.startsWith("cutin") || lower.startsWith("face") || lower.startsWith("gauge") -> "data_win32/ui/max/battle/$fileName"
            lower.startsWith("st_") || lower.startsWith("stage") -> "data_win32/stage/$fileName"
            lower.contains("prm") || lower.contains("cmn") -> "data_win32/spc/$fileName"
            else -> "data_win32/spc/$fileName"
        }
    }

    /**
     * Mode A: Memasang langsung ke folder game dengan backup otomatis & log manifest.
     */
    fun installDirectToGame(
        gameDir: File,
        modName: String,
        routedFiles: List<RoutedModFile>,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeModId = modName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
            val backupRoot = File(gameDir, "KZEO_Mod_Backups/${timestamp}_$safeModId")
            var backedUpCount = 0

            val installedRelPaths = JSONArray()

            for (item in routedFiles) {
                val destFile = File(gameDir, item.relativeTargetPath)
                destFile.parentFile?.mkdirs()

                if (destFile.exists()) {
                    val backupFile = File(backupRoot, item.relativeTargetPath)
                    backupFile.parentFile?.mkdirs()
                    destFile.copyTo(backupFile, overwrite = true)
                    backedUpCount++
                }

                item.sourceFile.copyTo(destFile, overwrite = true)
                installedRelPaths.put(item.relativeTargetPath)
                onLog("  -> ${item.relativeTargetPath}")
            }

            // Simpan catatan ke registry mod terpasang
            saveModRecord(
                gameDir = gameDir,
                id = "${timestamp}_$safeModId",
                modName = modName,
                backupPath = if (backedUpCount > 0) backupRoot.absolutePath else "",
                files = installedRelPaths
            )

            onLog("[SUCCESS] Mod '$modName' terpasang! (${routedFiles.size} file, $backedUpCount file lama di-backup)")
            true
        } catch (e: Exception) {
            onLog("[ERROR] Gagal install langsung: ${e.message}")
            false
        }
    }

    /**
     * Mode B: Membungkus ulang menjadi .zip bersih berstruktur root `data_win32/` + `mod.json`
     * agar lolos validasi Impor Mod bawaan Launcher Storm 4 Android.
     */
    fun repackForLauncher(
        outputZipFile: File,
        modName: String,
        routedFiles: List<RoutedModFile>,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            outputZipFile.parentFile?.mkdirs()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
                // Tulis mod.json metadata
                val meta = JSONObject().apply {
                    put("name", modName)
                    put("author", "NexusMods (Repacked by KZEO)")
                    put("version", "1.0")
                    put("target", "com.naruto.storm4nativo")
                    put("fileCount", routedFiles.size)
                }
                zos.putNextEntry(ZipEntry("mod.json"))
                zos.write(meta.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Tulis seluruh file dengan root `data_win32/...`
                for (item in routedFiles) {
                    val entryName = item.relativeTargetPath.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(item.sourceFile).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                    onLog("  + [ZIP] $entryName")
                }
            }
            onLog("[SUCCESS] ZIP Launcher siap: ${outputZipFile.absolutePath}")
            true
        } catch (e: Exception) {
            onLog("[ERROR] Gagal repack ZIP: ${e.message}")
            false
        }
    }

    private fun saveModRecord(
        gameDir: File,
        id: String,
        modName: String,
        backupPath: String,
        files: JSONArray
    ) {
        val registryFile = File(gameDir, "KZEO_Mod_Backups/installed_mods.json")
        registryFile.parentFile?.mkdirs()
        val rootArray = if (registryFile.exists()) {
            try {
                JSONArray(registryFile.readText())
            } catch (_: Exception) {
                JSONArray()
            }
        } else {
            JSONArray()
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("name", modName)
            put("backupPath", backupPath)
            put("installedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            put("files", files)
        }
        rootArray.put(entry)
        registryFile.writeText(rootArray.toString(2))
    }

    fun getInstalledMods(gameDir: File): List<JSONObject> {
        val registryFile = File(gameDir, "KZEO_Mod_Backups/installed_mods.json")
        if (!registryFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(registryFile.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun uninstallMod(gameDir: File, modId: String, onLog: (String) -> Unit): Boolean {
        val registryFile = File(gameDir, "KZEO_Mod_Backups/installed_mods.json")
        if (!registryFile.exists()) return false
        return try {
            val arr = JSONArray(registryFile.readText())
            val remaining = JSONArray()
            var targetObj: JSONObject? = null

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("id") == modId) {
                    targetObj = obj
                } else {
                    remaining.put(obj)
                }
            }

            if (targetObj == null) return false

            val files = targetObj.optJSONArray("files") ?: JSONArray()
            val backupPath = targetObj.optString("backupPath", "")
            val backupDir = if (backupPath.isNotEmpty()) File(backupPath) else null

            for (i in 0 until files.length()) {
                val relPath = files.getString(i)
                val targetFile = File(gameDir, relPath)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (backupDir != null && backupDir.exists()) {
                    val backupFile = File(backupDir, relPath)
                    if (backupFile.exists()) {
                        targetFile.parentFile?.mkdirs()
                        backupFile.copyTo(targetFile, overwrite = true)
                    }
                }
            }

            backupDir?.deleteRecursively()
            registryFile.writeText(remaining.toString(2))
            onLog("[RESTORED] Mod '${targetObj.optString("name")}' berhasil dihapus & backup dipulihkan.")
            true
        } catch (e: Exception) {
            onLog("[ERROR] Gagal uninstall mod: ${e.message}")
            false
        }
    }
}
