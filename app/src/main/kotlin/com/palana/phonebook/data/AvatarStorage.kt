package com.palana.phonebook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class AvatarStorage(private val context: Context) {
    fun copyPickedAvatar(sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        return try {
            val bitmap = decodeAvatarBitmap(sourcePath) ?: return null
            val file = File(avatarDir(), "avatar_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, output)
            }
            bitmap.recycle()
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun openAvatarInputStream(uriText: String): InputStream? {
        return try {
            val uri = Uri.parse(uriText)
            when (uri.scheme) {
                "file" -> File(uri.path ?: return null).inputStream()
                null -> File(uriText).inputStream()
                else -> context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun avatarExtension(path: String): String {
        val ext = Uri.parse(path).lastPathSegment.orEmpty()
            .substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        return when (ext) {
            "png", "webp", "jpg", "jpeg" -> if (ext == "jpeg") "jpg" else ext
            else -> "jpg"
        }
    }

    fun avatarDir(): File = File(context.filesDir, "avatars").apply { mkdirs() }

    private fun decodeAvatarBitmap(sourcePath: String): Bitmap? {
        val uri = Uri.parse(sourcePath)
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && uri.scheme != "file" && uri.scheme != null) {
            decodeWithImageDecoder(uri)
        } else {
            decodeWithBitmapFactory(sourcePath)
        } ?: return null
        return centerCrop(decoded, AVATAR_SIZE_PX).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun decodeWithImageDecoder(uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxSide = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val scale = MAX_DECODE_SIZE_PX.toFloat() / maxSide.toFloat()
                if (scale < 1f) {
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeWithBitmapFactory(sourcePath: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openAvatarInputStream(sourcePath)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_SIZE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openAvatarInputStream(sourcePath)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val cropSize = minOf(source.width, source.height)
        val x = ((source.width - cropSize) / 2).coerceAtLeast(0)
        val y = ((source.height - cropSize) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(source, x, y, cropSize, cropSize)
        val scaled = if (cropSize == size) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxSize && halfHeight / sample >= maxSize) sample *= 2
        return sample.coerceAtLeast(1)
    }

    companion object {
        private const val AVATAR_SIZE_PX = 768
        private const val MAX_DECODE_SIZE_PX = 1536
        private const val AVATAR_JPEG_QUALITY = 88
    }
}
