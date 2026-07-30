package com.sofstos.sharecopy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var adapter: DestinationAdapter
    private lateinit var recycler: RecyclerView

    private val safPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            askNameAndSave(uri)
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
            onClick = { /* на главном экране клик не запускает копирование */ },
            onLongClick = { dest -> confirmDelete(dest); true }
        )
        recycler.adapter = adapter

        findViewById<android.widget.Button>(R.id.btnAddSaf).setOnClickListener {
            safPickerLauncher.launch(null)
        }
        findViewById<android.widget.Button>(R.id.btnAddManual).setOnClickListener {
            showManualPathDialog()
        }
        findViewById<android.widget.Button>(R.id.btnAllFilesAccess).setOnClickListener {
            requestAllFilesAccess()
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
