package com.sofstos.sharecopy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShareTargetActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore

    // Режим "файлы" (обычное "Поделиться" файлом/файлами)
    private var fileSources: ArrayList<Uri> = ArrayList()

    // Режим "путь к папке" (текст, который распознан как существующая папка)
    private var folderPath: String? = null
    private var folderUseRoot: Boolean = false

    private lateinit var recycler: RecyclerView
    private lateinit var checkMove: CheckBox
    private lateinit var checkSubfolder: CheckBox
    private lateinit var textStatus: TextView
    private var isRunning = false
    private var sourceAppLabelCached: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_target)

        store = SettingsStore(this)

        recycler = findViewById(R.id.recyclerDestinations)
        recycler.layoutManager = LinearLayoutManager(this)
        checkMove = findViewById(R.id.checkMove)
        checkSubfolder = findViewById(R.id.checkSubfolder)
        textStatus = findViewById(R.id.textStatus)
        checkMove.isChecked = store.getLastMoveFlag()
        checkMove.setOnCheckedChangeListener { _, checked -> store.setLastMoveFlag(checked) }

        val rawText = extractSharedText(intent)
        if (rawText != null) {
            handleSharedText(rawText.trim())
            return
        }

        fileSources = extractFileSources(intent)
        if (fileSources.isEmpty()) {
            finish()
            return
        }
        setupSubfolderCheckbox()
        showDestinationPicker()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !isRunning) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Извлечение данных из intent ----------

    private fun extractFileSources(intent: Intent?): ArrayList<Uri> {
        val list = ArrayList<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let { list.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                uris?.let { list.addAll(it) }
            }
        }
        return list
    }

    /** Возвращает выделенный/присланный текст, если это текстовый share (а не файл). */
    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                cs?.toString()
            }
            Intent.ACTION_SEND -> {
                val hasStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) != null
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) != null
                if (hasStream) null else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
            else -> null
        }
    }

    private fun sourceAppLabel(): String {
        sourceAppLabelCached?.let { return it }
        val pkg = referrer?.host
        val label = if (pkg.isNullOrBlank()) {
            getString(R.string.unknown_app)
        } else {
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }
        }
        sourceAppLabelCached = label
        return label
    }

    // ---------- Обработка текста ----------

    private fun handleSharedText(text: String) {
        if (text.isBlank()) {
            finish()
            return
        }
        val looksLikePath = text.length < 4096 &&
            !text.contains('\n') &&
            text.startsWith("/")

        if (looksLikePath) {
            Thread {
                val existsNoRoot = RootHelper.isDirectory(text, false)
                val existsRoot = !existsNoRoot && RootHelper.isDirectory(text, true)
                runOnUiThread {
                    if (existsNoRoot || existsRoot) {
                        confirmFolderPath(text, useRoot = existsRoot)
                    } else {
                        proceedAsTextFile(text)
                    }
                }
            }.start()
        } else {
            proceedAsTextFile(text)
        }
    }

    private fun confirmFolderPath(path: String, useRoot: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_path_detected_title)
            .setMessage(getString(R.string.folder_path_detected_message, path))
            .setPositiveButton(R.string.btn_yes_folder) { _, _ ->
                folderPath = path
                folderUseRoot = useRoot
                checkSubfolder.visibility = View.GONE
                showDestinationPicker()
            }
            .setNegativeButton(R.string.btn_no_as_text) { _, _ -> proceedAsTextFile(path) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun proceedAsTextFile(text: String) {
        val label = sourceAppLabel()
        val safeLabel = FileCopyEngine.sanitizeName(label)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "${safeLabel}_$timestamp.txt"

        val dir = File(cacheDir, "text_share")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        try {
            file.writeText(text)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_write_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        fileSources = arrayListOf(uri)
        setupSubfolderCheckbox()
        showDestinationPicker()
    }

    // ---------- Общий UI выбора назначения ----------

    private fun setupSubfolderCheckbox() {
        val label = sourceAppLabel()
        checkSubfolder.visibility = View.VISIBLE
        checkSubfolder.text = getString(R.string.checkbox_subfolder_by_app, label)
        checkSubfolder.isChecked = true
    }

    private fun showDestinationPicker() {
        val destinations = store.getDestinations()
        if (destinations.isEmpty()) {
            Toast.makeText(this, R.string.error_no_destinations, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val adapter = DestinationAdapter(destinations, onClick = { dest ->
            if (!isRunning) startCopy(dest, checkMove.isChecked)
        })
        recycler.adapter = adapter
        recycler.post { recycler.getChildAt(0)?.requestFocus() }
    }

    private fun startCopy(dest: Destination, move: Boolean) {
        val opWord = getString(if (move) R.string.op_move else R.string.op_copy)
        val path = folderPath

        // SFTP/Mega — сетевые назначения, копирование может занять долгое время.
        // Запускаем через foreground-сервис с уведомлением и сразу закрываем этот экран —
        // не заставляем пользователя ждать, глядя на статичный текст "Копирование…".
        if (dest.type == DestType.SFTP || dest.type == DestType.MEGA) {
            if (path != null) {
                // Папка — источник уже реальный путь в файловой системе, content:// не участвует,
                // стейджинг не нужен, можно сразу передать в сервис.
                val intent = RcloneCopyService.buildFolderIntent(this, path, folderUseRoot, dest.id, move)
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, getString(R.string.toast_copy_started_background, opWord), Toast.LENGTH_LONG).show()
                finish()
            } else {
                val subfolder = if (checkSubfolder.visibility == View.VISIBLE && checkSubfolder.isChecked)
                    sourceAppLabel() else null
                stageAndStartRcloneCopy(dest, move, subfolder, opWord)
            }
            return
        }

        isRunning = true
        textStatus.visibility = View.VISIBLE

        if (path != null) {
            textStatus.text = getString(R.string.folder_copy_in_progress)
            Thread {
                val result = FileCopyEngine.runFolder(applicationContext, path, folderUseRoot, dest, move)
                runOnUiThread {
                    val msg = if (result.success)
                        getString(R.string.folder_result_ok, opWord, dest.name)
                    else
                        getString(R.string.folder_result_fail, opWord, result.message)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (result.success) VibrationHelper.success(this) else VibrationHelper.error(this)
                    finish()
                }
            }.start()
            return
        }

        textStatus.text = getString(R.string.copy_in_progress)
        val subfolder = if (checkSubfolder.visibility == View.VISIBLE && checkSubfolder.isChecked)
            sourceAppLabel() else null

        Thread {
            val result = FileCopyEngine.run(applicationContext, fileSources, dest, move, subfolder)
            runOnUiThread {
                val msg = when {
                    result.succeeded == result.total -> getString(R.string.copy_result_ok, opWord, result.succeeded, result.total, dest.name)
                    result.succeeded == 0 -> getString(R.string.copy_result_fail_all, opWord, result.total, dest.name)
                    else -> getString(R.string.copy_result_partial, opWord, result.succeeded, result.total, dest.name)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (result.succeeded == result.total) VibrationHelper.success(this) else VibrationHelper.error(this)
                if (result.notRemovedAfterMove.isNotEmpty()) {
                    Toast.makeText(this, R.string.copy_move_warning, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }

    /**
     * Читает все fileSources (content://) в реальные локальные temp-файлы ПОКА эта Activity ещё
     * жива (это быстрая локальная операция), и только потом запускает RcloneCopyService с уже
     * готовыми файлами. Так сервис никогда не работает с "чужими" content:// напрямую — раньше
     * Android иногда успевал отозвать разрешение на чтение такого URI сразу после закрытия этого
     * экрана, и SFTP/Mega-копирование проваливалось без понятной причины.
     */
    private fun stageAndStartRcloneCopy(dest: Destination, move: Boolean, subfolder: String?, opWord: String) {
        isRunning = true
        textStatus.visibility = View.VISIBLE
        textStatus.text = getString(R.string.preparing_files)

        Thread {
            val stagingDir = File(cacheDir, "network_stage/${System.nanoTime()}")
            stagingDir.mkdirs()
            val stagedNames = mutableListOf<String>()
            var anyFailed = false

            for (uri in fileSources) {
                try {
                    var displayName = FileCopyEngine.queryDisplayName(applicationContext, uri) ?: "file_${System.currentTimeMillis()}"
                    displayName = FileCopyEngine.maybeRenameScreenshot(applicationContext, uri, displayName)

                    // Уникальное имя внутри этой временной папки (на случай двух файлов с одинаковым именем в одной партии).
                    var candidateName = displayName
                    var i = 1
                    while (File(stagingDir, candidateName).exists()) {
                        val dot = displayName.lastIndexOf('.')
                        val base = if (dot > 0) displayName.substring(0, dot) else displayName
                        val ext = if (dot > 0) displayName.substring(dot) else ""
                        candidateName = "$base ($i)$ext"
                        i++
                    }

                    val destFile = File(stagingDir, candidateName)
                    val opened = contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
                        true
                    } ?: false

                    if (opened) {
                        stagedNames.add(candidateName)
                        if (move) FileCopyEngine.tryRemoveSource(applicationContext, uri)
                    } else {
                        anyFailed = true
                    }
                } catch (e: Exception) {
                    anyFailed = true
                }
            }

            runOnUiThread {
                if (stagedNames.isEmpty()) {
                    Toast.makeText(this, R.string.error_prepare_files_failed, Toast.LENGTH_LONG).show()
                    stagingDir.deleteRecursively()
                    finish()
                    return@runOnUiThread
                }

                val intent = RcloneCopyService.buildStagedFilesIntent(
                    this, stagingDir.absolutePath, stagedNames, dest.id, move, subfolder
                )
                ContextCompat.startForegroundService(this, intent)

                if (anyFailed) {
                    Toast.makeText(this, R.string.toast_some_files_prepare_failed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_copy_started_background, opWord), Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }
}
