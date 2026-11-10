package biz.rakendus.mb

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Tiny blocking HTTP helper. Always call from a background thread. */
class Api(var base: String, var key: String) {
    private fun open(path: String, method: String): HttpURLConnection {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.setRequestProperty("X-Key", key)
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    fun get(path: String): String {
        var last: Exception? = null
        for (i in 1..3) {
            try {
                val c = open(path, "GET")
                val code = c.responseCode
                if (code != 200) { c.disconnect(); throw RuntimeException("HTTP $code") }
                val bo = ByteArrayOutputStream()
                c.inputStream.use { it.copyTo(bo) }
                c.disconnect()
                return bo.toString("UTF-8")
            } catch (e: Exception) {
                last = e
                try { Thread.sleep(600L * i) } catch (x: InterruptedException) {}
            }
        }
        throw last ?: RuntimeException("network")
    }

    fun put(path: String, body: String) {
        val c = open(path, "PUT")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect()
        if (code != 200) throw RuntimeException("HTTP $code")
    }

    fun musicUrl(name: String): String = base.trimEnd('/') + "/music/" + android.net.Uri.encode(name)
}
