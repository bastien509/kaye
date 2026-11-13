package com.petiht.kaye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Sample notebook pages drawn in a handwriting font (same as the web app). Names and numbers are invented. */
object Samples {
    data class Page(val id: String, val label: String, val title: String, val lines: List<String>)

    val PAGES = listOf(
        Page("en", "Sales (EN)", "Tuesday - Sales", listOf("Rice  3 cups x 250 = 750", "Beans  2 x 300 = 600", "Oil  1 bottle = 450", "Sugar  4 x 75 = 300", "", "Total   2100")),
        Page("ht", "Lavant + kredi", "Madi 11 - Lavant", listOf("Diri  4 mamit x 250 = 1000", "Pwa  2 x 300 = 600", "Savon  5 x 60 = 300", "Total  1900", "", "Kredi: Madan Pòl  500", "Jean peye  200")),
        Page("err", "Page with a mistake", "Wednesday - Sales", listOf("Flour  2 sacks x 1500 = 3000", "Soap  6 x 60 = 360", "Charcoal  1 = 400", "", "Total   3860")),
    )

    fun draw(id: String): Bitmap {
        val page = PAGES.firstOrNull { it.id == id } ?: PAGES[0]
        val w = 900
        val h = 1150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFFFBFAF5.toInt(), 0xFFF1EFE6.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val rule = Paint().apply { color = 0x4700209F; strokeWidth = 2f }
        var y = 170f
        while (y < h - 30) { c.drawLine(0f, y, w.toFloat(), y, rule); y += 70f }
        c.drawLine(110f, 0f, 110f, h.toFloat(), Paint().apply { color = 0x8CD21034.toInt(); strokeWidth = 3f })
        val hand = Typeface.create("cursive", Typeface.NORMAL)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16245E.toInt(); typeface = hand; textSize = 52f }
        var seed = page.id[0].code.toLong()
        fun jit(): Float { seed = (seed * 9301L + 49297L) % 233280L; return seed / 233280f - 0.5f }
        fun text(s: String, x: Float, yy: Float, rot: Float) { c.save(); c.rotate(rot, x, yy); c.drawText(s, x, yy, ink); c.restore() }
        ink.textSize = 58f; ink.isFakeBoldText = true
        text(page.title, 140f, 140f, jit() * 1.2f)
        ink.textSize = 48f; ink.isFakeBoldText = false
        page.lines.forEachIndexed { i, s ->
            if (s.isEmpty()) return@forEachIndexed
            val yy = 225f + i * 70f + jit() * 6f
            if (s.startsWith("Total")) {
                ink.isFakeBoldText = true
                text(s, 150f + jit() * 10f, yy, jit())
                c.drawLine(140f, yy - 52f, 560f, yy - 56f, Paint().apply { color = 0xFF16245E.toInt(); strokeWidth = 2.5f })
                ink.isFakeBoldText = false
            } else text(s, 140f + jit() * 12f, yy, jit() * 1.4f)
        }
        return bmp
    }

    /** Loads a photo at most 1280 px on the long side, upright (EXIF), for the AI. */
    fun load(context: Context, uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val scale = minOf(1f, 1280f / max(bmp.width, bmp.height))
        val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }
        return if (rotation == 0 && scale == 1f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
