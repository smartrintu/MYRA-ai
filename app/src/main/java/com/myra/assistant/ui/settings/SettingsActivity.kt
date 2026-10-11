package com.myra.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.myra.assistant.R
import com.myra.assistant.ai.GeminiModels
import com.myra.assistant.data.PrimeContact
import com.myra.assistant.data.SecurePrefs
import com.myra.assistant.databinding.ActivitySettingsBinding
import com.myra.assistant.service.AccessibilityHelperService

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val contacts = mutableListOf<PrimeContact>()
    private lateinit var adapter: PrimeContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.apiKeyInput.setText(SecurePrefs.apiKey)
        b.userNameInput.setText(SecurePrefs.userName)

        b.modelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, GeminiModels.models.map { it.label }
        )
        b.modelSpinner.setSelection(GeminiModels.models.indexOfFirst { it.id == SecurePrefs.model }.coerceAtLeast(0))

        b.voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, GeminiModels.voices.map { it.label }
        )
        b.voiceSpinner.setSelection(GeminiModels.voices.indexOfFirst { it.id == SecurePrefs.voice }.coerceAtLeast(0))

        when (SecurePrefs.personality) {
            "PRO" -> b.radioPro.isChecked = true
            "ASSISTANT" -> b.radioAssistant.isChecked = true
            else -> b.radioGf.isChecked = true
        }
        when (SecurePrefs.language) {
            "BANGLA" -> b.radioLangBangla.isChecked = true
            "ENGLISH" -> b.radioLangEnglish.isChecked = true
            else -> b.radioLangHinglish.isChecked = true
        }
        b.muteWhileSpeakingSwitch.isChecked = SecurePrefs.muteWhileSpeaking

        contacts.addAll(SecurePrefs.getPrimeContacts())
        adapter = PrimeContactAdapter(contacts) { pos ->
            contacts.removeAt(pos)
            adapter.notifyDataSetChanged()
        }
        b.primeRecycler.layoutManager = LinearLayoutManager(this)
        b.primeRecycler.adapter = adapter

        b.addPrimeBtn.setOnClickListener { showAddDialog() }
        b.accessStatus.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.overlayStatus.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        b.batteryStatus.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        b.saveBtn.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        val acc = AccessibilityHelperService.isEnabled(this)
        b.accessStatus.text = if (acc) "✅ Accessibility ON" else "❌ Accessibility OFF — tap to enable"
        b.accessStatus.setTextColor(if (acc) Color.parseColor("#00E676") else Color.parseColor("#FF1744"))

        val ov = Settings.canDrawOverlays(this)
        b.overlayStatus.text = if (ov) "✅ Floating orb permission ON" else "❌ Floating orb permission OFF — tap to enable"
        b.overlayStatus.setTextColor(if (ov) Color.parseColor("#00E676") else Color.parseColor("#FF1744"))
        refreshBatteryStatus()
    }

    private fun refreshBatteryStatus() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ok = pm.isIgnoringBatteryOptimizations(packageName)
        b.batteryStatus.text = if (ok) "✅ Battery: unrestricted (background OK)"
        else "❌ Battery restricted — tap karke 'Allow' karo, warna background mein MYRA band ho sakti hai"
        b.batteryStatus.setTextColor(if (ok) Color.parseColor("#00E676") else Color.parseColor("#FF1744"))
    }

    private fun showAddDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        val nameIn = v.findViewById<EditText>(R.id.dialogNameInput)
        val numIn = v.findViewById<EditText>(R.id.dialogNumberInput)
        AlertDialog.Builder(this)
            .setTitle("Add Prime Contact")
            .setView(v)
            .setPositiveButton("Add") { _, _ ->
                val name = nameIn.text.toString().trim()
                val num = numIn.text.toString().replace(Regex("[\\s-]"), "")
                if (name.isEmpty() || !Regex("^\\+?[0-9]{7,15}$").matches(num)) {
                    Toast.makeText(this, "Valid name aur number daalo (e.g. +919876543210)", Toast.LENGTH_LONG).show()
                } else {
                    contacts.add(PrimeContact(name, num))
                    adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        SecurePrefs.apiKey = b.apiKeyInput.text.toString().trim()
        SecurePrefs.userName = b.userNameInput.text.toString().trim()
        SecurePrefs.model = GeminiModels.models[b.modelSpinner.selectedItemPosition].id
        SecurePrefs.voice = GeminiModels.voices[b.voiceSpinner.selectedItemPosition].id
        SecurePrefs.personality = when (b.personalityGroup.checkedRadioButtonId) {
            R.id.radioPro -> "PRO"
            R.id.radioAssistant -> "ASSISTANT"
            else -> "GF"
        }
        SecurePrefs.language = when (b.languageGroup.checkedRadioButtonId) {
            R.id.radioLangBangla -> "BANGLA"
            R.id.radioLangEnglish -> "ENGLISH"
            else -> "HINGLISH"
        }
        SecurePrefs.muteWhileSpeaking = b.muteWhileSpeakingSwitch.isChecked
        SecurePrefs.setPrimeContacts(contacts)
        Toast.makeText(this, "Settings apply ho gayi ✅", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
