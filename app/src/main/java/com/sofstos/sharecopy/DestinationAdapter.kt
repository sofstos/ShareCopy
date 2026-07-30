package com.sofstos.sharecopy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DestinationAdapter(
    private val items: MutableList<Destination>,
    private val onClick: (Destination) -> Unit,
    private val onLongClick: ((Destination) -> Boolean)? = null
) : RecyclerView.Adapter<DestinationAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textName)
        val subtitle: TextView = view.findViewById(R.id.textSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_destination, parent, false)
        // Обязательно для навигации пультом Android TV
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.subtitle.text = when (item.type) {
            DestType.SAF -> "SAF"
            DestType.MANUAL -> if (item.useRoot) "Путь вручную (root): ${item.path}" else "Путь вручную: ${item.path}"
        }
        holder.itemView.setOnClickListener { onClick(item) }
        onLongClick?.let { cb ->
            holder.itemView.setOnLongClickListener { cb(item) }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<Destination>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
