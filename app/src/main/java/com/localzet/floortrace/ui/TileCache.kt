package com.localzet.floortrace.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.localzet.floortrace.net.Http
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class TileCache(context: Context) {
    private val diskDir = File(context.cacheDir, "osm_tiles").apply { mkdirs() }
    private val executor = Executors.newFixedThreadPool(3)
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val memory = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 96
    }

    @Synchronized
    fun get(z: Int, x: Int, y: Int, onLoaded: () -> Unit): Bitmap? {
        val n = 1 shl z
        if (y !in 0 until n) return null
        val wrappedX = ((x % n) + n) % n
        val key = "$z-$wrappedX-$y"
        memory[key]?.let { return it }

        val file = File(diskDir, "$key.png")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                memory[key] = bitmap
                return bitmap
            }
        }

        if (pending.add(key)) {
            executor.execute {
                try {
                    val bytes = Http.get(
                        "https://tile.openstreetmap.org/$z/$wrappedX/$y.png",
                        headers = mapOf("Referer" to "https://www.openstreetmap.org/"),
                        timeoutMs = 12_000,
                    )
                    if (bytes.isNotEmpty()) {
                        file.writeBytes(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                            synchronized(this) { memory[key] = bitmap }
                        }
                        onLoaded()
                    }
                } catch (_: Throwable) {
                    // A failed tile is simply retried on a later paint.
                } finally {
                    pending.remove(key)
                }
            }
        }
        return null
    }
}
