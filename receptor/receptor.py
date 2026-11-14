"""
Receptor Rivio — recebe o JSON do app "Rivio Coletor" (Health Connect) e guarda por paciente.

    pip install -r requirements.txt
    uvicorn receptor:app --host 0.0.0.0 --port 8000

Para o celular alcançar o notebook:
  * mesma rede Wi-Fi:  http://<IP-do-notebook>:8000   (libere a porta 8000 no firewall)
  * qualquer rede:     cloudflared tunnel --url http://localhost:8000   (não precisa de conta)
                       ou  ngrok http 8000

O que ele faz em cada envio:
  recebidos/<paciente>/brutos/<data-hora>_<modo>.json   ← payload original (auditoria / lastro)
  recebidos/<paciente>/csv/<tabela>.csv                 ← tabelas acumuladas e sem duplicatas
  recebidos/<paciente>/ultimo_envio.json                ← resumo do último envio

Variáveis de ambiente opcionais:
  RIVIO_TOKEN=segredo        exige "Authorization: Bearer segredo" (mesmo token no app)
  RIVIO_DIR=recebidos        pasta de saída
  RIVIO_POS_INGEST='python -m pipeline.run --entrada "{csv}" --nome "{nome}"'
                             comando rodado em segundo plano depois de cada envio
"""
from __future__ import annotations

import gzip
import json
import os
import re
import shlex
import subprocess
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse

PASTA = Path(os.environ.get("RIVIO_DIR", "recebidos"))
TOKEN = os.environ.get("RIVIO_TOKEN", "").strip()
POS_INGEST = os.environ.get("RIVIO_POS_INGEST", "").strip()

# Colunas que identificam uma linha única em cada tabela (para descartar reenvios)
CHAVES = {
    "fc": ["momento", "registro_id"],
    "sono_estagios": ["inicio", "registro_id"],
}
CHAVE_PADRAO = ["registro_id"]

app = FastAPI(title="Rivio · receptor de wearables")


def slug(texto: str) -> str:
    t = unicodedata.normalize("NFKD", texto or "").encode("ascii", "ignore").decode()
    t = re.sub(r"[^a-zA-Z0-9]+", "-", t).strip("-").lower()
    return t or "sem-nome"


def acumular_csv(pasta_csv: Path, tabelas: dict) -> dict:
    """Junta as linhas novas ao CSV de cada tabela, sem duplicar. Devolve {tabela: (novas, total)}."""
    pasta_csv.mkdir(parents=True, exist_ok=True)
    resumo = {}
    for nome, linhas in tabelas.items():
        if not linhas:
            continue
        novo = pd.DataFrame(linhas)
        arq = pasta_csv / f"{nome}.csv"
        antes = 0
        if arq.exists():
            velho = pd.read_csv(arq)
            antes = len(velho)
            junto = pd.concat([velho, novo], ignore_index=True)
        else:
            junto = novo
        chave = [c for c in CHAVES.get(nome, CHAVE_PADRAO) if c in junto.columns]
        junto = junto.drop_duplicates(subset=chave or None, keep="last")
        ordem = next((c for c in ("momento", "inicio") if c in junto.columns), None)
        if ordem:
            junto = junto.sort_values(ordem)
        junto.to_csv(arq, index=False)
        resumo[nome] = {"novas": len(junto) - antes, "total": len(junto)}
    return resumo


@app.post("/ingest")
async def ingest(
    request: Request,
    authorization: str | None = Header(default=None),
    content_encoding: str | None = Header(default=None),
):
    if TOKEN and authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="token inválido")

    corpo = await request.body()
    if (content_encoding or "").lower() == "gzip":
        corpo = gzip.decompress(corpo)
    try:
        payload = json.loads(corpo)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=400, detail=f"JSON inválido: {e}")
    if payload.get("formato") != "rivio-hc-v1":
        raise HTTPException(status_code=400, detail="formato desconhecido (esperado rivio-hc-v1)")

    nome = (payload.get("paciente") or {}).get("nome") or "sem-nome"
    pasta_pac = PASTA / slug(nome)
    agora = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    brutos = pasta_pac / "brutos"
    brutos.mkdir(parents=True, exist_ok=True)
    (brutos / f"{agora}_{payload.get('modo', 'envio')}.json").write_bytes(corpo)

    resumo = acumular_csv(pasta_pac / "csv", payload.get("tabelas") or {})
    info = {
        "recebido_em": agora,
        "paciente": nome,
        "modo": payload.get("modo"),
        "janela": payload.get("janela"),
        "celular": payload.get("celular"),
        "contagem": payload.get("contagem"),
        "erros": payload.get("erros"),
        "tabelas": resumo,
    }
    (pasta_pac / "ultimo_envio.json").write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[{agora}] {nome}: " + ", ".join(f"{k} +{v['novas']}" for k, v in resumo.items()))

    if POS_INGEST:
        cmd = POS_INGEST.format(csv=str((pasta_pac / "csv").resolve()), nome=nome, pasta=str(pasta_pac.resolve()))
        log = open(pasta_pac / "pos_ingest.log", "a", encoding="utf-8")
        subprocess.Popen(shlex.split(cmd), stdout=log, stderr=subprocess.STDOUT)

    return {"ok": True, "paciente": nome, "tabelas": resumo}


@app.get("/", response_class=HTMLResponse)
def painel():
    linhas = []
    for p in sorted(PASTA.glob("*/ultimo_envio.json")):
        i = json.loads(p.read_text(encoding="utf-8"))
        tabs = ", ".join(f"{k}: {v['total']}" for k, v in (i.get("tabelas") or {}).items())
        linhas.append(f"<tr><td>{i['paciente']}</td><td>{i['recebido_em']}</td><td>{i.get('modo')}</td><td>{tabs}</td></tr>")
    corpo = "".join(linhas) or "<tr><td colspan=4>Nenhum envio ainda.</td></tr>"
    return f"""<html><head><meta charset="utf-8"><title>Rivio receptor</title>
    <style>body{{font-family:system-ui;margin:2rem}}td,th{{border-bottom:1px solid #ddd;padding:.4rem .8rem;text-align:left}}</style>
    </head><body><h2>Rivio · receptor de wearables</h2><p>POST /ingest está no ar.</p>
    <table><tr><th>Paciente</th><th>Último envio (UTC)</th><th>Modo</th><th>Linhas acumuladas</th></tr>{corpo}</table>
    </body></html>"""


@app.get("/saude")
def saude():
    return {"ok": True}
