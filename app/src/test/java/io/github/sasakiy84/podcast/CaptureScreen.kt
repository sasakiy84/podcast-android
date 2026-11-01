package io.github.sasakiy84.podcast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import java.io.File

internal fun captureScreen(activity: ComponentActivity, target: File): Unit {
    val decor = activity.window.decorView
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    for (view in WindowInspector.getGlobalWindowViews()) {
        if (
            !view.isShown ||
                view.windowVisibility != View.VISIBLE ||
                view.width == 0 ||
                view.height == 0
        )
            continue
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        canvas.save()
        canvas.translate(location[0].toFloat(), location[1].toFloat())
        view.draw(canvas)
        canvas.restore()
    }
    target.parentFile!!.mkdirs()
    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
