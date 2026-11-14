package br.rivio.coletor

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/** Tipos de dado lidos e as permissões correspondentes. */
object Tipos {
    val LEITURA: List<KClass<out Record>> = listOf(
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        StepsRecord::class,
        SleepSessionRecord::class,
        ExerciseSessionRecord::class,
        OxygenSaturationRecord::class,
        BloodPressureRecord::class,
        WeightRecord::class,
        RespiratoryRateRecord::class,
    )

    val PERMISSOES_LEITURA: Set<String> =
        LEITURA.map { HealthPermission.getReadPermission(it) }.toSet()

    val HISTORICO: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    val SEGUNDO_PLANO: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Pacote do Samsung Health — aparece como "origem" dos registros que vieram dele. */
    const val PACOTE_SAMSUNG = "com.sec.android.app.shealth"
}

/**
 * Lê os registros do Health Connect numa janela de tempo e devolve um JSON "rivio-hc-v1":
 * { formato, paciente, janela, celular, permissoes, contagem, erros, tabelas: { fc: [...], sono: [...], ... } }
 * Cada linha carrega o lastro: origem (app que gravou), aparelho, tipo de aparelho e id do registro.
 */
class Coletor(private val client: HealthConnectClient) {

    suspend fun coletar(
        inicio: Instant,
        fim: Instant,
        concedidas: Set<String>,
        paciente: String,
        modo: String,
    ): JSONObject {
        val tabelas = JSONObject()
        val contagem = JSONObject()
        val erros = JSONObject()

        suspend fun <T : Record> passo(nome: String, tipo: KClass<T>, converter: (List<T>) -> JSONArray) {
            if (HealthPermission.getReadPermission(tipo) !in concedidas) {
                erros.put(nome, "sem permissão")
                return
            }
            try {
                val linhas = converter(ler(tipo, inicio, fim))
                tabelas.put(nome, linhas)
                contagem.put(nome, linhas.length())
            } catch (e: Exception) {
                erros.put(nome, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        passo("fc", HeartRateRecord::class) { fc(it) }
        passo("fc_repouso", RestingHeartRateRecord::class) { fcRepouso(it) }
        passo("hrv_rmssd", HeartRateVariabilityRmssdRecord::class) { hrv(it) }
        passo("passos", StepsRecord::class) { passos(it) }
        passo("sono", SleepSessionRecord::class) { sono(it) }
        passo("sono_estagios", SleepSessionRecord::class) { estagios(it) }
        passo("exercicio", ExerciseSessionRecord::class) { exercicio(it) }
        passo("spo2", OxygenSaturationRecord::class) { spo2(it) }
        passo("pressao", BloodPressureRecord::class) { pressao(it) }
        passo("peso", WeightRecord::class) { peso(it) }
        passo("freq_respiratoria", RespiratoryRateRecord::class) { respiracao(it) }

        return JSONObject().apply {
            put("formato", "rivio-hc-v1")
            put("modo", modo)
            put("paciente", JSONObject().put("nome", paciente))
            put("coletado_em", Instant.now().toString())
            put("janela", JSONObject().put("inicio", inicio.toString()).put("fim", fim.toString()))
            put(
                "celular", JSONObject()
                    .put("fabricante", Build.MANUFACTURER)
                    .put("modelo", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
            )
            put("permissoes", JSONArray(concedidas.map { it.substringAfterLast('.') }.sorted()))
            put("contagem", contagem)
            put("erros", erros)
            put("tabelas", tabelas)
        }
    }

    // ---------------------------------------------------------------- leitura paginada

    private suspend fun <T : Record> ler(tipo: KClass<T>, inicio: Instant, fim: Instant): List<T> {
        val saida = ArrayList<T>()
        var token: String? = null
        do {
            val resposta = client.readRecords(
                ReadRecordsRequest(
                    recordType = tipo,
                    timeRangeFilter = TimeRangeFilter.between(inicio, fim),
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            saida.addAll(resposta.records)
            token = resposta.pageToken
        } while (token != null)
        return saida
    }

    // ---------------------------------------------------------------- lastro de cada linha

    private class Lastro(m: Metadata) {
        val origem: String = m.dataOrigin.packageName
        val aparelho: String = m.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") } ?: ""
        val tipoAparelho: String = nomeAparelho(m.device?.type)
        val metodo: Int = m.recordingMethod
        val id: String = m.id
    }

    private fun linha(l: Lastro, vararg campos: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in campos) o.put(k, v ?: JSONObject.NULL)
        o.put("origem", l.origem)
        o.put("aparelho", l.aparelho)
        o.put("tipo_aparelho", l.tipoAparelho)
        o.put("metodo_registro", l.metodo)
        o.put("registro_id", l.id)
        return o
    }

    private fun fuso(o: ZoneOffset?): String = o?.id ?: ""

    // ---------------------------------------------------------------- conversores

    private fun fc(lista: List<HeartRateRecord>) = JSONArray().also { arr ->
        for (r in lista) {
            val l = Lastro(r.metadata)
            val f = fuso(r.startZoneOffset)
            for (s in r.samples) {
                arr.put(linha(l, "momento" to s.time.toString(), "bpm" to s.beatsPerMinute, "fuso" to f))
            }
        }
    }

    private fun fcRepouso(lista: List<RestingHeartRateRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(Lastro(r.metadata), "momento" to r.time.toString(), "bpm" to r.beatsPerMinute, "fuso" to fuso(r.zoneOffset))
        )
    }

    private fun hrv(lista: List<HeartRateVariabilityRmssdRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(Lastro(r.metadata), "momento" to r.time.toString(), "rmssd_ms" to r.heartRateVariabilityMillis, "fuso" to fuso(r.zoneOffset))
        )
    }

    private fun passos(lista: List<StepsRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(
                Lastro(r.metadata),
                "inicio" to r.startTime.toString(), "fim" to r.endTime.toString(),
                "passos" to r.count, "fuso" to fuso(r.startZoneOffset),
            )
        )
    }

    private fun sono(lista: List<SleepSessionRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(
                Lastro(r.metadata),
                "inicio" to r.startTime.toString(), "fim" to r.endTime.toString(),
                "minutos" to java.time.Duration.between(r.startTime, r.endTime).toMinutes(),
                "n_estagios" to r.stages.size, "titulo" to r.title, "fuso" to fuso(r.startZoneOffset),
            )
        )
    }

    private fun estagios(lista: List<SleepSessionRecord>) = JSONArray().also { arr ->
        for (r in lista) {
            val l = Lastro(r.metadata)
            for (s in r.stages) arr.put(
                linha(
                    l,
                    "inicio" to s.startTime.toString(), "fim" to s.endTime.toString(),
                    "estagio" to nomeEstagio(s.stage), "sessao_inicio" to r.startTime.toString(),
                    "fuso" to fuso(r.startZoneOffset),
                )
            )
        }
    }

    private fun exercicio(lista: List<ExerciseSessionRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(
                Lastro(r.metadata),
                "inicio" to r.startTime.toString(), "fim" to r.endTime.toString(),
                "tipo_exercicio" to r.exerciseType, "titulo" to r.title, "fuso" to fuso(r.startZoneOffset),
            )
        )
    }

    private fun spo2(lista: List<OxygenSaturationRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(Lastro(r.metadata), "momento" to r.time.toString(), "spo2" to r.percentage.value, "fuso" to fuso(r.zoneOffset))
        )
    }

    private fun pressao(lista: List<BloodPressureRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(
                Lastro(r.metadata),
                "momento" to r.time.toString(),
                "sistolica" to r.systolic.inMillimetersOfMercury,
                "diastolica" to r.diastolic.inMillimetersOfMercury,
                "fuso" to fuso(r.zoneOffset),
            )
        )
    }

    private fun peso(lista: List<WeightRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(Lastro(r.metadata), "momento" to r.time.toString(), "kg" to r.weight.inKilograms, "fuso" to fuso(r.zoneOffset))
        )
    }

    private fun respiracao(lista: List<RespiratoryRateRecord>) = JSONArray().also { arr ->
        for (r in lista) arr.put(
            linha(Lastro(r.metadata), "momento" to r.time.toString(), "rpm" to r.rate, "fuso" to fuso(r.zoneOffset))
        )
    }

    companion object {
        fun nomeEstagio(s: Int): String = when (s) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "acordado"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "dormindo"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "fora_da_cama"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "leve"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "profundo"
            SleepSessionRecord.STAGE_TYPE_REM -> "rem"
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "acordado_na_cama"
            else -> "desconhecido"
        }

        fun nomeAparelho(t: Int?): String = when (t) {
            Device.TYPE_WATCH -> "relogio"
            Device.TYPE_PHONE -> "celular"
            Device.TYPE_RING -> "anel"
            Device.TYPE_FITNESS_BAND -> "pulseira"
            Device.TYPE_SCALE -> "balanca"
            Device.TYPE_CHEST_STRAP -> "cinta_peitoral"
            Device.TYPE_HEAD_MOUNTED -> "cabeca"
            Device.TYPE_SMART_DISPLAY -> "tela"
            null -> ""
            else -> "desconhecido"
        }
    }
}
