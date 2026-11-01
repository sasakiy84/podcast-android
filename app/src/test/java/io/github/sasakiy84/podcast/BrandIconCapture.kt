package io.github.sasakiy84.podcast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import java.io.File

/** Visual catalog of real Android vector resources; no image equality assertions. */
internal fun captureBrandIcons(context: Context, target: File): Unit {
    val bitmap = Bitmap.createBitmap(1200, 620, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(247, 245, 242))
    val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(48, 39, 34)
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    canvas.drawText("GalenaCast  /  34-29 C05", 36f, 42f, text)
    fun icon(x: Int, y: Int, size: Int, mask: Int, themed: Boolean = false) {
        val drawable = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        if (mask == 0) {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        } else {
            val path =
                Path().apply {
                    if (mask == 1) addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                    else
                        addRoundRect(
                            0f,
                            0f,
                            size.toFloat(),
                            size.toFloat(),
                            size * .22f,
                            size * .22f,
                            Path.Direction.CW,
                        )
                }
            canvas.clipPath(path)
            val background =
                if (themed) Color.rgb(56, 39, 29) else context.getColor(R.color.galena_background)
            canvas.drawColor(background)
            val foreground =
                if (themed)
                    requireNotNull(drawable.monochrome).mutate().apply {
                        setTint(Color.rgb(243, 206, 176))
                    }
                else drawable.foreground
            // Adaptive layers include 25% overscan on each side of the visible mask.
            foreground.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            foreground.draw(canvas)
        }
        canvas.restore()
    }
    listOf("Android mask", "Circle", "Rounded square", "Themed").forEachIndexed { i, label ->
        val x = 36 + i * 290
        icon(x, 80, 220, if (i == 3) 1 else i, i == 3)
        canvas.drawText(label, x.toFloat(), 340f, text)
        icon(x, 375, 48, if (i == 3) 1 else i, i == 3)
        icon(x + 75, 375, 72, if (i == 3) 1 else i, i == 3)
        canvas.drawText("48px / 72px", x.toFloat(), 480f, text)
    }
    text.textSize = 20f
    canvas.drawText("Notification / 24px", 36f, 545f, text)
    val notification = requireNotNull(context.getDrawable(R.drawable.ic_podcast)).mutate()
    val surface = Paint().apply { color = Color.rgb(48, 39, 34) }
    canvas.drawRoundRect(255f, 512f, 335f, 568f, 8f, 8f, surface)
    notification.setBounds(283, 528, 307, 552)
    notification.draw(canvas)
    target.parentFile!!.mkdirs()
    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    bitmap.recycle()
}
