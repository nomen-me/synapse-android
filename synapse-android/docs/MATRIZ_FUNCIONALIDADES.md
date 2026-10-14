# Matriz: funcionalidade do painel (synapse_painel_18-09t.html) × app Android

Legenda de status: **IMPLEMENTADO (não testado em aparelho)** · **PENDENTE** (existe no painel, ainda não portado).
Nada abaixo está marcado como "testado/aprovado": nenhuma integração foi executada contra a infraestrutura real.
Endpoints extraídos de `SYNAPSE_CONFIG`, `Ritmo`, `Harmonia` e das funções de login/atendimento do painel.
"Uso detalhado a confirmar" = o endpoint é declarado na configuração do painel, mas o fluxo de tela ainda não foi lido linha a linha.

## Núcleo
| Funcionalidade do painel | Endpoint / contrato | Autenticação | App | Status |
|---|---|---|---|---|
| Login | `POST ritmo/api/method/login` (form `usr`,`pwd`; sucesso = `message:"Logged In"`) | cookie `sid` | `RitmoClient.login`, `LoginScreen` | IMPLEMENTADO |
| Perfil/Role Synapse | `GET ritmo/api/resource/User/{email}?fields=["roles","full_name"]` | sessão | `AuthRepository`, `Perfis.identificar` | IMPLEMENTADO |
| CSRF | `GET ritmo/api/method/synapse_csrf_token`; header `X-Frappe-CSRF-Token` só em escrita | sessão | `RitmoClient.buscarCsrfToken` | IMPLEMENTADO |
| Verificar sessão ao abrir | `GET ritmo/api/method/frappe.auth.get_logged_user` (`Guest` = sem sessão) | cookie | `AuthRepository.verificarSessao` | IMPLEMENTADO |
| Logout | `POST ritmo/api/method/logout` (sai localmente mesmo se falhar) | sessão | `AuthRepository.logout` | IMPLEMENTADO |
| Permissões por aba | matriz `PERMISSOES_TABS` (Produção/Unidades fora da matriz, visíveis a todos) | — | `Perfis` | IMPLEMENTADO (testes JVM escritos) |
| Sub-aba Filiais só Lojista | `CAD_SUBABAS_RESTRITAS` | — | `Perfis.subAbaCadastroPermitida` | IMPLEMENTADO (regra); tela de Cadastros PENDENTE |
| Login offline por PIN (Caixa) | PBKDF2/SHA-256 local, `synapse_sincronizar_caixa`, `synapse_definir_pin` | — | — | PENDENTE |

## Áreas
| Aba | Endpoints/DocTypes conhecidos | Status no app |
|---|---|---|
| Atendimento — listar conversas | `POST harmonia/webhook/atendimento/conversas` `{}` | IMPLEMENTADO |
| Atendimento — mensagens/contexto | `POST .../atendimento/mensagens` `{conversa_id}` | IMPLEMENTADO |
| Atendimento — enviar texto | `POST .../atendimento/enviar` `{conversa_id,mensagem}` (sem retry) | IMPLEMENTADO |
| Atendimento — enviar anexo | `POST .../atendimento/enviar-anexo` multipart (`attachments[]`+`conversa_id`) | PENDENTE (cliente `chamarComArquivo` pronto; tela e seletor de arquivo não) |
| Dashboard — faturamento, fluxo, conversas aguardando, pedidos em espera, gráfico 7 dias | dados de `Sales Order` (Ritmo) + `atendimento/conversas` (Harmonia) | IMPLEMENTADO (não testado em aparelho). Alertas de fila do PDV e de transferência entre unidades entram quando PDV/Unidades forem portados |
| Pedidos — lista venda/compra, busca, filtro de status, detalhe (itens sob demanda) | `GET ritmo/api/resource/Sales Order` e `Purchase Order`; `GET .../{doc}` | IMPLEMENTADO |
| Pedidos — cancelar | `POST harmonia/webhook/lyra/alterar {tipo:"pedidos",id,instrucao}` (o app não cancela; só Lojista/Administrador; sem retry) | IMPLEMENTADO |
| Pedidos — situação do envio | `POST harmonia/webhook/envio/situacao {pedidoId}` | IMPLEMENTADO (workflow depende do W2 no Harmonia) |
| Pedidos — criar venda/compra | `POST ritmo/api/resource/Sales Order` / `Purchase Order` (company via POS Profile ou Company única) | IMPLEMENTADO (campos de Compra seguem a premissa do painel, não confirmada em Ritmo real) |
| PDV — PIN offline (PBKDF2-SHA256, compatível com o painel), bloqueio 5 erros/60 s | `PinCrypto`, `PinLockout`; `POST ritmo/api/method/synapse_definir_pin` | MOTOR IMPLEMENTADO (tela pendente) |
| PDV — autorização offline do caixa | `GET ritmo/api/method/synapse_sincronizar_caixa?pos_profile=` → `message.usuarios_autorizados` | MOTOR IMPLEMENTADO (tela pendente) |
| PDV — fila offline (SQLite) e sincronização idempotente (`synapse_op_id`) | `POS Opening Entry`, `POS Invoice`, `POS Closing Entry`, `Sales Order`/`Customer` (pedido_venda), `Synapse Producao Item` (+ `producao_status`) | MOTOR IMPLEMENTADO e coberto por testes JVM escritos (não executados); tela pendente |
| PDV — catálogo, carrinho, descontos/acréscimos, abertura/fechamento de caixa, finalizar venda (telas) | cálculo de ajustes já portado (`valorItemComAjuste`) | PENDENTE |
| PDV — NFC-e, cobrança Pix (InfinitePay), número WhatsApp | `fiscal/emitir-nfce`, `pagamentos/pdv/criar-cobranca`, `pagamentos/pdv/status`, `atendimento/numero-whatsapp` (gancho `aposVendaConfirmada` existe, sem implementação) | PENDENTE |
| PDV — exportar/importar pacote de pendências (ZIP, SHA-256 por operação) | formato do painel (manifesto.json, operacoes.json, autorizados.json) | PENDENTE |
| PDV — login offline por PIN (tela) e definir PIN (tela) | — | PENDENTE (motor pronto) |
| Produção | `Synapse Producao Item`, `Synapse Composicao` | PENDENTE |
| Unidades | `Warehouse`, `Bin`, `Stock Entry`, `Item Reorder` | PENDENTE |
| Cadastros | `Customer`, `Supplier`, `Item`, `Employee`, `Bank Account`; `lyra/consultar|alterar|gerar-atributos|inativar`; `endereco/autocomplete` | PENDENTE |
| Fiscal | `Company`, `upload_file`, `fiscal/consultar-cnpj`, `fiscal/certificado-senha` | PENDENTE |
| Integrações | `integracoes/conectar`, `atendimento/conectar-canal`, `Synapse Credencial Externa` | PENDENTE |
| Marketing | `marketing/conectar` (`marketing/health-check` está declarado e não é usado no painel) | PENDENTE |
| Tema da Loja | `Synapse Tema Loja`, `upload_file` | PENDENTE |
| Consumo | `monitoramento/metricas` (CPU/RAM/disco) e `consumo/servicos`. **O workflow `06_consumo_servicos.json` do repositório responde SEMPRE 501 `PENDENTE_FONTE_DE_DADOS`** (não há caminho de sucesso nele): a parte de serviços aparece como "em configuração", como no painel | Camada de dados IMPLEMENTADA e testada na JVM (`ConsumoTest`); tela escrita, NÃO compilada |

## Dependências de backend já sinalizadas no repositório (não resolvidas pelo app)
Conforme `LEIA-ME_ENTREGA.md` do repositório, todos os workflows Harmonia estão "estruturalmente prontos, aguardando teste real";
`consumo/servicos` devolve 501; `NFC-e`, cobrança PDV e anexo dependem de seus workflows ativos. O app não declara esses recursos como funcionais.
