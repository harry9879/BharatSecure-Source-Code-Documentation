package com.bharatsecure.app

import android.content.Context
import java.io.File

data class Recording(val audio: File, val transcript: File) {
    val name: String get() = audio.nameWithoutExtension
}

/**
 * Recordings live in the app's external files folder
 * (Android/data/com.bharatsecure.app/files/recordings) so they can be copied off over USB.
 */
object Recordings {
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

    fun list(context: Context): List<Recording> =
        dir(context).listFiles { f -> f.extension == "wav" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { Recording(it, File(it.parentFile, it.nameWithoutExtension + ".txt")) }

    fun delete(recording: Recording) {
        recording.audio.delete()
        recording.transcript.delete()
    }
}
