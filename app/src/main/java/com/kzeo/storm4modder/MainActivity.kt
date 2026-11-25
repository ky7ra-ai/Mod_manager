package com.kzeo.storm4modder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kzeo.storm4modder.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var stagedModFile: File? = null

    private val prefs by lazy {
        getSharedPreferences("kzeo_storm4_prefs", MODE_PRIVATE)
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStoragePermissionUI()
    }

    private val pickModLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            stageSelectedUri(uri)
        }
    }

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val resolved = resolveTreeUriToPath(uri)
            if (resolved != null) {
                binding.etGamePath.setText(resolved)
                verifyGameDirectory(resolved)
            } else {
                appendLog("[WARN] Tidak dapat mengonversi URI folder, silakan ketik path manual.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvConsole.movementMethod = ScrollingMovementMethod()

        val savedPath = prefs.getString("game_path", "") ?: ""
        if (savedPath.isNotEmpty()) {
            binding.etGamePath.setText(savedPath)
            verifyGameDirectory(savedPath)
        }

        setupListeners()
        updateStoragePermissionUI()
    }

    override fun onResume() {
        super.onResume()
        updateStoragePermissionUI()
    }

    private fun setupListeners() {
        binding.btnGrantStorage.setOnClickListener {
            requestAllFilesAccess()
        }

        binding.btnAutoScan.setOnClickListener {
            autoScanGameFolder()
        }

        binding.btnBrowseGame.setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        binding.btnPickMod.setOnClickListener {
            pickModLauncher.launch(arrayOf("*/*"))
        }

        binding.btnInstallDirect.setOnClickListener {
            processModPackage(mode = ProcessMode.DIRECT_INSTALL)
        }

        binding.btnRepackZip.setOnClickListener {
            processModPackage(mode = ProcessMode.REPACK_ZIP)
        }

        binding.btnManageInstalled.setOnClickListener {
            showInstalledModsDialog()
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvConsole.text = "[READY] Log dibersihkan.\n"
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateStoragePermissionUI() {
        val granted = hasStoragePermission()
        binding.cardPermission.visibility = if (granted) View.GONE else View.VISIBLE
        binding.tvStorageBadge.text = if (granted) "STORAGE: OK" else "STORAGE: LOCKED"
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun autoScanGameFolder() {
        if (!hasStoragePermission()) {
            requestAllFilesAccess()
            return
        }

        setBusy(true)
        appendLog("[SCAN] Memindai folder game Naruto Storm 4 di penyimpanan...")

        lifecycleScope.launch(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()
            val candidates = mutableListOf<File>()

            // Cek path umum terlebih dahulu
            val commonNames = listOf(
                "NarutoStorm4", "NSUNS4", "Storm4", "Naruto Storm 4",
                "Download/NarutoStorm4", "Games/NarutoStorm4"
            )
            for (name in commonNames) {
                val dir = File(root, name)
                if (isStorm4Directory(dir)) candidates.add(dir)
            }

            // Scan kedalaman 2 level dari /storage/emulated/0
            if (candidates.isEmpty()) {
                root.listFiles()?.forEach { level1 ->
                    if (level1.isDirectory && !level1.name.startsWith(".")) {
                        if (isStorm4Directory(level1)) candidates.add(level1)
                        level1.listFiles()?.forEach { level2 ->
                            if (level2.isDirectory && isStorm4Directory(level2)) {
                                candidates.add(level2)
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                setBusy(false)
                if (candidates.isEmpty()) {
                    appendLog("[WARN] Folder game tidak ditemukan otomatis. Silakan isi path manual.")
                } else if (candidates.size == 1) {
                    val found = candidates.first().absolutePath
                    binding.etGamePath.setText(found)
                    verifyGameDirectory(found)
                } else {
                    val paths = candidates.map { it.absolutePath }.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Pilih Folder Game Storm 4")
                        .setItems(paths) { _, which ->
                            binding.etGamePath.setText(paths[which])
                            verifyGameDirectory(paths[which])
                        }
                        .show()
                }
            }
        }
    }

    private fun isStorm4Directory(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return File(dir, "NSUNS4.exe").exists() ||
                File(dir, "data_win32").exists() ||
                File(dir, "data/launch/data1.cpk").exists() ||
                File(dir, "data1.cpk").exists()
    }

    private fun verifyGameDirectory(path: String): Boolean {
        val dir = File(path.trim())
        return if (dir.exists() && dir.isDirectory) {
            val hasExe = File(dir, "NSUNS4.exe").exists()
            val hasDataWin32 = File(dir, "data_win32").exists()
            prefs.edit().putString("game_path", dir.absolutePath).apply()
            binding.tvGameStatus.text =
                "Status: Valid (NSUNS4.exe=${if (hasExe) "YA" else "TIDAK"}, data_win32=${if (hasDataWin32) "ADA" else "AKAN DIBUAT"})"
            appendLog("[OK] Direktori game aktif: ${dir.absolutePath}")
            true
        } else {
            binding.tvGameStatus.text = "Status: Folder tidak ditemukan!"
            false
        }
    }

    private fun stageSelectedUri(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = queryDisplayName(uri) ?: "nexus_mod.zip"
                val stagingDir = File(cacheDir, "staged_input").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val dest = File(stagingDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                stagedModFile = dest

                withContext(Dispatchers.Main) {
                    setBusy(false)
                    val cleanTitle = fileName.substringBeforeLast('.')
                        .replace('_', ' ')
                        .replace('-', ' ')
                    binding.tvSelectedMod.text =
                        "File terpilih: $fileName (${dest.length() / 1024} KB)"
                    binding.etModName.setText(cleanTitle)
                    appendLog("[LOAD] Arsip dimuat: $fileName")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    appendLog("[ERROR] Gagal membaca file terpilih: ${e.message}")
                }
            }
        }
    }

    private fun processModPackage(mode: ProcessMode) {
        val inputFile = stagedModFile
        if (inputFile == null || !inputFile.exists()) {
            Toast.makeText(this, "Pilih file mod terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasStoragePermission()) {
            requestAllFilesAccess()
            return
        }

        val gamePathStr = binding.etGamePath.text.toString().trim()
        if (mode == ProcessMode.DIRECT_INSTALL && !verifyGameDirectory(gamePathStr)) {
            Toast.makeText(this, "Path folder game Storm 4 tidak valid!", Toast.LENGTH_SHORT).show()
            return
        }

        val modName = binding.etModName.text.toString().trim()
            .ifEmpty { inputFile.nameWithoutExtension }

        setBusy(true)
        appendLog("----------------------------------------")
        appendLog("[START] Memproses mod: $modName")

        lifecycleScope.launch(Dispatchers.IO) {
            val workDir = File(cacheDir, "extracted_work").apply {
                deleteRecursively()
                mkdirs()
            }

            val extracted = ArchiveExtractor.extractArchive(inputFile, workDir) { msg ->
                runOnUiThread { appendLog(msg) }
            }

            if (!extracted) {
                withContext(Dispatchers.Main) { setBusy(false) }
                return@launch
            }

            val initialAnalysis = ModRouterEngine.analyzeAndRoute(workDir, null) { msg ->
                runOnUiThread { appendLog(msg) }
            }

            if (initialAnalysis.routedFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    appendLog("[ABORT] Tidak ditemukan file aset Storm 4 (.xfbin/.nut/.awb) yang valid di arsip ini.")
                }
                return@launch
            }

            // Jika arsip punya lebih dari 1 subfolder varian mod, tanyakan ke user
            if (initialAnalysis.detectedVariantFolders.size > 1) {
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    val options = arrayOf("Pasang Semua Varian") + initialAnalysis.detectedVariantFolders.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Terdeteksi Beberapa Varian Mod")
                        .setItems(options) { _, idx ->
                            val chosenPrefix = if (idx == 0) null else options[idx]
                            executeFinalOutput(mode, workDir, gamePathStr, modName, chosenPrefix)
                        }
                        .show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    executeFinalOutput(mode, workDir, gamePathStr, modName, null)
                }
            }
        }
    }

    private fun executeFinalOutput(
        mode: ProcessMode,
        workDir: File,
        gamePathStr: String,
        modName: String,
        variantPrefix: String?
    ) {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val finalAnalysis = ModRouterEngine.analyzeAndRoute(workDir, variantPrefix) { msg ->
                runOnUiThread { appendLog(msg) }
            }

            if (finalAnalysis.skippedFiles.isNotEmpty()) {
                runOnUiThread {
                    appendLog("[CLEAN] Dilewati (${finalAnalysis.skippedFiles.size} file PC/non-aset): " +
                            finalAnalysis.skippedFiles.take(3).joinToString(", "))
                }
            }

            if (mode == ProcessMode.DIRECT_INSTALL) {
                ModRouterEngine.installDirectToGame(
                    gameDir = File(gamePathStr),
                    modName = modName,
                    routedFiles = finalAnalysis.routedFiles
                ) { msg ->
                    runOnUiThread { appendLog(msg) }
                }
            } else {
                val safeZipName = modName.replace(Regex("[^a-zA-Z0-9_-]"), "_") + "_Storm4Ready.zip"
                val outDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Storm4_Ready_Mods"
                )
                val outZip = File(outDir, safeZipName)
                ModRouterEngine.repackForLauncher(
                    outputZipFile = outZip,
                    modName = modName,
                    routedFiles = finalAnalysis.routedFiles
                ) { msg ->
                    runOnUiThread { appendLog(msg) }
                }
            }

            withContext(Dispatchers.Main) {
                setBusy(false)
            }
        }
    }

    private fun showInstalledModsDialog() {
        val gamePathStr = binding.etGamePath.text.toString().trim()
        val gameDir = File(gamePathStr)
        if (!gameDir.exists()) {
            Toast.makeText(this, "Set folder game Storm 4 terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }

        val installed = ModRouterEngine.getInstalledMods(gameDir)
        if (installed.isEmpty()) {
            Toast.makeText(this, "Belum ada mod yang terpasang via aplikasi ini.", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = installed.map { obj ->
            val name = obj.optString("name")
            val count = obj.optJSONArray("files")?.length() ?: 0
            val date = obj.optString("installedAt")
            "$name ($count file) - $date"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pilih Mod untuk Uninstall & Restore Backup")
            .setItems(labels) { _, idx ->
                val target = installed[idx]
                val modId = target.optString("id")
                lifecycleScope.launch(Dispatchers.IO) {
                    ModRouterEngine.uninstallMod(gameDir, modId) { msg ->
                        runOnUiThread { appendLog(msg) }
                    }
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    private fun resolveTreeUriToPath(uri: Uri): String? {
        val docId = uri.lastPathSegment ?: return null
        if (docId.startsWith("primary:")) {
            val rel = docId.removePrefix("primary:")
            return "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
        }
        return null
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnInstallDirect.isEnabled = !busy
        binding.btnRepackZip.isEnabled = !busy
        binding.btnPickMod.isEnabled = !busy
        binding.btnAutoScan.isEnabled = !busy
    }

    private fun appendLog(line: String) {
        binding.tvConsole.append("$line\n")
        val scrollAmount = (binding.tvConsole.layout?.getLineTop(binding.tvConsole.lineCount) ?: 0) -
                binding.tvConsole.height
        if (scrollAmount > 0) {
            binding.tvConsole.scrollTo(0, scrollAmount)
        }
    }

    private enum class ProcessMode {
        DIRECT_INSTALL,
        REPACK_ZIP
    }
}
