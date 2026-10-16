# Auditoria da Etapa 3 — synapse-android-etapa3

Data: 13/10/2026. Base de referência: repositório `nomen-me/desenvolvimento`, commit `011af81` (12/10/2026), arquivo `synapse_painel_18-09t.html`.
**Nada foi compilado. Nenhum teste foi executado. Nenhuma chamada foi feita a um Ritmo/Harmonia reais.** Tudo abaixo que diz "existe" significa
"existe no código/script do repositório", nunca "existe no Ritmo em produção".

## 1. Estado das telas e áreas

| Área | Estado | Observação |
|---|---|---|
| Domínio da loja, Login, sessão, Sair | **Tela implementada** | não executada em aparelho |
| Navegação (barra inferior + "Mais" / NavigationRail) e menu do usuário | **Implementada** | |
| Atendimento (lista, filtro, detalhe, contexto do cliente, enviar texto) | **Implementada** | **Anexos NÃO** implementados (cliente multipart pronto, sem tela) |
| Dashboard | **Implementada, parcial** | faturamento, fluxo, conversas, pedidos em espera, gráfico 7 dias. **Sem** alertas de fila do PDV e de transferências entre unidades |
| Pedidos (lista, busca, filtro, detalhe, cancelar via Lyra, situação de envio, criar venda/compra) | **Implementada** | |
| PDV | **PLACEHOLDER** (tela "ainda não implementada") | o **motor** existe sem tela: PIN offline, autorização do caixa, fila SQLite, sincronização |
| Produção | **PLACEHOLDER** | |
| Unidades | **PLACEHOLDER** | |
| Cadastros | **PLACEHOLDER** | |
| Fiscal | **PLACEHOLDER** | |
| Integrações | **PLACEHOLDER** | |
| Marketing | **PLACEHOLDER** | |
| Tema da Loja | **PLACEHOLDER** | |
| Consumo | **PLACEHOLDER** | |

Os placeholders não exibem dados fictícios: apenas informam que a área ainda não foi portada.

## 2. Dependências do Ritmo (o app NÃO cria nada no Ritmo)

Evidência = onde o item é criado nos scripts do repositório. **"Verificado em Ritmo vivo" = NÃO para todos** (sem acesso). A coluna "Correção mínima" só se aplica se o item faltar no Ritmo real.

| Nome exato | Usado por | Evidência no repositório | Correção mínima se ausente |
|---|---|---|---|
| Server Script API `synapse_csrf_token` (GET, `message` = token) | login, ping de conectividade | `provisionar_cliente_synapse2.sh` L954-960; também em `instalar_nomen_completo.sh` | reexecutar o provisionamento (cria se não existe). O app tolera ausência: segue sem CSRF, mas escritas podem falhar |
| Server Script API `synapse_sincronizar_caixa` (GET `?pos_profile=`, resposta em `message`) | autorização offline do caixa | `provisionar_cliente_synapse2.sh` L975-1033 (script) e registro em L1065 | reexecutar o provisionamento |
| Server Script API `synapse_definir_pin` (POST `pin_hash`,`pin_salt`,`pin_kdf_iteracoes`) | definir PIN | L1034-1063 (script) e registro em L1066; recusa hash curto (<32) ou só dígitos | idem |
| Custom Field `Employee.synapse_pin_hash/_salt/_kdf_iteracoes/_definido_em` | PIN offline | L1335-1349; conferido no gate final L2610 | idem |
| Custom Field `synapse_op_id` (Data, **unique**) em `POS Opening Entry`, `POS Closing Entry`, `POS Invoice`, `Sales Order` | idempotência da fila | L1356-1389; gate L2611-2614 | idem |
| Custom Field `synapse_operador_real` em `POS Invoice`, `Sales Order` | rastreio do operador offline | L1374, L1388 | idem |
| Custom Field `synapse_identificacao_atendimento` em `POS Invoice`, `Sales Order` | mesa/comanda/senha | L1380, L1389 | idem |
| Custom Field `synapse_item_generico`, `synapse_preco_autorizado_por`, `synapse_preco_motivo` em `POS Invoice Item`, `Sales Order Item` | itens genéricos/preço autorizado | L1391-1404 | idem |
| Custom Field `POS Profile.synapse_conta_acrescimos` (Link→Account) | lançar acréscimos da venda | L1406-1413 | configurar a conta no POS Profile; sem ela o app **rejeita** vendas com acréscimo (igual ao painel) |
| Custom Field `Customer.synapse_pedido_op_id` | idempotência de cliente criado por pedido | L1415-1430 | reexecutar o provisionamento |
| DocType `Synapse Producao Item` (autoname = `synapse_op_id`; campos `pos_invoice,item_code,frase,preenchimentos_json,alerta,status_producao`) | itens de produção da venda | L1700-1730 | idem |
| Item `ITEM-GENERICO` | itens genéricos | criado em L1467-1476; conferido no gate final | idem |
| `Synapse Credencial Externa`, `Synapse Tema Loja`, `Synapse Composicao` | áreas ainda não portadas | ambos os scripts | — |

### Achado de divergência entre os dois instaladores
`instalar_nomen_completo.sh` (VPS da própria Nomen) **não** contém: `synapse_conta_acrescimos`, `synapse_item_generico`, `synapse_preco_autorizado_por`, `synapse_preco_motivo`, `synapse_identificacao_atendimento`, `ITEM-GENERICO` (contagem 0). Só `provisionar_cliente_synapse2.sh` os tem. Logo, num Ritmo instalado apenas pelo instalador da Nomen,
vendas com item genérico/preço autorizado/identificação de atendimento/acréscimo seriam **rejeitadas pelo Ritmo** na sincronização. Correção mínima: levar esses 5 Custom Fields + o Item ao `instalar_nomen_completo.sh` (ou rodar o patch de caixa). **Não alterei esse arquivo** — decisão sua.

### Endpoints padrão do Frappe (não são custom)
`POST /api/method/login`, `POST /api/method/logout`, `GET /api/method/frappe.auth.get_logged_user`, `GET /api/resource/User/{email}`,
`GET/POST/PUT /api/resource/<DocType>[/<name>]`, `POST /api/method/<método>`.

## 3. Webhooks do Harmonia usados (workflows no repositório; todos `POST`)
`atendimento/conversas`, `atendimento/mensagens`, `atendimento/enviar`, `lyra/alterar`, `envio/situacao` — o `path` do nó webhook de cada JSON confere com o slug usado no app.
Existem no repositório mas **ainda não são usados** pelo app: `atendimento/enviar-anexo`, `consumo/servicos`, `monitoramento/metricas`, `fiscal/*`, `pagamentos/pdv/*`, `atendimento/numero-whatsapp`.
Importação e ativação desses workflows no n8n real: **não verificada** (o `LEIA-ME_ENTREGA.md` do repositório os descreve como "aguardando teste real").

## 4. Divergências e riscos encontrados
1. **`criada.name` vs `data.name`:** o painel lê `criada.name` após `Ritmo.criar`; a API REST do Frappe responde `{"data":{"name":...}}`. O app lê `data.name`. Se o Ritmo real divergir, abertura/venda/fechamento falham.
2. **`restricao_configurada = false`:** quando o POS Profile não tem usuários em "POS Profile User", o script devolve `usuarios_autorizados` **vazio** — nenhum operador consegue entrar offline. A tela de login offline (pendente) precisa avisar isso.
3. **Hashes de PIN no aparelho:** `synapse_sincronizar_caixa` entrega hash+salt de todos os operadores autorizados ao terminal. O app guarda isso em SQLite não cifrado (como o IndexedDB do painel). Cifrar o banco é decisão de produto pendente.
4. **HTTP 5xx → "rejeitado":** igual ao painel; o reenvio é manual. Pode ser conservador demais para falhas transitórias do servidor.
5. **Fila e dados do PDV sobrevivem ao logout** (necessário ao login offline); a troca de loja é bloqueada com operações não confirmadas.

## 5. Testes
Escritos (JUnit + MockWebServer, JVM): `DominioEPerfisTest`, `RitmoClientTest`, `AtendimentoRepositoryTest`, `PedidosTest`, `PdvTest`. **Executados: nenhum.**
Não escritos ainda: testes de UI (Compose), testes instrumentados, TalkBack, fonte ampliada, tablet. Testes na infraestrutura Synapse real: **nenhum**, e devem ser relatados separadamente quando existirem.

## 6. Preparação do build
Presentes: `build-env/Dockerfile` + `build-env/build.sh` (qualquer máquina com Docker e internet) e `.github/workflows/android.yml`. Nenhum foi executado.
Opção sem credencial para mim: criar o repositório privado exclusivo do Android **você mesmo**, subir o projeto com o seu `git push`, deixar o Actions rodar (o build debug não usa secrets) e me enviar o log/relatório dos artefatos. Assim nenhum token passa pelo chat. O APK de release e a assinatura continuam sob controle da Nomen.
