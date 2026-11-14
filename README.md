# Rivio Coletor — Samsung Health → Health Connect → servidor Rivio

App Android que lê os dados do wearable (Galaxy Watch/Ring via Samsung Health, e também Fitbit, Garmin,
Oura, Xiaomi… tudo que sincroniza com o **Health Connect**) e envia para o servidor Rivio, que guarda
por paciente em CSVs prontos para o pipeline.

```
Galaxy Watch → Samsung Health → Health Connect → Rivio Coletor (este app) → POST /ingest → receptor → CSVs → pipeline
```

Por que Health Connect e não a API da Samsung: o Samsung Health não tem API na nuvem; o SDK direto
(Samsung Health Data SDK) só funciona fora do modo desenvolvedor depois de aprovação de parceria.
O Samsung Health já sincroniza para o Health Connect, que é aberto a qualquer app com permissão do usuário.

---

## 1. Celular (5 min)

1. **Samsung Health → ⋮ → Configurações → Health Connect** → permitir que o Samsung Health
   **grave** os dados (FC, sono, passos, exercício, SpO2…).
   - Android 14+: o Health Connect fica em *Configurações → Segurança e privacidade → Health Connect*
     (ou *Configurações → Saúde*). Android 13 ou menos: instalar "Health Connect" na Play Store.
2. Abra o Health Connect → **Dados e acesso** e confira que já aparece frequência cardíaca/sono vindos do Samsung Health.
3. Ative a **depuração USB**: Configurações → Sobre o telefone → Informações do software →
   toque 7× em "Número de compilação" → volte → Opções do desenvolvedor → Depuração USB.

## 2. Compilar e instalar o app (15–20 min na primeira vez)

1. Android Studio → **Open** → selecione a pasta `rivio-coletor` (a que tem `settings.gradle.kts`).
2. Espere o *Gradle Sync* (baixa dependências na 1ª vez). Se ele oferecer atualizar AGP/Gradle, pode aceitar.
3. Conecte o celular pelo USB, aceite "permitir depuração" no celular, escolha o aparelho no topo e clique **Run ▶**.
   - Sem Android Studio? `./gradlew assembleDebug` (precisa do Android SDK) → `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Subir o receptor no notebook

```bash
cd receptor
pip install -r requirements.txt
uvicorn receptor:app --host 0.0.0.0 --port 8000
```

Em outro terminal, um túnel público (o celular alcança de qualquer rede):

```bash
cloudflared tunnel --url http://localhost:8000      # imprime https://xxxx.trycloudflare.com
# ou: ngrok http 8000
```

Teste sem celular: `python simular_envio.py http://localhost:8000` → abra http://localhost:8000.

Opcional: `RIVIO_TOKEN=segredo` exige o mesmo token no app;
`RIVIO_POS_INGEST='python -m pipeline.run --entrada "{csv}" --nome "{nome}"'` roda o pipeline a cada envio.

## 4. Usar o app

1. Preencha **Servidor** (URL do túnel), **Nome do paciente** e **Dias** (30 sem histórico).
2. **1. Conceder permissões de leitura** → no Health Connect, marque "Permitir tudo".
3. **2. Permitir histórico + segundo plano** (Android 14+ / Health Connect recente; se não aparecer, ignore).
4. **Só ler** → o log mostra quantos registros de cada tipo e as **origens**
   (`com.sec.android.app.shealth ← Samsung Health` = está chegando do relógio).
5. **3. Ler e ENVIAR agora** → resposta 200 com as linhas novas por tabela.
6. **Ativar sincronização automática** → a cada ~15 min manda o que é novo (precisa da permissão de segundo plano).

## O que chega no servidor

`recebidos/<paciente>/csv/`: `fc.csv`, `fc_repouso.csv`, `hrv_rmssd.csv`, `passos.csv`, `sono.csv`,
`sono_estagios.csv`, `exercicio.csv`, `spo2.csv`, `pressao.csv`, `peso.csv`, `freq_respiratoria.csv`.

Toda linha tem horário em UTC (`momento` ou `inicio`/`fim`) + `fuso` local, e o lastro:
`origem` (app que gravou), `aparelho`, `tipo_aparelho` (relogio/anel/celular…), `metodo_registro`
(0 desconhecido, 1 ativo, 2 automático, 3 manual) e `registro_id`. Os JSONs originais ficam em `brutos/`.

## Problemas comuns

| Sintoma | Causa / solução |
|---|---|
| Botão 1 não abre nada | Health Connect desatualizado/ausente (Android ≤ 13: instale/atualize pela Play Store). |
| "sem permissão" para um tipo | Não foi marcado na tela do Health Connect → botão 1 de novo ou "Abrir Health Connect". |
| 0 registros de FC/sono | O Samsung Health não está gravando no Health Connect (passo 1.1) ou o relógio ainda não sincronizou. |
| Só 30 dias | Sem a permissão de histórico (botão 2). É limite do Health Connect. |
| Erro de conexão | URL errada, receptor parado, ou firewall bloqueando a porta 8000 (use o túnel). |
| ECG, ronco, temperatura da pele | Não passam pelo Health Connect → continuam entrando pelo export do Samsung Health. |

## Próximos passos (pós-hackathon)

- Adaptador `pipeline/fontes/rivio_hc.py` lendo `recebidos/<paciente>/csv/` para o esquema canônico.
- Samsung Health Data SDK (dados exclusivos Samsung) após aprovação de parceria.
- iOS: mesmo app em Flutter (`health`) ou Swift (HealthKit), mesmo formato `rivio-hc-v1`.
- Play Store: formulário de declaração de apps de saúde + política de privacidade pública.
