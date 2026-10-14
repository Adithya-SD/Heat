package app.heat.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The last picture seen from each camera, small, kept on the watch.
 *
 * It is what the screen shows the instant the app opens, before the link or the decoder exists: a dimmed still of
 * the room instead of a black circle, which cross-fades into live video when the first frame arrives. It is a
 * guess (the room may have changed) and is drawn as one: dim, with the connecting hint over it.
 */
object LastPicture {
    private fun file(context: Context, camera: String) =
        File(File(context.filesDir, "heat").apply { mkdirs() }, "last_$camera.jpg")

    fun save(context: Context, camera: String, bitmap: Bitmap) {
        runCatching {
            val tmp = File(file(context, camera).path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            tmp.renameTo(file(context, camera))
        }
    }

    fun load(context: Context, camera: String): Bitmap? = runCatching {
        val f = file(context, camera)
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }.getOrNull()
}
