"""
Simula um envio do app (mesmo formato rivio-hc-v1), para testar o receptor sem celular.

    python simular_envio.py                       # manda para http://localhost:8000
    python simular_envio.py https://xxxx.trycloudflare.com --token segredo
"""
import argparse
import gzip
import json
import random
import urllib.request
from datetime import datetime, timedelta, timezone

SAMSUNG = {"origem": "com.sec.android.app.shealth", "aparelho": "Samsung SM-R960",
           "tipo_aparelho": "relogio", "metodo_registro": 2}


def payload(nome: str, dias: int = 7) -> dict:
    fim = datetime.now(timezone.utc).replace(microsecond=0)
    ini = fim - timedelta(days=dias)
    fc, sono, est, passos = [], [], [], []
    t = ini
    while t < fim:
        noite = 3 <= t.hour < 10  # 0h–7h em -03:00
        fc.append({"momento": t.isoformat().replace("+00:00", "Z"),
                   "bpm": int(random.gauss(58 if noite else 76, 4)), "fuso": "-03:00",
                   "registro_id": f"fc-{t:%Y%m%d%H}", **SAMSUNG})
        t += timedelta(minutes=10)
    for d in range(dias):
        s = (ini + timedelta(days=d)).replace(hour=3, minute=0, second=0)
        e = s + timedelta(hours=7)
        rid = f"sono-{s:%Y%m%d}"
        sono.append({"inicio": s.isoformat().replace("+00:00", "Z"), "fim": e.isoformat().replace("+00:00", "Z"),
                     "minutos": 420, "n_estagios": 4, "titulo": None, "fuso": "-03:00", "registro_id": rid, **SAMSUNG})
        cur = s
        for nome_est, minutos in [("leve", 120), ("profundo", 90), ("rem", 100), ("leve", 110)]:
            est.append({"inicio": cur.isoformat().replace("+00:00", "Z"),
                        "fim": (cur + timedelta(minutes=minutos)).isoformat().replace("+00:00", "Z"),
                        "estagio": nome_est, "sessao_inicio": s.isoformat().replace("+00:00", "Z"),
                        "fuso": "-03:00", "registro_id": rid, **SAMSUNG})
            cur += timedelta(minutes=minutos)
        passos.append({"inicio": (s + timedelta(hours=8)).isoformat().replace("+00:00", "Z"),
                       "fim": (s + timedelta(hours=20)).isoformat().replace("+00:00", "Z"),
                       "passos": random.randint(4000, 11000), "fuso": "-03:00",
                       "registro_id": f"passos-{s:%Y%m%d}", **SAMSUNG})
    tabelas = {"fc": fc, "sono": sono, "sono_estagios": est, "passos": passos}
    return {
        "formato": "rivio-hc-v1", "modo": "completo", "paciente": {"nome": nome},
        "coletado_em": fim.isoformat(), "janela": {"inicio": ini.isoformat(), "fim": fim.isoformat()},
        "celular": {"fabricante": "samsung", "modelo": "SM-S921B", "android": "15"},
        "permissoes": ["READ_HEART_RATE", "READ_SLEEP", "READ_STEPS"],
        "contagem": {k: len(v) for k, v in tabelas.items()},
        "erros": {"spo2": "sem permissão"}, "tabelas": tabelas,
    }


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("servidor", nargs="?", default="http://localhost:8000")
    ap.add_argument("--token", default="")
    ap.add_argument("--nome", default="Paciente Teste")
    a = ap.parse_args()
    corpo = gzip.compress(json.dumps(payload(a.nome)).encode())
    req = urllib.request.Request(a.servidor.rstrip("/") + "/ingest", data=corpo, method="POST",
                                 headers={"Content-Type": "application/json", "Content-Encoding": "gzip",
                                          **({"Authorization": f"Bearer {a.token}"} if a.token else {})})
    with urllib.request.urlopen(req) as r:
        print(r.status, r.read().decode())
