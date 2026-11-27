package com.minibrowser.data

import java.io.File

/** Мінімальне файлове сховище: атомарний запис (tmp → rename), усе локально в filesDir. */
class JsonFileStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun read(name: String): String? {
        val f = File(dir, name)
        return try {
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun write(name: String, text: String) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }
}
