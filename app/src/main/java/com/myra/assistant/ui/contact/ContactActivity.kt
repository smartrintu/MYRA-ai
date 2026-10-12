package com.myra.assistant.ui.contact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityContactBinding

object ContactInfo {
    const val NAME = "Smart Rintu"
    const val PHONE = "8346939348"
    const val EMAIL = "info@smartrintu.com"
    const val TELEGRAM = "https://t.me/smartrintu"
    const val GITHUB = "https://github.com/smartrintu"
}

class ContactActivity : AppCompatActivity() {

    private lateinit var b: ActivityContactBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityContactBinding.inflate(layoutInflater)
        setContentView(b.root)

        addRow("📱 Phone / WhatsApp", ContactInfo.PHONE, "CALL KARO") {
            startSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ContactInfo.PHONE)))
        }
        addRow("✉️ Email", ContactInfo.EMAIL, "EMAIL BHEJO") {
            startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + ContactInfo.EMAIL)))
        }
        addRow("💬 Telegram", "t.me/smartrintu", "TELEGRAM KHOLO") {
            startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(ContactInfo.TELEGRAM)))
        }
        addRow("💻 GitHub", ContactInfo.GITHUB, "GITHUB KHOLO") {
            startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(ContactInfo.GITHUB)))
        }
        b.backBtn.setOnClickListener { finish() }
    }

    private fun addRow(label: String, value: String, actionText: String, action: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_contact_row, b.container, false)
        row.findViewById<TextView>(R.id.rowLabel).text = label
        row.findViewById<TextView>(R.id.rowValue).text = value
        row.findViewById<Button>(R.id.rowAction).apply {
            text = actionText
            setOnClickListener { action() }
        }
        row.findViewById<Button>(R.id.rowCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, if (value.startsWith("t.me")) ContactInfo.TELEGRAM else value))
            Toast.makeText(this, "Copy ho gaya", Toast.LENGTH_SHORT).show()
        }
        b.container.addView(row)
    }

    private fun startSafe(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Is phone par ye app nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }
}
