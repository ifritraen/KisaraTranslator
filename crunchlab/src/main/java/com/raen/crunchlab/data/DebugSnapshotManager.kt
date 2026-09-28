package com.raen.crunchlab.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

class DebugSnapshotManager(private val context: Context) {

    private val rootDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TranslationDebug/cache"
        ).apply { mkdirs() }

    fun listSnapshots(pageLabel: String): List<File> {
        val safePage = pageLabel.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val pageDir = File(rootDir, safePage).apply { mkdirs() }
        return pageDir.listFiles { f -> f.extension.lowercase() == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun saveSnapshot(pageLabel: String, snapshot: DebugModuleSnapshot): File {
        val safePage = pageLabel.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val pageDir = File(rootDir, safePage).apply { mkdirs() }
        val safeSlot = snapshot.slotName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val targetFile = File(pageDir, "$safeSlot.json")
        targetFile.writeText(snapshot.toJson())
        Log.i("DebugSnapshot", "Saved snapshot to: ${targetFile.absolutePath}")
        return targetFile
    }

    fun loadSnapshot(file: File): DebugModuleSnapshot? {
        return try {
            if (!file.exists()) return null
            DebugModuleSnapshot.fromJson(file.readText())
        } catch (e: Exception) {
            Log.e("DebugSnapshot", "Failed to load snapshot ${file.name}: ${e.message}")
            null
        }
    }

    fun deleteSnapshot(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else false
        } catch (_: Exception) {
            false
        }
    }
}
