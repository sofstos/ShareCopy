package com.sofstos.sharecopy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var adapter: DestinationAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var copyProgressPanel: View
    private lateinit var copyProgressText: TextView
    private lateinit var copyProgressBar: ProgressBar
    private var unsubscribeProgress: (() -> Unit)? = null
    private var unsubscribeDetails: (() -> Unit)? = null
    private var detailsDialog: AlertDialog? = null
    private var detailsText: TextView? = null

    // Если не null — очередной результат SAF-пикера должен заменить treeUri
    // у уже существующей папки назначения с этим id (режим "Изменить папку (SAF)"),
    // а не создавать новую запись.
    private var pendingEditSafId: String? = null

    private val safPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val editId = pendingEditSafId
            if (editId != null) {
                pendingEditSafId = null
                val existing = store.getDestinations().firstOrNull { it.id == editId }
                if (existing != null) {
                    store.updateDestination(existing.copy(treeUri = uri.toString()))
                    refreshList()
                }
            } else {
                askNameAndSave(uri)
            }
        } else {
            pendingEditSafId = null
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* без разрешения сервис копирования просто не покажет уведомление, не критично */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        store = SettingsStore(this)
        recycler = findViewById(R.id.recyclerDestinations)
        copyProgressPanel = findViewById(R.id.copyProgressPanel)
        copyProgressText = findViewById(R.id.copyProgressText)
        copyProgressBar = findViewById(R.id.copyProgressBar)
        copyProgressPanel.setOnClickListener { showCopyDetailsDialog() }
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = DestinationAdapter(
            store.getDestinations(),
            onClick = { dest -> editDestination(dest) },
            onLongClick = { dest -> showDestinationActionsMenu(dest); true }
        )
        recycler.adapter = adapter

        findViewById<TextView>(R.id.btnMenu).setOnClickListener {
            showActionsMenu()
        }
        findViewById<android.widget.Button>(R.id.btnCopyFolder).setOnClickListener {
            startActivity(Intent(this, FolderBrowserActivity::class.java))
        }

        refreshList()
    }

    override fun onStart() {
        super.onStart()
        unsubscribeProgress = CopyProgressBus.subscribe { progress ->
            runOnUiThread { renderCopyProgress(progress) }
        }
    }

    override fun onStop() {
        unsubscribeProgress?.invoke()
        unsubscribeProgress = null
        super.onStop()
    }

    private fun renderCopyProgress(progress: CopyProgressOverview) {
        val show = progress.active || progress.instances.any { it.message.isNotBlank() }
        copyProgressPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val percent = if (progress.totalBytes > 0L)
            ((progress.copiedBytes * 100L) / progress.totalBytes).coerceIn(0L, 100L)
        else 0L
        val activeCount = progress.instances.count { it.active }
        copyProgressText.text = "Копирование: $activeCount активн. — ${formatBytes(progress.copiedBytes)} из ${formatBytes(progress.totalBytes)}, осталось ${formatBytes(progress.remainingBytes)}, ${formatSpeed(progress.speedBytesPerSecond)}"

        if (progress.totalBytes > 0L) {
            copyProgressBar.isIndeterminate = false
            copyProgressBar.progress = percent.toInt()
        } else {
            copyProgressBar.isIndeterminate = progress.active
            if (!progress.active) copyProgressBar.progress = 100
        }
        detailsText?.let { renderCopyDetails(progress, it) }
    }

    private fun showCopyDetailsDialog() {
        if (detailsDialog?.isShowing == true) return
        val initialOverview = CopyProgressBus.current()
        val text = TextView(this).apply {
            setPadding(32, 8, 32, 16)
            // AlertDialog использует светлую поверхность. Цвет text_primary приложения
            // белый (#FFFFFF), поэтому раньше содержимое было белым на белом фоне.
            setTextColor(getColor(android.R.color.black))
            textSize = 14f
            // На некоторых прошивках пустой child ScrollView получает высоту 0
            // во время первого измерения AlertDialog. Формируем содержимое заранее
            // и задаём минимальную высоту, чтобы последующий setText был виден.
            minHeight = (120 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        renderCopyDetails(initialOverview, text)
        detailsText = text
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(text)
        }
        detailsDialog = AlertDialog.Builder(this)
            .setTitle("Подробности копирования")
            .setView(scroll)
            .setPositiveButton(R.string.btn_close, null)
            .create()
        detailsDialog?.setOnDismissListener {
            unsubscribeDetails?.invoke()
            unsubscribeDetails = null
            detailsText = null
            detailsDialog = null
        }
        detailsDialog?.show()
        // Состояние могло измениться между initialOverview и созданием окна.
        renderCopyDetails(CopyProgressBus.current(), text)
        unsubscribeDetails = CopyProgressBus.subscribe { overview ->
            runOnUiThread { detailsText?.let { renderCopyDetails(overview, it) } }
        }
    }

    private fun renderCopyDetails(overview: CopyProgressOverview, target: TextView) {
        if (overview.instances.isEmpty()) {
            target.text = "Нет активных копирований"
            return
        }
        val builder = StringBuilder()
        overview.instances.forEachIndexed { index, instance ->
            val percent = if (instance.totalBytes > 0L)
                ((instance.copiedBytes * 100L) / instance.totalBytes).coerceIn(0L, 100L)
            else 0L
            builder.append("${index + 1}. ${instance.operation} → ${instance.destination}\n")
            builder.append("   ${if (instance.active) "Выполняется" else instance.message.ifBlank { "Завершено" }}; ")
            builder.append("$percent%, отправлено ${formatBytes(instance.copiedBytes)} из ${formatBytes(instance.totalBytes)}, ")
            builder.append("осталось ${formatBytes(instance.remainingBytes)}, ${formatSpeed(instance.speedBytesPerSecond)}\n")
            instance.items.forEach { item ->
                val state = when (item.state) {
                    CopyItemState.PENDING -> "ожидает"
                    CopyItemState.COPYING -> "копируется"
                    CopyItemState.DONE -> "готово"
                    CopyItemState.FAILED -> "ошибка"
                }
                builder.append("   • ${item.name}: $state, ${formatBytes(item.bytes)} из ${formatBytes(item.totalBytes)}\n")
                item.error?.takeIf { it.isNotBlank() }?.let { builder.append("     $it\n") }
            }
            if (index < overview.instances.lastIndex) builder.append("\n")
        }
        target.text = builder.toString()
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value Б"
        val units = arrayOf("КБ", "МБ", "ГБ")
        var number = value.toDouble()
        var unit = 0
        while (number >= 1024 && unit < units.lastIndex) { number /= 1024; unit++ }
        return String.format(java.util.Locale.US, "%.1f %s", number, units[unit])
    }

    private fun formatSpeed(value: Long): String = if (value > 0L) "${formatBytes(value)}/с" else "скорость —"

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val list = store.getDestinations()
        adapter.updateItems(list)
        findViewById<android.widget.TextView>(R.id.textEmpty).visibility =
            if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------- Меню действий (добавление папок, доступ ко всем файлам) ----------

    private fun showActionsMenu() {
        val view = layoutInflater.inflate(R.layout.dialog_menu_actions, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        val menuFm = view.findViewById<TextView>(R.id.menuItemPickFileManager)
        val currentFmLabel = store.getFixedFileManagerLabel() ?: getString(R.string.fixed_fm_not_set)
        menuFm.text = getString(R.string.menu_pick_file_manager) + ": " + currentFmLabel

        val menuLogFolder = view.findViewById<TextView>(R.id.menuItemLogFolder)
        val currentLogFolder = store.getLogFolderPath() ?: getString(R.string.log_folder_not_set)
        menuLogFolder.text = getString(R.string.menu_log_folder) + ": " + currentLogFolder

        val checkVibration = view.findViewById<CheckBox>(R.id.menuCheckVibration)
        checkVibration.isChecked = store.isVibrationEnabled()
        checkVibration.setOnCheckedChangeListener { _, checked -> store.setVibrationEnabled(checked) }

        view.findViewById<TextView>(R.id.menuItemAddSaf).setOnClickListener {
            dialog.dismiss()
            safPickerLauncher.launch(null)
        }
        view.findViewById<TextView>(R.id.menuItemAddManual).setOnClickListener {
            dialog.dismiss()
            showManualPathDialog()
        }
        view.findViewById<TextView>(R.id.menuItemAddSftp).setOnClickListener {
            dialog.dismiss()
            showSftpDialog(null)
        }
        view.findViewById<TextView>(R.id.menuItemAddMega).setOnClickListener {
            dialog.dismiss()
            showMegaDialog(null)
        }
        view.findViewById<TextView>(R.id.menuItemAllFilesAccess).setOnClickListener {
            dialog.dismiss()
            requestAllFilesAccess()
        }
        view.findViewById<TextView>(R.id.menuItemUsageAccess).setOnClickListener {
            dialog.dismiss()
            requestUsageAccess()
        }
        menuFm.setOnClickListener {
            dialog.dismiss()
            showFileManagerPickerDialog()
        }
        menuLogFolder.setOnClickListener {
            dialog.dismiss()
            showLogFolderDialog()
        }

        dialog.show()
    }

    private fun askNameAndSave(treeUri: Uri) {
        val input = EditText(this).apply { hint = getString(R.string.hint_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.hint_name)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text.toString().ifBlank { treeUri.lastPathSegment ?: "Папка" }
                store.addDestination(
                    Destination(name = name, type = DestType.SAF, treeUri = treeUri.toString())
                )
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showManualPathDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_manual_path, null)
        val editName = view.findViewById<EditText>(R.id.editName)
        val editPath = view.findViewById<EditText>(R.id.editPath)
        val checkRoot = view.findViewById<CheckBox>(R.id.checkUseRoot)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_manual_title)
            .setView(view)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val path = editPath.text.toString().trim()
                if (path.isBlank()) {
                    Toast.makeText(this, R.string.hint_path, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = editName.text.toString().ifBlank { path }
                val useRoot = checkRoot.isChecked
                if (useRoot && !RootHelper.isRootAvailable()) {
                    Toast.makeText(this, R.string.error_root_unavailable, Toast.LENGTH_LONG).show()
                }
                store.addDestination(
                    Destination(name = name, type = DestType.MANUAL, path = path, useRoot = useRoot)
                )
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------- Редактирование существующей папки назначения ----------

    private fun editDestination(dest: Destination) {
        when (dest.type) {
            DestType.MANUAL -> {
                val view = layoutInflater.inflate(R.layout.dialog_add_manual_path, null)
                val editName = view.findViewById<EditText>(R.id.editName)
                val editPath = view.findViewById<EditText>(R.id.editPath)
                val checkRoot = view.findViewById<CheckBox>(R.id.checkUseRoot)
                editName.setText(dest.name)
                editPath.setText(dest.path)
                checkRoot.isChecked = dest.useRoot

                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_edit_title)
                    .setView(view)
                    .setPositiveButton(R.string.btn_save) { _, _ ->
                        val path = editPath.text.toString().trim()
                        if (path.isBlank()) {
                            Toast.makeText(this, R.string.hint_path, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val name = editName.text.toString().ifBlank { path }
                        val useRoot = checkRoot.isChecked
                        if (useRoot && !RootHelper.isRootAvailable()) {
                            Toast.makeText(this, R.string.error_root_unavailable, Toast.LENGTH_LONG).show()
                        }
                        store.updateDestination(dest.copy(name = name, path = path, useRoot = useRoot))
                        refreshList()
                    }
                    .setNeutralButton(R.string.btn_delete) { _, _ -> confirmDelete(dest) }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
            DestType.SAF -> {
                val input = EditText(this).apply {
                    setText(dest.name)
                    hint = getString(R.string.hint_name)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_edit_title)
                    .setView(input)
                    .setPositiveButton(R.string.btn_save) { _, _ ->
                        val name = input.text.toString().ifBlank { dest.name }
                        store.updateDestination(dest.copy(name = name))
                        refreshList()
                    }
                    .setNeutralButton(R.string.btn_change_saf_folder) { _, _ ->
                        pendingEditSafId = dest.id
                        safPickerLauncher.launch(null)
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
            DestType.SFTP -> showSftpDialog(dest)
            DestType.MEGA -> showMegaDialog(dest)
        }
    }

    /**
     * Диалог добавления/редактирования SFTP-назначения (через rclone). Перед сохранением
     * проверяет соединение (в фоне) — если подключиться не удалось, диалог не закрывается и
     * показывается причина ошибки, чтобы не узнавать о неверном пароле/хосте только
     * в момент реальной передачи файла.
     */
    private fun showSftpDialog(existing: Destination?) {
        val view = layoutInflater.inflate(R.layout.dialog_add_sftp, null)
        val editName = view.findViewById<EditText>(R.id.editSftpName)
        val editHost = view.findViewById<EditText>(R.id.editSftpHost)
        val editPort = view.findViewById<EditText>(R.id.editSftpPort)
        val editUser = view.findViewById<EditText>(R.id.editSftpUser)
        val editPassword = view.findViewById<EditText>(R.id.editSftpPassword)
        val editRemotePath = view.findViewById<EditText>(R.id.editSftpRemotePath)

        existing?.let {
            editName.setText(it.name)
            editHost.setText(it.sftpHost)
            editPort.setText(it.sftpPort.toString())
            editUser.setText(it.sftpUser)
            editPassword.setText(it.sftpPassword)
            editRemotePath.setText(it.sftpRemotePath)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_sftp_title)
            .setView(view)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.btn_delete) { _, _ -> confirmDelete(existing) }
        }
        val dialog = builder.create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val host = editHost.text.toString().trim()
                val port = editPort.text.toString().trim().toIntOrNull() ?: 22
                val user = editUser.text.toString().trim()
                val password = editPassword.text.toString()
                val remotePath = editRemotePath.text.toString().trim().ifBlank { "/" }
                val name = editName.text.toString().ifBlank { "$user@$host" }

                if (host.isBlank() || user.isBlank()) {
                    Toast.makeText(this, R.string.hint_sftp_host, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val candidate = (existing ?: Destination(name = name, type = DestType.SFTP)).copy(
                    name = name,
                    type = DestType.SFTP,
                    sftpHost = host,
                    sftpPort = port,
                    sftpUser = user,
                    sftpPassword = password,
                    sftpRemotePath = remotePath
                )

                if (existing != null) {
                    // Редактирование настроек не должно зависеть от доступности сервера.
                    // Особенно важно для изменения только названия: раньше здесь запускался
                    // config/create, кнопка блокировалась, а новое имя не сохранялось.
                    store.updateDestination(candidate)
                    refreshList()
                    dialog.dismiss()

                    val connectionChanged = existing.sftpHost != candidate.sftpHost ||
                        existing.sftpPort != candidate.sftpPort ||
                        existing.sftpUser != candidate.sftpUser ||
                        existing.sftpPassword != candidate.sftpPassword
                    val pathChanged = existing.sftpRemotePath != candidate.sftpRemotePath
                    if (connectionChanged || pathChanged) {
                        Thread {
                            val remoteName = RcloneHelper.remoteName(candidate.id)
                            val (type, params, _) = FileCopyEngine.rcloneBackendInfo(candidate)!!
                            val ensured = if (connectionChanged) {
                                RcloneHelper.createOrReplaceRemote(this, remoteName, type, params, force = true)
                            } else {
                                RcloneHelper.ensureRemote(this, remoteName, type, params)
                            }
                            val result = if (ensured.success) {
                                RcloneHelper.testRemotePath(this, RcloneHelper.fsSpec(remoteName, candidate.sftpRemotePath ?: "/"))
                            } else ensured
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    if (result.success) "Подключение проверено" else "Проверка не удалась: ${result.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.start()
                    }
                    return@setOnClickListener
                }

                // Блокируем кнопку И закрытие диалога (свайп назад/тап мимо) на время проверки —
                // иначе повторные нажатия создают несколько одинаковых записей, а закрытие
                // диалога до завершения проверки выглядит так, будто ничего не произошло.
                saveButton.isEnabled = false
                dialog.setCancelable(false)
                dialog.setTitle(getString(R.string.dialog_add_sftp_title) + " — " + getString(R.string.sftp_testing))
                Thread {
                    val remoteName = RcloneHelper.remoteName(candidate.id)
                    val (type, params, _) = FileCopyEngine.rcloneBackendInfo(candidate)!!
                    val ensured = RcloneHelper.createOrReplaceRemote(this, remoteName, type, params, force = true)
                    val testResult = if (ensured.success) RcloneHelper.testRemotePath(this, "$remoteName:$remotePath") else ensured
                    runOnUiThread {
                        if (testResult.success) {
                            if (existing != null) store.updateDestination(candidate) else store.addDestination(candidate)
                            refreshList()
                            dialog.dismiss()
                        } else {
                            saveButton.isEnabled = true
                            dialog.setCancelable(true)
                            dialog.setTitle(R.string.dialog_add_sftp_title)
                            Toast.makeText(
                                this,
                                getString(R.string.sftp_test_failed, testResult.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    /** Диалог добавления/редактирования Mega-назначения (через rclone), с той же логикой проверки перед сохранением. */
    private fun showMegaDialog(existing: Destination?) {
        val view = layoutInflater.inflate(R.layout.dialog_add_mega, null)
        val editName = view.findViewById<EditText>(R.id.editMegaName)
        val editUser = view.findViewById<EditText>(R.id.editMegaUser)
        val editPassword = view.findViewById<EditText>(R.id.editMegaPassword)
        val editRemotePath = view.findViewById<EditText>(R.id.editMegaRemotePath)

        existing?.let {
            editName.setText(it.name)
            editUser.setText(it.megaUser)
            editPassword.setText(it.megaPassword)
            editRemotePath.setText(it.megaRemotePath)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_mega_title)
            .setView(view)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.btn_delete) { _, _ -> confirmDelete(existing) }
        }
        val dialog = builder.create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val user = editUser.text.toString().trim()
                val password = editPassword.text.toString()
                val remotePath = editRemotePath.text.toString().trim().ifBlank { "/" }
                val name = editName.text.toString().ifBlank { user }

                if (user.isBlank()) {
                    Toast.makeText(this, R.string.hint_mega_user, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val candidate = (existing ?: Destination(name = name, type = DestType.MEGA)).copy(
                    name = name,
                    type = DestType.MEGA,
                    megaUser = user,
                    megaPassword = password,
                    megaRemotePath = remotePath
                )

                if (existing != null) {
                    // Сохраняем локальные изменения независимо от состояния Mega.
                    store.updateDestination(candidate)
                    refreshList()
                    dialog.dismiss()

                    val connectionChanged = existing.megaUser != candidate.megaUser ||
                        existing.megaPassword != candidate.megaPassword
                    val pathChanged = existing.megaRemotePath != candidate.megaRemotePath
                    if (connectionChanged || pathChanged) {
                        Thread {
                            val remoteName = RcloneHelper.remoteName(candidate.id)
                            val (type, params, _) = FileCopyEngine.rcloneBackendInfo(candidate)!!
                            val ensured = if (connectionChanged) {
                                RcloneHelper.createOrReplaceRemote(this, remoteName, type, params, force = true)
                            } else {
                                RcloneHelper.ensureRemote(this, remoteName, type, params)
                            }
                            val result = if (ensured.success) {
                                RcloneHelper.testRemotePath(this, RcloneHelper.fsSpec(remoteName, candidate.megaRemotePath ?: ""))
                            } else ensured
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    if (result.success) "Подключение проверено" else "Проверка не удалась: ${result.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.start()
                    }
                    return@setOnClickListener
                }

                saveButton.isEnabled = false
                dialog.setCancelable(false)
                dialog.setTitle(getString(R.string.dialog_add_mega_title) + " — " + getString(R.string.mega_testing))
                Thread {
                    val remoteName = RcloneHelper.remoteName(candidate.id)
                    val (type, params, _) = FileCopyEngine.rcloneBackendInfo(candidate)!!
                    val ensured = RcloneHelper.createOrReplaceRemote(this, remoteName, type, params, force = true)
                    val testResult = if (ensured.success) RcloneHelper.testRemotePath(this, "$remoteName:$remotePath") else ensured
                    runOnUiThread {
                        if (testResult.success) {
                            if (existing != null) store.updateDestination(candidate) else store.addDestination(candidate)
                            refreshList()
                            dialog.dismiss()
                        } else {
                            saveButton.isEnabled = true
                            dialog.setCancelable(true)
                            dialog.setTitle(R.string.dialog_add_mega_title)
                            Toast.makeText(
                                this,
                                getString(R.string.mega_test_failed, testResult.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showDestinationActionsMenu(dest: Destination) {
        val view = layoutInflater.inflate(R.layout.dialog_destination_actions, null)
        val dialog = AlertDialog.Builder(this).setTitle(dest.name).setView(view).create()

        val canOpenLocally = dest.type == DestType.SAF || dest.type == DestType.MANUAL
        val openSystemView = view.findViewById<TextView>(R.id.actionOpenSystem)
        val openFixedFmView = view.findViewById<TextView>(R.id.actionOpenFixedFm)
        val testRemoteView = view.findViewById<TextView>(R.id.actionTestRemote)

        if (canOpenLocally) {
            openSystemView.setOnClickListener {
                dialog.dismiss()
                openInSystem(dest)
            }
            openFixedFmView.setOnClickListener {
                dialog.dismiss()
                openInFixedFileManager(dest)
            }
        } else {
            // SFTP/Mega — удалённое хранилище, локальным файловым менеджером его не открыть.
            openSystemView.visibility = View.GONE
            openFixedFmView.visibility = View.GONE
        }
        if (dest.type == DestType.SFTP || dest.type == DestType.MEGA) {
            testRemoteView.setOnClickListener {
                dialog.dismiss()
                testRemoteDestination(dest)
            }
        } else {
            testRemoteView.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.actionDelete).setOnClickListener {
            dialog.dismiss()
            confirmDelete(dest)
        }

        dialog.show()
    }

    private fun testRemoteDestination(destination: Destination) {
        Toast.makeText(this, R.string.remote_test_started, Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                val info = FileCopyEngine.rcloneBackendInfo(destination)
                if (info == null) {
                    RcloneHelper.RpcResult(false, null, "не настроены параметры подключения")
                } else {
                    val (type, params, basePath) = info
                    val remoteName = RcloneHelper.remoteName(destination.id)
                    val ensured = RcloneHelper.ensureRemote(this, remoteName, type, params)
                    var test = if (ensured.success) {
                        RcloneHelper.testRemotePath(this, RcloneHelper.fsSpec(remoteName, basePath))
                    } else ensured
                    if (!test.success && RcloneHelper.isMissingRemoteError(test.message)) {
                        val recreated = RcloneHelper.createOrReplaceRemote(this, remoteName, type, params, force = true)
                        test = if (recreated.success) {
                            RcloneHelper.testRemotePath(this, RcloneHelper.fsSpec(remoteName, basePath))
                        } else recreated
                    }
                    test
                }
            } catch (e: Exception) {
                RcloneHelper.RpcResult(false, null, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (result.success) {
                    Toast.makeText(this, R.string.remote_test_ok, Toast.LENGTH_LONG).show()
                } else {
                    val error = result.message
                    val lower = error.lowercase()
                    val text = when {
                        listOf("password", "authentication", "permission denied", "access denied", "login")
                            .any { lower.contains(it) } -> getString(R.string.remote_test_auth_failed, error)
                        listOf("connection", "connect", "dial", "timeout", "timed out", "refused", "unreachable", "no route")
                            .any { lower.contains(it) } -> getString(R.string.remote_test_server_failed, error)
                        else -> getString(R.string.remote_test_failed, error)
                    }
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openInSystem(dest: Destination) {
        val intents = FolderOpenHelper.buildAllOpenFolderIntents(this, dest)
        if (intents.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_open_folder_failed, dest.name), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val chooser = Intent.createChooser(intents.first(), dest.name)
            if (intents.size > 1) {
                val extraIntents = mutableListOf<Intent>()
                for (i in 1 until intents.size) {
                    val resolved = packageManager.queryIntentActivities(intents[i], android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    for (ri in resolved) {
                        extraIntents.add(Intent(intents[i]).apply {
                            setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        })
                    }
                }
                if (extraIntents.isNotEmpty()) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                }
            }
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_folder_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun openInFixedFileManager(dest: Destination) {
        val pkg = store.getFixedFileManagerPackage()
        if (pkg.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_fixed_fm_not_set, Toast.LENGTH_LONG).show()
            return
        }
        val intent = FolderOpenHelper.buildOpenFolderIntent(this, dest)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.error_open_folder_failed, dest.name), Toast.LENGTH_LONG).show()
            return
        }
        intent.setPackage(pkg)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_folder_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun showFileManagerPickerDialog() {
        val candidates = FolderOpenHelper.listFileManagerCandidates(this)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.fixed_fm_none_found, Toast.LENGTH_LONG).show()
            return
        }
        val labels = candidates.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_file_manager_title)
            .setItems(labels) { _, which ->
                val (pkg, label) = candidates[which]
                store.setFixedFileManager(pkg, label)
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showLogFolderDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_log_folder_path)
            setText(store.getLogFolderPath() ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_log_folder_title)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val path = input.text.toString().trim()
                store.setLogFolderPath(path.ifBlank { null })
            }
            .setNeutralButton(R.string.btn_disable) { _, _ -> store.setLogFolderPath(null) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDelete(dest: Destination) {
        AlertDialog.Builder(this)
            .setTitle(dest.name)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                store.removeDestination(dest.id)
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun requestUsageAccess() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, e2.message ?: "?", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}
