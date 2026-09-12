package com.sellsan.app

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudClient {
    data class Result(val ok: Boolean, val body: JSONObject?, val message: String)

    fun request(baseUrl: String, path: String, method: String = "GET", token: String = "", body: JSONObject? = null): Result {
        return try {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isBlank()) return Result(false, null, "Servidor não configurado")
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) doOutput = true
            }
            if (body != null) OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.readText().orEmpty()
            val json = try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject().put("raw", raw) }
            if (conn.responseCode in 200..299) Result(true, json, json.optString("message", "OK"))
            else Result(false, json, json.optString("error", json.optString("message", "Erro ${conn.responseCode}")))
        } catch (e: Exception) {
            Result(false, null, e.message ?: "Falha de conexão")
        }
    }
}
