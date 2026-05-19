package com.palana.phonebook.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class AvatarStorage(private val context: Context) {
    fun copyPickedAvatar(sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        return try {
            val ext = avatarExtension(sourcePath)
            val file = File(avatarDir(), "avatar_${System.currentTimeMillis()}.$ext")
            openAvatarInputStream(sourcePath)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: return null
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
}
