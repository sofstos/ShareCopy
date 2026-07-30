package com.sofstos.sharecopy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Показывает список полных путей [items]; в строке отображается только последний сегмент пути.
 */
class FolderAdapter(
    private val items: MutableList<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_row, parent, false) as TextView
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val path = items[position]
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        holder.text.text = "📁 $name"
        holder.text.setOnClickListener { onClick(path) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
