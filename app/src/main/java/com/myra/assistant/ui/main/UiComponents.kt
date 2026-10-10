package com.myra.assistant.ui.main

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
        holder.itemView.findViewById<TextView>(R.id.chatText).text = getItem(position).text
    }
}
