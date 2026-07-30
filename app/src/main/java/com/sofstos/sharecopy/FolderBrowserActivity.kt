package com.sofstos.sharecopy

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Собственный лёгкий обозреватель файловой системы внутри ShareCopy.
 *
 * Нужен потому, что системный intent "Поделиться" (ACTION_SEND/ACTION_SEND_MULTIPLE) в принципе
 * не может передать папку — EXTRA_STREAM ожидает поток байт одного файла, а не дерево каталогов.
 * Поэтому для копирования/перемещения ЦЕЛЫХ папок используется этот отдельный экран, открываемый
 * прямо из главного меню приложения.
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FolderAdapter
    private lateinit var textCurrentPath: TextView
    private lateinit var editJumpPath: EditText
    private lateinit var checkBrowseRoot: CheckBox
    private lateinit var textEmptyFolders: TextView

    private var currentPath = DEFAULT_START_PATH
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_browser)

        store = SettingsStore(this)
        recycler = findViewById(R.id.recyclerFolders)
        recycler.layoutManager = LinearLayoutManager(this)
        textCurrentPath = findViewById(R.id.textCurrentPath)
        editJumpPath = findViewById(R.id.editJumpPath)
        checkBrowseRoot = findViewById(R.id.checkBrowseRoot)
        textEmptyFolders = findViewById(R.id.textEmptyFolders)

        adapter = FolderAdapter(mutableListOf()) { path -> navigateTo(path) }
        recycler.adapter = adapter

        editJumpPath.setText(currentPath)

        findViewById<Button>(R.id.btnGo).setOnClickListener {
            val target = editJumpPath.text.toString().trim()
            if (target.isNotBlank()) navigateTo(target)
        }
        findViewById<Button>(R.id.btnUp).setOnClickListener {
            val parent = currentPath.trimEnd('/').substringBeforeLast('/', "/")
            navigateTo(parent.ifBlank { "/" })
        }
        findViewById<Button>(R.id.btnSelectFolder).setOnClickListener {
            confirmAndCopyFolder(currentPath)
        }
        checkBrowseRoot.setOnCheckedChangeListener { _, _ -> refreshList() }

        refreshList()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !isBusy) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun navigateTo(path: String) {
        currentPath = path.trimEnd('/').ifBlank { "/" }
        editJumpPath.setText(currentPath)
        refreshList()
    }

    private fun refreshList() {
        if (isBusy) return
        textCurrentPath.text = currentPath
        val useRoot = checkBrowseRoot.isChecked
        Thread {
            val list = RootHelper.listDirectories(currentPath, useRoot)
            runOnUiThread {
                if (list == null) {
                    Toast.makeText(this, R.string.folder_empty_or_no_access, Toast.LENGTH_SHORT).show()
                    adapter.updateItems(emptyList())
                    textEmptyFolders.visibility = View.VISIBLE
                } else {
                    adapter.updateItems(list)
                    textEmptyFolders.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private fun confirmAndCopyFolder(sourcePath: String) {
        val destinations = store.getDestinations()
        if (destinations.isEmpty()) {
            Toast.makeText(this, R.string.error_no_destinations, Toast.LENGTH_LONG).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.activity_share_target, null)
        val destRecycler = view.findViewById<RecyclerView>(R.id.recyclerDestinations)
        val checkMove = view.findViewById<CheckBox>(R.id.checkMove)
        view.findViewById<CheckBox>(R.id.checkSubfolder).visibility = View.GONE
        destRecycler.layoutManager = LinearLayoutManager(this)
        checkMove.isChecked = store.getLastMoveFlag()

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        val destAdapter = DestinationAdapter(destinations, onClick = { dest ->
            store.setLastMoveFlag(checkMove.isChecked)
            dialog.dismiss()
            runFolderCopy(sourcePath, dest, checkMove.isChecked)
        })
        destRecycler.adapter = destAdapter

        dialog.show()
    }

    private fun runFolderCopy(sourcePath: String, dest: Destination, move: Boolean) {
        isBusy = true
        val sourceUseRoot = checkBrowseRoot.isChecked
        Toast.makeText(this, R.string.folder_copy_in_progress, Toast.LENGTH_SHORT).show()

        Thread {
            val result = FileCopyEngine.runFolder(applicationContext, sourcePath, sourceUseRoot, dest, move)
            runOnUiThread {
                isBusy = false
                if (result.success) {
                    Toast.makeText(this, R.string.folder_copy_done_ok, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.folder_copy_done_fail, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    companion object {
        private const val DEFAULT_START_PATH = "/storage/emulated/0"
    }
}
