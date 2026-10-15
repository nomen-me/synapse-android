# Roteiro de integração real (executar quando a Synapse estiver no ar)

Regras: usar um **tenant/loja de teste**, nunca cobranças reais, nunca dados de produção para demonstração. Registrar a saída de cada passo (cole no relatório). Tudo abaixo que não for "somente leitura" está marcado.

## Passo 0 — Pré-requisitos
- `ritmo.<domínio>` e `harmonia.<domínio>` por HTTPS válido (certificado emitido). Usuário de teste com Role Synapse (ex.: Lojista) e outro com Role `Caixa`.
- Ter rodado o provisionamento (`provisionar_cliente_synapse2.sh`) ou, na VPS da Nomen, o patch de caixa — ver divergência em `AUDITORIA.md`.

## Passo 1 — Verificação somente-leitura do Ritmo (automática)
`python3 scripts/verificar_ritmo.py https://ritmo.<domínio> <usuario> [pos_profile]`
Cobre: login, `synapse_csrf_token`, 28 Custom Fields, 4 DocTypes, `ITEM-GENERICO`, POS Profile único com `payments`, `synapse_sincronizar_caixa`, `restricao_configurada`, operadores com PIN, `synapse_conta_acrescimos`.
Critério: **zero FAIL**. Cada FAIL tem a correção mínima em `AUDITORIA.md` §2 e `INVESTIGACAO_CONSUMO_501.md`. INDETERMINADO = o usuário não tem permissão para listar Custom Field (rode com um administrador).
(O verificador foi testado apenas contra um Ritmo simulado; sua lógica de PASS/FAIL foi validada, não o Ritmo real.)

## Passo 2 — Harmonia (somente leitura)
Com cookie de sessão: `POST https://harmonia.<domínio>/webhook/<slug>` corpo `{}` para `atendimento/conversas`, `monitoramento/metricas` (corpo `{"chart":"system.cpu","points":1,"format":"json","after":-1}`) e `consumo/servicos`.
Esperado: 200 com `{"conversas":[...]}`; 200 com `data`; **501** em `consumo/servicos` (estado atual). **Não** chamar `atendimento/enviar`, `lyra/alterar`, `fiscal/*`, `pagamentos/*` fora dos passos de escrita abaixo.

## Passo 3 — App, fluxos online (loja de teste)
1. Primeira abertura: informar o domínio; entrar; conferir abas do perfil. 2. Atendimento: listar, abrir, enviar UMA mensagem de teste a um contato de teste *(escrita)*. 3. Pedidos: listar; criar pedido de venda de teste *(escrita)*; conferir no Ritmo; pedir cancelamento via Lyra *(escrita)*.
Evidência: captura + o documento criado no Ritmo.

## Passo 4 — PDV offline (escrita em loja de teste)
1. Online: entrar, definir PIN (6 dígitos), aguardar a sincronização da autorização. 2. Colocar o aparelho em modo avião. 3. Sair e entrar **offline com o PIN** (errar o PIN 5× e ver o bloqueio de 60 s). 4. Abrir caixa, fazer 2 vendas (uma com desconto), fechar caixa — tudo offline. 5. Reativar a rede: a fila deve sair sozinha em ≤30 s. 6. Conferir no Ritmo: 1 POS Opening Entry, 2 POS Invoice, 1 POS Closing Entry; filtrar por `synapse_op_id` e confirmar **sem duplicidade**. 7. Repetir a sincronização e confirmar que nada novo é criado. 8. Matar o app durante a sincronização e reabrir: nenhuma venda duplicada.
Registrar: nomes dos documentos, horários, e qualquer operação "rejeitada" com a mensagem do Ritmo.

## Passo 5 — Acessibilidade e ergonomia (aparelho real)
TalkBack nas telas Login, Pedidos, Atendimento, PDV; fonte do sistema no máximo; rotação; tablet; teclado sobre os campos (Atendimento, Checkout, Login).

## Passo 6 — Itens conhecidos que podem falhar (não são surpresa)
`data.name` na criação de documentos; campos `custom_*` ausentes; `restricao_configurada=false`; `synapse_conta_acrescimos` vazio (vendas com acréscimo rejeitadas); workflows n8n não importados/ativos.

## Modelo de evidência (por passo)
`passo | data/hora | aparelho/versão do app | resultado (PASS/FAIL) | saída/captura | observação`
