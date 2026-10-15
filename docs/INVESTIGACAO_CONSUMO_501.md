# Investigação: `501 PENDENTE_FONTE_DE_DADOS` em `consumo/servicos`

Método: leitura do workflow `06_consumo_servicos.json`, dos workflows de logística/IA e dos instaladores do repositório. Nada foi criado nem alterado.

## O que acontece
O nó "Monta resposta" do workflow **sempre** devolve 501 com três pendências fixas (`ia`, `frete`, `freteVolume`) e, quando há cache, devolve só `freteSaldo` como `parcial`. Não existe caminho de sucesso no workflow. O painel e o app tratam o 501 como "métrica em configuração" (contrato preservado; nenhuma mudança necessária no app).

## Fonte correta, por campo (dentro da arquitetura existente)
| Campo | Fonte existente | Evidência | Estado |
|---|---|---|---|
| `freteSaldo` | DocType `Synapse Credencial Externa`, campo `saldo` (+ `cache_atualizado_em`) para `provedor = melhorenvio` | o próprio workflow já lê esse campo (nó "Monta resposta"); campos definidos em `synapse_credencial_externa.json` | já ligado; falta algo **preencher** o cache (não identifiquei no repositório o workflow que grava `saldo`) |
| `frete` (por semana) e `freteVolume` (no mês) | **Ritmo**: `Sales Invoice` com `custom_tracking_code != ''`, agrupado por `creation` (contagem semanal/mensal) | `W2_logistica.json` já grava `custom_tracking_code` no `Sales Invoice` e filtra por ele (nó "Busca Pedidos em Trânsito") | **Existe fonte, mas depende de campos que nenhum instalador cria** (ver abaixo) |
| `ia` (requisições por dia) | **Lyra Central**: `GET /v1/usage/:tenant_id` | o Harmonia já tem `LYRA_CENTRAL_URL`, `LYRA_API_KEY`, `LYRA_TENANT_ID` (usados em `Lyra_L1_triagem.json`). **O endpoint de uso não está documentado neste repositório**; a descrição que recebi antes o dá como *snapshot mensal*, não série diária | série **diária** não tem fonte; o que a Lyra Central dá (se confirmado) é total mensal |

## Achado colateral relevante (afeta também Pedidos/Envio e Pagamentos)
Estes campos são usados pelos workflows `W2_logistica`, `W3_pagamento`, `W4_posvenda`, `envio_situacao`, `Lyra_L2_consultas`, mas aparecem **0 vezes** em `instalar_nomen_completo.sh` e em `provisionar_cliente_synapse2.sh`:
`custom_tracking_code`, `custom_transportadora`, `custom_entregue`, `custom_melhorenvio_order_id`, `custom_payment_status`, `custom_infinitepay_charge_id`, `custom_pix_qr_code`.
Se não existirem no Ritmo real, esses workflows falham ao gravar/consultar. **Só o Ritmo real confirma** (rodar `scripts/verificar_ritmo.py`: ele checa os 7 campos em `Sales Invoice`).

## Correção mínima (PROPOSTA — não aplicada; é alteração de contrato/infra, precisa da sua autorização)
1. Confirmar no Ritmo real se os 7 campos `custom_*` existem; se não, criá-los (Custom Field em `Sales Invoice`; a doctype segue as URLs dos workflows) — idealmente dentro dos dois instaladores, no mesmo padrão `create_custom_fields` já usado.
2. `06_consumo_servicos.json`: trocar o 501 fixo por (a) contagem de `Sales Invoice` com `custom_tracking_code` por semana/mês (GET com `filters` + `fields`, mesmo padrão dos outros nós) para `frete`/`freteVolume`; (b) manter `freteSaldo` do cache; (c) para `ia`, **decidir**: mostrar o total mensal da Lyra Central (precisa confirmar o endpoint real com `curl`) ou pedir à Lyra Central uma série diária. Enquanto (c) estiver indefinido, responder 501 **só** para `ia` com os demais campos preenchidos exigiria mudar o contrato (hoje é tudo-ou-nada).
3. Nenhuma alteração no app: ele já exibe os dados quando o backend responder 200.
