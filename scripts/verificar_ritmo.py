#!/usr/bin/env python3
"""Verificação SOMENTE-LEITURA do Ritmo real contra o que o app Android e os workflows exigem.
Faz: 1 login (POST /api/method/login) e GETs. NÃO cria, altera nem apaga nada.
Uso:  python3 scripts/verificar_ritmo.py https://ritmo.SEUDOMINIO usuario@loja.com [pos_profile]    (a senha é pedida no terminal)
Saída: tabela PASS/FAIL/INDETERMINADO + código de saída 1 se houver FAIL. Cole a saída no relatório de integração."""
import getpass, json, sys, urllib.parse, urllib.request, http.cookiejar

CUSTOM_FIELDS = [  # (doctype, fieldname, origem)
    *[("Employee", f, "provisionar L1335-1349") for f in ("synapse_pin_hash", "synapse_pin_salt", "synapse_pin_kdf_iteracoes", "synapse_pin_definido_em")],
    *[(d, "synapse_op_id", "provisionar L1356-1389") for d in ("POS Opening Entry", "POS Closing Entry", "POS Invoice", "Sales Order")],
    *[(d, f, "provisionar L1374-1389") for d in ("POS Invoice", "Sales Order") for f in ("synapse_operador_real", "synapse_identificacao_atendimento")],
    *[(d, f, "provisionar L1391-1404") for d in ("POS Invoice Item", "Sales Order Item") for f in ("synapse_item_generico", "synapse_preco_autorizado_por", "synapse_preco_motivo")],
    ("POS Profile", "synapse_conta_acrescimos", "provisionar L1406-1413"),
    ("Customer", "synapse_pedido_op_id", "provisionar L1415-1430"),
    # usados pelos workflows W2/W3/W4/envio_situacao; NENHUM instalador do repositório os cria (contagem 0):
    *[("Sales Invoice", f, "workflows (sem instalador)") for f in ("custom_tracking_code", "custom_transportadora", "custom_entregue", "custom_melhorenvio_order_id", "custom_payment_status", "custom_infinitepay_charge_id", "custom_pix_qr_code")],
]
DOCTYPES = ["Synapse Producao Item", "Synapse Tema Loja", "Synapse Credencial Externa", "Synapse Composicao"]

class Cliente:
    def __init__(self, base):
        self.base = base.rstrip("/"); self.cj = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))
    def req(self, caminho, dados=None):
        r = urllib.request.Request(self.base + caminho, data=urllib.parse.urlencode(dados).encode() if dados else None, headers={"Accept": "application/json"})
        try:
            with self.op.open(r, timeout=20) as resp: return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            try: return e.code, json.loads(e.read() or b"{}")
            except Exception: return e.code, {}
        except Exception as e: return 0, {"erro": str(e)}
    def get(self, caminho, **params):
        return self.req(urllib.parse.quote(caminho, safe="/:%") + ("?" + urllib.parse.urlencode(params) if params else ""))

def main():
    if len(sys.argv) < 3: print(__doc__); sys.exit(2)
    c = Cliente(sys.argv[1]); usuario = sys.argv[2]; pos = sys.argv[3] if len(sys.argv) > 3 else None
    resultados = []
    def r(nome, estado, detalhe=""): resultados.append((estado, nome, detalhe))
    st, _ = c.req("/api/method/login", {"usr": usuario, "pwd": getpass.getpass("Senha: ")})
    if st != 200: r("login", "FAIL", f"HTTP {st}"); return fim(resultados)
    r("login", "PASS")
    st, d = c.get("/api/method/synapse_csrf_token"); r("Server Script synapse_csrf_token", "PASS" if st == 200 and d.get("message") else "FAIL", f"HTTP {st}")
    for dt, campo, origem in CUSTOM_FIELDS:
        filtros = json.dumps([["dt", "=", dt], ["fieldname", "=", campo]])
        st, d = c.get("/api/resource/Custom Field", filters=filtros, fields=json.dumps(["name"]), limit_page_length="1")
        r(f"Custom Field {dt}.{campo}", ("PASS" if d.get("data") else "FAIL") if st == 200 else "INDETERMINADO", f"{origem}" + ("" if st == 200 else f" (HTTP {st}: sem permissão para listar Custom Field?)"))
    for dt in DOCTYPES:
        st, d = c.get("/api/resource/DocType/" + urllib.parse.quote(dt)); r(f"DocType {dt}", "PASS" if st == 200 else ("FAIL" if st == 404 else "INDETERMINADO"), f"HTTP {st}")
    st, d = c.get("/api/resource/Item/ITEM-GENERICO"); r("Item ITEM-GENERICO", "PASS" if st == 200 else ("FAIL" if st == 404 else "INDETERMINADO"), f"HTTP {st}")
    st, d = c.get("/api/resource/POS Profile", fields=json.dumps(["name", "warehouse", "company"]), limit_page_length="5")
    perfis = d.get("data", []) if st == 200 else []
    r("POS Profile existe", "PASS" if perfis else "FAIL", f"{len(perfis)} encontrado(s)" if st == 200 else f"HTTP {st}")
    if len(perfis) > 1: r("POS Profile único (premissa do app/painel)", "FAIL", "há mais de um: o app usa o primeiro")
    alvo = pos or (perfis[0]["name"] if perfis else None)
    if alvo:
        st, d = c.get("/api/method/synapse_sincronizar_caixa", pos_profile=alvo)
        m = d.get("message") or {}
        r("Server Script synapse_sincronizar_caixa", "PASS" if st == 200 and "usuarios_autorizados" in m else "FAIL", f"HTTP {st}")
        if st == 200:
            r("POS Profile User configurado (restricao_configurada)", "PASS" if m.get("restricao_configurada") else "FAIL", "sem isso NENHUM operador entra offline")
            r("Operadores com PIN definido", "PASS" if any(u.get("pin_definido") for u in m.get("usuarios_autorizados", [])) else "FAIL", f"{sum(1 for u in m.get('usuarios_autorizados', []) if u.get('pin_definido'))} de {len(m.get('usuarios_autorizados', []))}")
        st, d = c.get("/api/resource/POS Profile/" + urllib.parse.quote(alvo))
        doc = d.get("data", {}) if st == 200 else {}
        r("POS Profile.payments (modo de pagamento)", "PASS" if doc.get("payments") else "FAIL", "")
        r("POS Profile.synapse_conta_acrescimos", "PASS" if doc.get("synapse_conta_acrescimos") else "INDETERMINADO", "sem conta, vendas com acréscimo são rejeitadas")
    fim(resultados)

def fim(resultados):
    larg = max(len(n) for _, n, _ in resultados)
    for estado, nome, det in resultados: print(f"{estado:<14} {nome:<{larg}}  {det}")
    falhas = sum(1 for e, _, _ in resultados if e == "FAIL")
    print(f"\n{sum(1 for e,_,_ in resultados if e=='PASS')} PASS · {falhas} FAIL · {sum(1 for e,_,_ in resultados if e=='INDETERMINADO')} INDETERMINADO")
    sys.exit(1 if falhas else 0)

if __name__ == "__main__": main()
