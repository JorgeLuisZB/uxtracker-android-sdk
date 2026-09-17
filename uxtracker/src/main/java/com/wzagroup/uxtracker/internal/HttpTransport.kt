package com.wzagroup.uxtracker.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

internal data class HttpResponse(val status: Int, val body: String?, val retryAfterSeconds: Long?)

internal interface HttpTransport {
    /** @throws IOException on network failure */
    fun post(url: String, headers: Map<String, String>, json: String): HttpResponse
}

/** HttpURLConnection, not OkHttp: the SDK adds no dependencies to host apps. Bodies are gzipped (§2). */
internal class HttpUrlConnectionTransport : HttpTransport {

    override fun post(url: String, headers: Map<String, String>, json: String): HttpResponse {
        val body = gzip(json.toByteArray(Charsets.UTF_8))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false // §2.3: never follow to another host
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Content-Encoding", "gzip")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            return HttpResponse(status, responseBody, retryAfter)
        } finally {
            connection.disconnect()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
