package com.localzet.floortrace.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private const val USER_AGENT = "FloorTrace/0.1 (+https://localzet.com; Android spatial positioning prototype)"

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,image/*,*/*;q=0.8")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return connection.useBytes()
    }

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
    ): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return connection.useBytes()
    }

    private fun HttpURLConnection.useBytes(): ByteArray {
        try {
            val code = responseCode
            val source = if (code in 200..299) inputStream else errorStream
            val bytes = source?.let { stream ->
                BufferedInputStream(stream).use { input ->
                    val out = ByteArrayOutputStream()
                    input.copyTo(out)
                    out.toByteArray()
                }
            } ?: ByteArray(0)
            if (code !in 200..299) {
                val body = bytes.toString(Charsets.UTF_8).take(500)
                error("HTTP $code from $url: $body")
            }
            return bytes
        } finally {
            disconnect()
        }
    }
}
