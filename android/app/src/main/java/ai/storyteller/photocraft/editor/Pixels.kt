package ai.storyteller.photocraft.editor

import android.graphics.Bitmap
import ai.storyteller.photocraft.engine.RenderedImage
import java.nio.ByteBuffer

/**
 * The engine renders straight-alpha RGBA8; an `ARGB_8888` bitmap stores premultiplied RGBA in the
 * same byte order, so only pixels with partial transparency need converting.
 */
fun RenderedImage.toBitmap(): Bitmap {
    val w = width.toInt()
    val h = height.toInt()
    val px = pixels
    var i = 0
    while (i + 3 < px.size) {
        val a = px[i + 3].toInt() and 0xFF
        if (a != 0xFF) {
            px[i] = ((px[i].toInt() and 0xFF) * a / 255).toByte()
            px[i + 1] = ((px[i + 1].toInt() and 0xFF) * a / 255).toByte()
            px[i + 2] = ((px[i + 2].toInt() and 0xFF) * a / 255).toByte()
        }
        i += 4
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px))
    return bitmap
}
