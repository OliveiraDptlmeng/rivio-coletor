package br.rivio.coletor

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela de "por que pedimos estes dados". O Health Connect EXIGE que ela exista
 * (ação androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE) para mostrar o pedido de permissão.
 */
class PrivacidadeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texto = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 16f
            text = """
                Rivio Coletor — uso dos seus dados de saúde

                O que lemos: frequência cardíaca, FC de repouso, variabilidade (HRV), passos, sono e estágios,
                exercícios, SpO2, pressão arterial, peso e frequência respiratória, a partir do Health Connect
                (para onde o Samsung Health e outros apps de wearable sincronizam).

                Para quê: montar o seu histórico de saúde unificado e um resumo para o seu médico.
                Alertas são observações, não diagnósticos.

                Para onde vai: somente para o servidor configurado no app, pela rede, compactado.
                Nada é vendido nem compartilhado com terceiros.

                Seu controle: você pode revogar qualquer permissão a qualquer momento nas configurações
                do Health Connect, e pedir a exclusão dos dados enviados.

                Base legal (LGPD): consentimento específico para dados pessoais sensíveis de saúde.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(texto) })
    }
}
