package com.myra.assistant.ui.license

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityActivationBinding
import com.myra.assistant.license.LicenseConfig
import com.myra.assistant.license.LicenseManager
import com.myra.assistant.license.Plan
import com.myra.assistant.ui.contact.ContactActivity
import com.myra.assistant.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivationActivity : AppCompatActivity() {

    private lateinit var b: ActivityActivationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = LicenseManager.deviceId(this)
        b.deviceIdText.text = id
        b.contactText.text = LicenseConfig.CONTACT_TEXT

        b.copyIdBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MYRA Device ID", id))
            Toast.makeText(this, "Device ID copy ho gaya", Toast.LENGTH_SHORT).show()
        }
        b.shareIdBtn.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "MYRA Device ID: $id")
            startActivity(Intent.createChooser(send, "Device ID bhejo"))
        }
        b.contactBtn.setOnClickListener { startActivity(Intent(this, ContactActivity::class.java)) }
        b.activateBtn.setOnClickListener { activate() }
        showCurrent()
    }

    private fun showCurrent() {
        val s = LicenseManager.status(this)
        b.infoText.text = when {
            s.valid -> "Abhi active: ${planName(s.plan)} • ${expiryText(s.expiresAtSec)}"
            s.message.isNotEmpty() && s.message != "Activation key nahi daali gayi" -> s.message
            else -> ""
        }
    }

    private fun activate() {
        val st = LicenseManager.activate(this, b.keyInput.text.toString())
        if (st.valid) {
            Toast.makeText(this, "Activate ho gaya ✅ ${planName(st.plan)}", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        } else {
            b.errorText.text = "❌ ${st.message}"
        }
    }

    private fun planName(p: Plan?) = when (p) {
        Plan.PRO -> "PRO (sab features)"
        Plan.TRIAL -> "TRIAL (sab features)"
        Plan.FREE -> "FREE (sirf voice baat-cheet)"
        null -> ""
    }

    private fun expiryText(sec: Long): String =
        if (sec == 0L) "lifetime"
        else "khatam: " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(sec * 1000))
}
