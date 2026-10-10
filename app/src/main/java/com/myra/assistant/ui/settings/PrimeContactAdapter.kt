package com.myra.assistant.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.data.PrimeContact

class PrimeContactAdapter(
    private val items: MutableList<PrimeContact>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.primeItemName)
        val number: TextView = v.findViewById(R.id.primeItemNumber)
        val delete: ImageButton = v.findViewById(R.id.primeItemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = (if (position == 0) "⭐ " else "") + c.name
        holder.number.text = c.number
        holder.delete.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onDelete(p)
        }
    }

    override fun getItemCount() = items.size
}
