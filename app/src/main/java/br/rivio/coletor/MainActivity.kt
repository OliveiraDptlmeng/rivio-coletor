package br.rivio.coletor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {

    private var cliente: HealthConnectClient? = null

    private lateinit var campoServidor: EditText
    private lateinit var campoPaciente: EditText
    private lateinit var campoToken: EditText
    private lateinit var campoDias: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView

    // Abre a tela de permissões do Health Connect e recebe o que o usuário concedeu
    private val pedirPermissoes = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { concedidas ->
        escrever("Permissões concedidas nesta tela: ${concedidas.size}")
        atualizarStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        montarTela()
        verificarHealthConnect()
    }

    override fun onResume() {
        super.onResume()
        atualizarStatus()
    }

    // ------------------------------------------------------------------ Health Connect

    private fun verificarHealthConnect() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                cliente = HealthConnectClient.getOrCreate(this)
                escrever("Health Connect disponível.")
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                escrever("Health Connect precisa ser instalado/atualizado. Abrindo a Play Store…")
                abrir(
                    Intent(Intent.ACTION_VIEW).apply {
                        setPackage("com.android.vending")
                        data = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        putExtra("overlay", true)
                        putExtra("callerId", packageName)
                    }
                )
            }
            else -> escrever("Health Connect NÃO está disponível neste aparelho (Android < 8 ou perfil sem suporte).")
        }
    }

    private fun atualizarStatus() {
        val c = cliente ?: run { status.text = "Health Connect indisponível"; return }
        lifecycleScope.launch {
            try {
                val g = c.permissionController.getGrantedPermissions()
                val leitura = Tipos.PERMISSOES_LEITURA.count { it in g }
                val ultimo = Config.ultimoEnvio(this@MainActivity)?.toString() ?: "nunca"
                status.text = buildString {
                    append("Leitura: $leitura/${Tipos.PERMISSOES_LEITURA.size} tipos")
                    append("   ·   Histórico: ${if (Tipos.HISTORICO in g) "sim" else "não"}")
                    append("   ·   Segundo plano: ${if (Tipos.SEGUNDO_PLANO in g) "sim" else "não"}")
                    append("\nÚltimo envio: $ultimo")
                }
            } catch (e: Exception) {
                status.text = "Erro ao consultar permissões: ${e.message}"
            }
        }
    }

    private fun acaoPermissoesBasicas() {
        if (cliente == null) return escrever("Health Connect indisponível.")
        pedirPermissoes.launch(Tipos.PERMISSOES_LEITURA)
    }

    private fun acaoPermissoesExtras() {
        if (cliente == null) return escrever("Health Connect indisponível.")
        // Pedidas separadamente: em versões antigas do Health Connect elas não existem.
        pedirPermissoes.launch(setOf(Tipos.HISTORICO, Tipos.SEGUNDO_PLANO))
    }

    private fun acaoColetar(enviar: Boolean) {
        salvarCampos()
        val c = cliente ?: return escrever("Health Connect indisponível.")
        val servidor = Config.servidor(this)
        if (enviar && servidor.isBlank()) return escrever("Preencha o endereço do servidor.")

        lifecycleScope.launch {
            try {
                val concedidas = c.permissionController.getGrantedPermissions()
                if (Tipos.PERMISSOES_LEITURA.none { it in concedidas }) {
                    escrever("Nenhuma permissão de leitura concedida. Toque no botão 1.")
                    return@launch
                }
                var dias = Config.dias(this@MainActivity)
                if (Tipos.HISTORICO !in concedidas && dias > 29) {
                    dias = 29
                    escrever("Sem permissão de histórico → limitando a 29 dias.")
                }
                val fim = Instant.now()
                val inicio = fim.minus(dias.toLong(), ChronoUnit.DAYS)
                escrever("Lendo $dias dias do Health Connect…")

                val payload = withContext(Dispatchers.Default) {
                    Coletor(c).coletar(inicio, fim, concedidas, Config.paciente(this@MainActivity), "completo")
                }
                resumir(payload)

                if (!enviar) return@launch
                escrever("Enviando para $servidor/ingest …")
                val r = Envio.enviar(servidor, Config.token(this@MainActivity), payload)
                escrever("Enviado ${r.bytesOriginais / 1024} KB (${r.bytesEnviados / 1024} KB compactado)")
                escrever("Servidor respondeu ${r.codigo}: ${r.corpo.take(400)}")
                if (r.codigo in 200..299) Config.marcarEnvio(this@MainActivity, fim)
                atualizarStatus()
            } catch (e: Exception) {
                escrever("ERRO: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun resumir(payload: JSONObject) {
        val contagem = payload.getJSONObject("contagem")
        val erros = payload.getJSONObject("erros")
        escrever("Registros lidos:")
        contagem.keys().forEach { escrever("  • $it: ${contagem.getInt(it)}") }
        if (erros.length() > 0) {
            escrever("Não lidos:")
            erros.keys().forEach { escrever("  • $it: ${erros.getString(it)}") }
        }
        // Mostra de onde vieram os dados (prova de que o Samsung Health está chegando)
        val origens = mutableMapOf<String, Int>()
        val tabelas = payload.getJSONObject("tabelas")
        tabelas.keys().forEach { t ->
            val arr = tabelas.getJSONArray(t)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val chave = o.optString("origem") + (o.optString("aparelho").let { if (it.isBlank()) "" else " ($it)" })
                origens[chave] = (origens[chave] ?: 0) + 1
            }
        }
        if (origens.isNotEmpty()) {
            escrever("Origens:")
            origens.entries.sortedByDescending { it.value }.forEach {
                val marca = if (it.key.startsWith(Tipos.PACOTE_SAMSUNG)) " ← Samsung Health" else ""
                escrever("  • ${it.key}: ${it.value}$marca")
            }
        }
        val fc = tabelas.optJSONArray("fc")
        if (fc != null && fc.length() > 0) {
            val u = fc.getJSONObject(fc.length() - 1)
            escrever("Última FC: ${u.optLong("bpm")} bpm em ${u.optString("momento")}")
        }
    }

    private fun acaoSincronizacao(ativar: Boolean) {
        salvarCampos()
        if (ativar) {
            SyncWorker.agendar(this)
            escrever("Sincronização automática ATIVADA (a cada ~15 min, precisa da permissão de segundo plano).")
        } else {
            SyncWorker.cancelar(this)
            escrever("Sincronização automática desativada.")
        }
    }

    private fun abrirHealthConnect() {
        val acao = if (Build.VERSION.SDK_INT >= 34) "android.health.connect.action.HEALTH_HOME_SETTINGS"
        else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
        abrir(Intent(acao))
    }

    private fun abrir(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            escrever("Não consegui abrir: ${intent.action}")
        }
    }

    // ------------------------------------------------------------------ tela

    private fun salvarCampos() {
        Config.salvar(
            this,
            campoServidor.text.toString(),
            campoPaciente.text.toString(),
            campoToken.text.toString(),
            campoDias.text.toString().toIntOrNull()?.coerceIn(1, 3650) ?: 30,
        )
    }

    private fun escrever(msg: String) {
        val hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        runOnUiThread { log.append("[$hora] $msg\n") }
    }

    private fun montarTela() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 40)
        }
        fun rotulo(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, 18, 0, 0) }
        fun campo(dica: String, valor: String, tipo: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            hint = dica; setText(valor); inputType = tipo; setSingleLine(true)
        }
        fun botao(t: String, acao: () -> Unit) = Button(this).apply {
            text = t; setAllCaps(false); setOnClickListener { acao() }
        }

        raiz.addView(TextView(this).apply {
            text = "Rivio · Coletor de wearable"; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        })
        raiz.addView(TextView(this).apply {
            text = "Samsung Health → Health Connect → este app → servidor Rivio"; textSize = 13f
        })
        status = TextView(this).apply { setPadding(0, 18, 0, 0); textSize = 13f }
        raiz.addView(status)

        raiz.addView(rotulo("Servidor (ex.: https://xxxx.trycloudflare.com ou http://192.168.0.10:8000)"))
        campoServidor = campo("https://…", Config.servidor(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        raiz.addView(campoServidor)
        raiz.addView(rotulo("Nome do paciente"))
        campoPaciente = campo("Nome", Config.paciente(this))
        raiz.addView(campoPaciente)
        raiz.addView(rotulo("Token (opcional, igual ao do servidor)"))
        campoToken = campo("token", Config.token(this))
        raiz.addView(campoToken)
        raiz.addView(rotulo("Dias de histórico"))
        campoDias = campo("30", Config.dias(this).toString(), InputType.TYPE_CLASS_NUMBER)
        raiz.addView(campoDias)

        raiz.addView(botao("1. Conceder permissões de leitura") { acaoPermissoesBasicas() })
        raiz.addView(botao("2. Permitir histórico + segundo plano") { acaoPermissoesExtras() })
        raiz.addView(botao("3. Ler e ENVIAR agora") { acaoColetar(enviar = true) })
        raiz.addView(botao("Só ler (testar sem servidor)") { acaoColetar(enviar = false) })
        raiz.addView(botao("Ativar sincronização automática") { acaoSincronizacao(true) })
        raiz.addView(botao("Desativar sincronização automática") { acaoSincronizacao(false) })
        raiz.addView(botao("Abrir Health Connect") { abrirHealthConnect() })

        log = TextView(this).apply {
            setPadding(0, 24, 0, 0); textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        raiz.addView(log)

        setContentView(ScrollView(this).apply { addView(raiz) })
    }
}
