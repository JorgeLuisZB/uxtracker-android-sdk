package com.wzagroup.uxtracker.internal

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
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
        return try {
            send(url, headers, body)
        } catch (e: IOException) {
            // Android reuses kept-alive connections the server may already have closed ("unexpected end of stream").
            // Retrying once on a new connection is safe: the server deduplicates events by event_id (§5.2).
            if (!isStaleConnection(e)) throw e
            send(url, headers, body)
        }
    }

    private fun send(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
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

    private fun isStaleConnection(e: IOException): Boolean {
        // Android (OkHttp): "unexpected end of stream"; JVM: "Unexpected end of file from server".
        val message = e.message.orEmpty().lowercase()
        return e is EOFException || message.contains("unexpected end of") ||
            (e is SocketException && (message.contains("reset") || message.contains("broken pipe")))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
