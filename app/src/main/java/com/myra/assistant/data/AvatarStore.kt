package com.myra.assistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File

/** Stores the optional real-photo avatar the user picked from the gallery. */
object AvatarStore {
    fun file(ctx: Context) = File(ctx.filesDir, "avatar.jpg")

    /** Built-in default photo: put the image at res/drawable-nodpi/myra_girl.jpg (or .png). */
    private fun defaultResId(ctx: Context) = ctx.resources.getIdentifier("myra_girl", "drawable", ctx.packageName)

    /** Changes whenever the shown photo changes (custom photo, default photo or none). */
    fun stamp(ctx: Context): Long {
        val f = file(ctx)
        return when {
            f.exists() -> f.lastModified()
            defaultResId(ctx) != 0 -> 1L
            else -> 0L
        }
    }

    /** The photo the user picked, else the built-in default girl photo, else null (drawn portrait). */
    fun load(ctx: Context): Bitmap? {
        val f = file(ctx)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        val id = defaultResId(ctx)
        return if (id != 0) BitmapFactory.decodeResource(ctx.resources, id) else null
    }

    fun remove(ctx: Context) {
        file(ctx).delete()
    }

    fun saveFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = 1200f / maxOf(w, h)
                    if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            }
            if (bmp == null) return false
            file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
