package com.r1.launcher.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Flat, on-disk photo gallery for the Camera app.
 *
 * Files live in `filesDir/photos/` and are named `<epochMillis>-<kind>.<ext>`,
 * so a plain reverse filename sort is also a reverse chronological sort — no
 * index file to keep in sync, and a half-written file can never corrupt a
 * database. `kind` is `shot` for camera captures and `ai` for generated
 * images, which is what drives the little AI badge in the gallery grid.
 *
 * Deliberately not MediaStore: CarrotOS ships with
 * `com.android.providers.media.module` disabled, so MediaStore inserts fail
 * silently and nothing would ever appear.
 */
object PhotoStore {

    private const val TAG = "PhotoStore"
    private const val DIR = "photos"

    /** Newest first. */
    fun list(context: Context): List<Photo> {
        val dir = dir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0 && (it.name.endsWith(".jpg") || it.name.endsWith(".png")) }
            .sortedByDescending { it.name }
            .map { Photo(path = it.absolutePath, isAi = it.name.contains("-ai."), sizeBytes = it.length()) }
    }

    fun save(context: Context, bytes: ByteArray, kind: Kind): Photo? {
        return try {
            val ext = if (kind == Kind.AI) "png" else "jpg"
            // Monotonic-ish name. Two saves inside the same millisecond would
            // collide, so bump until the name is free rather than overwrite.
            var stamp = System.currentTimeMillis()
            var file: File
            do {
                file = File(dir(context), "$stamp-${kind.tag}.$ext")
                stamp++
            } while (file.exists())
            // Write to a temp sibling then rename, so `list()` can never see a
            // partially-written file (it runs on the UI thread on every open).
            val tmp = File(file.parentFile, "." + file.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.e(TAG, "save: rename failed for ${file.name}")
                return null
            }
            Log.i(TAG, "save: ${file.name} (${bytes.size / 1024} KB)")
            Photo(path = file.absolutePath, isAi = kind == Kind.AI, sizeBytes = file.length())
        } catch (t: Throwable) {
            Log.e(TAG, "save failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    fun delete(photo: Photo): Boolean = runCatching { File(photo.path).delete() }.getOrDefault(false)

    fun readBytes(photo: Photo): ByteArray? =
        runCatching { File(photo.path).readBytes() }.getOrNull()

    /**
     * Decode scaled to roughly [targetPx] on the long edge. The gallery grid
     * shows six-plus thumbnails at once and a generated image is a 1024² PNG
     * (~2 MB, ~4 MB decoded) — decoding those at full size scrolls straight
     * into an OOM on this device.
     */
    fun decode(photo: Photo, targetPx: Int): Bitmap? {
        val path = photo.path
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge / sample > targetPx * 2) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed for $path: ${t.message}")
            null
        }
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    enum class Kind(val tag: String) { SHOT("shot"), AI("ai") }

    data class Photo(
        val path: String,
        val isAi: Boolean,
        val sizeBytes: Long,
    ) {
        val name: String get() = path.substringAfterLast('/')
    }
}
