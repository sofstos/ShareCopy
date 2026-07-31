package com.sofstos.sharecopy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SettingsStore(this)
        recycler = findViewById(R.id.recyclerDestinations)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = DestinationAdapter(
            store.getDestinations(),
            onClick = { dest -> editDestination(dest) },
            onLongClick = { dest -> confirmDelete(dest); true }
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
        view.findViewById<TextView>(R.id.menuItemAllFilesAccess).setOnClickListener {
            dialog.dismiss()
            requestAllFilesAccess()
        }
        view.findViewById<TextView>(R.id.menuItemUsageAccess).setOnClickListener {
            dialog.dismiss()
            requestUsageAccess()
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
        }
    }

    /**
     * Диалог добавления/редактирования SFTP-назначения. Перед сохранением проверяет
     * соединение (в фоне) — если подключиться не удалось, диалог не закрывается и
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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

                Toast.makeText(this, R.string.sftp_testing, Toast.LENGTH_SHORT).show()
                Thread {
                    val result = SftpHelper.testConnection(host, port, user, password, remotePath)
                    runOnUiThread {
                        if (result.success) {
                            val base = existing ?: Destination(name = name, type = DestType.SFTP)
                            val toSave = base.copy(
                                name = name,
                                type = DestType.SFTP,
                                sftpHost = host,
                                sftpPort = port,
                                sftpUser = user,
                                sftpPassword = password,
                                sftpRemotePath = remotePath
                            )
                            if (existing != null) store.updateDestination(toSave) else store.addDestination(toSave)
                            refreshList()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(
                                this,
                                getString(R.string.sftp_test_failed, result.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
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
