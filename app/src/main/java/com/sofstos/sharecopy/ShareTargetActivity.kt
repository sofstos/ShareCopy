package com.sofstos.sharecopy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ShareTargetActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var sources: ArrayList<Uri>
    private lateinit var recycler: RecyclerView
    private lateinit var checkMove: CheckBox
    private lateinit var textStatus: TextView
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_target)

        store = SettingsStore(this)
        sources = extractSources(intent)

        if (sources.isEmpty()) {
            finish()
            return
        }

        val destinations = store.getDestinations()
        if (destinations.isEmpty()) {
            Toast.makeText(this, R.string.error_no_destinations, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        recycler = findViewById(R.id.recyclerDestinations)
        recycler.layoutManager = LinearLayoutManager(this)
        checkMove = findViewById(R.id.checkMove)
        textStatus = findViewById(R.id.textStatus)

        checkMove.isChecked = store.getLastMoveFlag()
        checkMove.setOnCheckedChangeListener { _, checked -> store.setLastMoveFlag(checked) }

        val adapter = DestinationAdapter(destinations, onClick = { dest ->
            if (!isRunning) startCopy(dest, checkMove.isChecked)
        })
        recycler.adapter = adapter

        // Фокус пультом ТВ сразу на первый пункт списка
        recycler.post { recycler.getChildAt(0)?.requestFocus() }
    }

    /** Позволяет закрыть диалог кнопкой "Назад" на пульте/телефоне */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !isRunning) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun extractSources(intent: Intent?): ArrayList<Uri> {
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

    private fun startCopy(dest: Destination, move: Boolean) {
        isRunning = true
        textStatus.visibility = android.view.View.VISIBLE
        textStatus.text = getString(R.string.copy_in_progress)

        Thread {
            val result = FileCopyEngine.run(applicationContext, sources, dest, move)
            runOnUiThread {
                val msg = getString(R.string.copy_done, result.succeeded, result.total)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (result.notRemovedAfterMove.isNotEmpty()) {
                    Toast.makeText(this, R.string.copy_move_warning, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }
}
