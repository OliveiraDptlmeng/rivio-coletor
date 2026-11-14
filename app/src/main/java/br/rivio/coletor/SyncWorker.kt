package br.rivio.coletor

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Sincronização automática: a cada ~15 min lê do último envio (com 24 h de sobreposição,
 * porque o relógio/Samsung Health às vezes sincroniza atrasado) e manda ao servidor.
 * O servidor descarta duplicatas. Só roda se o usuário deu a permissão de segundo plano.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return Result.success()
        val servidor = Config.servidor(ctx)
        if (servidor.isBlank()) return Result.success()

        val client = HealthConnectClient.getOrCreate(ctx)
        return try {
            val concedidas = client.permissionController.getGrantedPermissions()
            if (Tipos.SEGUNDO_PLANO !in concedidas) return Result.success()

            val fim = Instant.now()
            val inicio = Config.ultimoEnvio(ctx)?.minus(24, ChronoUnit.HOURS) ?: fim.minus(2, ChronoUnit.DAYS)
            val payload = Coletor(client).coletar(inicio, fim, concedidas, Config.paciente(ctx), "incremental")
            val r = Envio.enviar(servidor, Config.token(ctx), payload)
            if (r.codigo in 200..299) {
                Config.marcarEnvio(ctx, fim)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NOME = "rivio-sync"

        fun agendar(ctx: Context) {
            val pedido = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NOME, ExistingPeriodicWorkPolicy.UPDATE, pedido)
        }

        fun cancelar(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
        }
    }
}
