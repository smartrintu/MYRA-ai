package com.myra.assistant.ui.main

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R

data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.nanoTime())

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    private val items = mutableListOf<ChatMessage>()
    private var themeP = 0xFFFF4D8D.toInt()
    private var themeS = 0xFFB388FF.toInt()

    fun setTheme(p: Int, s: Int) {
        themeP = p
        themeS = s
    }

    fun refresh() = notifyDataSetChanged()

    private fun bubble(isUser: Boolean, density: Float): GradientDrawable {
        val big = 18f * density
        val small = 4f * density
        return GradientDrawable().apply {
            if (isUser) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(themeP, themeS)
                cornerRadii = floatArrayOf(big, big, big, big, small, small, big, big)
            } else {
                setColor(0x40FFFFFF)
                setStroke((1f * density).toInt().coerceAtLeast(1), (themeS and 0x00FFFFFF) or 0x66000000)
                cornerRadii = floatArrayOf(small, small, big, big, big, big, big, big)
            }
        }
    }

    companion object {
        private const val T_USER = 0
        private const val T_MYRA = 1
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.timestamp == b.timestamp
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    fun lastMyraText(): String? = items.lastOrNull { !it.isUser }?.text

    /** Returns the new item count, or -1 if the message was skipped as a duplicate. */
    fun add(msg: ChatMessage): Int {
        val last = items.lastOrNull()
        if (!msg.isUser && last != null && !last.isUser && last.text == msg.text) return -1
        items.add(msg)
        submitList(ArrayList(items))
        return items.size
    }

    override fun getItemViewType(position: Int) = if (getItem(position).isUser) T_USER else T_MYRA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == T_USER) R.layout.item_chat_user else R.layout.item_chat_myra
        return object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false)
        ) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        val tv = holder.itemView.findViewById<TextView>(R.id.chatText)
        tv.text = msg.text
        tv.background = bubble(msg.isUser, tv.resources.displayMetrics.density)
    }
}
