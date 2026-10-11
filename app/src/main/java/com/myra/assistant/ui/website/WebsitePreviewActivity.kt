package com.myra.assistant.ui.website

import android.content.Intent
import android.os.Bundle
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.myra.assistant.databinding.ActivityWebsitePreviewBinding
import com.myra.assistant.viewmodel.WebsiteBuilder
import java.io.File

/** Shows the website MYRA built, with Share / Download buttons. */
class WebsitePreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebsitePreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebsitePreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra("path")
        val file = if (path != null) File(path) else null
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Website file nahi mili", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val slug = file.nameWithoutExtension
        val html = file.readText()
        b.titleText.text = "🌐 $slug"

        b.webView.settings.javaScriptEnabled = true
        b.webView.settings.domStorageEnabled = true
        b.webView.webViewClient = WebViewClient()
        b.webView.loadDataWithBaseURL("https://myra.preview/", html, "text/html", "utf-8", null)

        b.shareBtn.setOnClickListener {
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/html")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Website share karo"))
            } catch (e: Exception) {
                Toast.makeText(this, "Share nahi ho paya: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        b.downloadBtn.setOnClickListener {
            val p = WebsiteBuilder.saveToDownloads(this, slug, html)
            Toast.makeText(
                this,
                if (p != null) "Save ho gaya: $p" else "Is phone par seedha save nahi hua — Share dabao",
                Toast.LENGTH_LONG
            ).show()
        }
        b.closeBtn.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        if (::b.isInitialized) b.webView.destroy()
        super.onDestroy()
    }
}
