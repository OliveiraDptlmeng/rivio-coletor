package br.rivio.coletor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPOutputStream

/** POST do JSON (gzip) para <servidor>/ingest. */
object Envio {
    data class Resposta(val codigo: Int, val corpo: String, val bytesEnviados: Int, val bytesOriginais: Int)

    suspend fun enviar(servidor: String, token: String, payload: JSONObject): Resposta = withContext(Dispatchers.IO) {
        val url = URL(servidor.trim().trimEnd('/') + "/ingest")
        val bruto = payload.toString().toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(bruto) } }.toByteArray()

        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 15_000
            con.readTimeout = 120_000
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("Content-Encoding", "gzip")
            con.setRequestProperty("ngrok-skip-browser-warning", "1")
            if (token.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            con.setFixedLengthStreamingMode(gz.size)
            con.outputStream.use { it.write(gz) }

            val codigo = con.responseCode
            val fluxo = if (codigo in 200..299) con.inputStream else con.errorStream
            val corpo = fluxo?.bufferedReader()?.use { it.readText() } ?: ""
            Resposta(codigo, corpo, gz.size, bruto.size)
        } finally {
            con.disconnect()
        }
    }
}

/** Configurações salvas no celular. */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("rivio", Context.MODE_PRIVATE)

    fun servidor(c: Context) = p(c).getString("servidor", "") ?: ""
    fun paciente(c: Context) = p(c).getString("paciente", "") ?: ""
    fun token(c: Context) = p(c).getString("token", "") ?: ""
    fun dias(c: Context) = p(c).getInt("dias", 30)

    fun salvar(c: Context, servidor: String, paciente: String, token: String, dias: Int) {
        p(c).edit()
            .putString("servidor", servidor.trim())
            .putString("paciente", paciente.trim())
            .putString("token", token.trim())
            .putInt("dias", dias)
            .apply()
    }

    fun ultimoEnvio(c: Context): Instant? = p(c).getString("ultimo_envio", null)?.let { Instant.parse(it) }
    fun marcarEnvio(c: Context, momento: Instant) = p(c).edit().putString("ultimo_envio", momento.toString()).apply()
}
