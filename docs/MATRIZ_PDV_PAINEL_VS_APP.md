# PDV: painel (`synapse_painel_18-09t.html`) × app Android

Colunas — **Código**: escrito. **Compilado**: type-check real do kotlinc (K2) *(K)*; contra stubs de AndroidX apenas *(S)*; não compilado *(—)*.
**Simulação**: coberto por teste automatizado contra servidor HTTP falso. **Real**: validado na Synapse real (hoje: nenhum item).
Funções do painel listadas por grupo (nomes reais extraídos do HTML).

| Grupo de funções do painel | Código | Compilado | Simulação | Real |
|---|---|---|---|---|
| Catálogo: `pdvCarregarCatalogo`, `buscarProdutoPDV`, `pdvFiltrarPorCategoriaPDV` (categoria: filtro não portado) | sim (busca por nome/código; sem filtro de categoria) | K | sim (`PdvFluxoCompletoTest`) | não |
| Depósito/POS Profile: `pdvResolverDeposito` | sim | K | sim | não |
| Estoque local: `pdvDecrementarEstoqueLocal` | sim | K | sim | não |
| Carrinho: `adicionarAoCarrinhoPDV`, `alterarQtdCarrinhoPDV`, `removerDoCarrinhoPDV`, `chaveCarrinhoPDV`, `totalCarrinhoPDV`, `valorItemComAjustePDV` | sim | K | sim (`CarrinhoTest`) | não |
| Carrinho por código (`adicionarAoCarrinhoPDVPorCodigo`), qtd direta (`editarQtd…`, `confirmarQtdDireta…`), `cancelarUltimoItemPDV` | **não** | — | não | não |
| Desconto geral (`abrirDescontoGeralPDV`, `confirmar…`, `remover…`) | sim | lógica K; tela — | lógica sim | não |
| Ajuste por item (`abrirAjusteItemPDV`, `confirmarAjusteItemPDV`, `removerAjusteItemPDV`) | lógica sim; **tela não** | K | lógica sim | não |
| Acréscimos de venda (`abrirAcrescimosVendaPDV`, `adicionarAcrescimoVendaPDV`, `remover…`, `pdvRenderAjustesVendaPDV`) | lógica sim; **tela não** | K | lógica sim (conta ausente → rejeita) | não |
| Preço livre / supervisor (`abrirPrecoLivrePDV`, `pdvSolicitarAutorizacaoSupervisor`, `pdvConfirmar…`, `pdvAcaoPermitida`) | gate de perfil e payload sim; **fluxo de supervisor não** | K | parcial (`Perfis.acaoPermitida`) | não |
| Item genérico (`abrirItemGenericoPDV`, `confirmarItemGenericoPDV`) | mapeamento do payload sim; **tela não** | K | não | não |
| Pesáveis (`pdvEhPesavel`, `pdvSolicitarPesoPDV`, `pdvConfirmarPesoPDV`, `pdvFormatarPeso`) | detecção sim; **entrada de peso não** | K | sim (detecção) | não |
| Cliente, vendedor, identificação (`abrirBuscaClientePDV`, `buscarClientePDV`, `confirmarClientePDV`, `…Vendedor…`, `…Identificacao…`, `pdvCarregarClientesCache`, `pdvCarregarVendedoresCache`) | payload sim; **telas e caches não** | K | payload sim | não |
| Abrir/fechar caixa: `abrirCaixaPDV`, `fecharCaixaPDV`, `pdvCarregarCaixaAberto` | sim (lógica + tela) | lógica K; tela — | lógica sim | não |
| Finalizar venda: `finalizarVendaPDV`, `abrirPagamentoPDV`, `registrarPagamentoManualPDV`, `abrirConclusaoPDV`, `pdvFecharConclusao`, `pdvLimparEstadoVendaPDV` | sim (dinheiro com troco / cartão / Pix **manual**) | lógica K; tela — | lógica sim | não |
| Pix integrado: `pagarComIntegracaoPDV`, `pdvAguardarConfirmacaoPagamento`, `pdvReconciliarConfirmacao`, `pdvRenderQR` | **não** | — | não | não |
| NFC-e: `acionarNFCePDV`, `entregarNFCePorWhatsAppPDV`, `pdvPersistirNfceDaVenda`, `pdvRotuloNFCe`, `pdvAtualizarPillNFCe`, `imprimirRecibo` | só o gancho `aposVendaConfirmada` (vazio) | K | não | não |
| Vendas suspensas: `suspenderVendaPDV`, `abrirVendasSuspensasPDV`, `recuperarVendaSuspensaPDV`, `excluirVendaSuspensaPDV`, `pdvAtualizarBadgeSuspensas` | **não** | — | não | não |
| Cancelar venda: `confirmarCancelarVendaPDV` | **não** (gate `cancelar_venda` existe) | — | parcial | não |
| Fila: `pdvEnfileirarOperacao`, `sincronizarFilaPDV`, `pdvEnviarOperacao`, `pdvMapearItemParaRitmo`, `pdvMapearAjustesDocParaRitmo`, `pdvPersistirCamposDaOperacao`, `pdvReenviarOperacaoRejeitada`, `pdvGerarId`, `pdvIdentidadeOperador` | sim | K | **sim** (idempotência, rede, sessão, rejeição, duplicado, ordem, queda do app) | não |
| Fila — tela: `pdvAbrirPainelFila`, `pdvRenderPainelFila`, `pdvAtualizarBadgeFila`, `irParaFilaPdvAlerta` | sim | — | não | não |
| Ciclo de sincronização e conectividade: `pdvCicloDeSincronizacao`, `pdvIniciarSincronizacaoPeriodica`, `pdvConectividadeReal`, `pdvAtualizarBannerOffline` | sim (30 s; ping real do Ritmo) | ciclo: S; ping: K | ping sim; ciclo não | não |
| PIN: `pdvDerivarHashPin`, `pdvGerarSaltPin`, `pdvCalibrarIteracoesKdf`, `pdvBytes/Base64`, `pdvHashSha256` | sim | K | **sim** (vetores PBKDF2) | não |
| Definir PIN: `pdvAbrirModalDefinirPin`, `pdvSalvarNovoPin`, `pdvDefinirPinOnline` | sim | lógica K; tela — | sim (`PdvAutorizacaoTest`) | não |
| Autorização do caixa: `pdvSincronizarAutorizacaoCaixa`, `pdvAutorizacaoCaixaLocal` | sim | K | sim | não |
| Login offline: `tentarLoginOffline` | sim (lógica + tela) | lógica K; tela — | **sim** (bloqueio 5×/60 s etc.) | não |
| Trocar operador: `trocarOperadorPDV` | parcial (sair/entrar) | — | não | não |
| Exportar/importar pacote de pendências: `pdvExportarPendencias`, `pdvImportarPacotePendencias`, `pdvAcionarImportarPacote`, `pdvHashOperacao`, `pdvTerminalId`, `pdvAguardarConfirmacao…` | **não** | — | não | não |
| Hortifrúti (`pdvHf*`: PLU, teclado, páginas) e `pdvMudarModoOperacao` / `pdvNucleoOperacao` | **não** | — | não | não |
| Consulta rápida / QR de preço: `abrirConsultaRapidaPDV`, `pdvConsultarRapido*`, `mostrarQRConsultaPDV` | **não** | — | não | não |
| Produção (kanban): `carregarProducao`, `renderProducao`, `avancarStatusProducao`, `…CancelamentoProducao`, `atualizarBadgeProducao`, `detectarAlertaProducao` | **não** (apenas a criação do item na sincronização da venda) | K (criação) | sim (criação) | não |
| Motor de Composição (`pdvCarregarComposicoes`, preenchimentos): | payload aceita; **UI/validação não** | K | parcial | não |
| Não se aplicam ao Android: `pdvRegistrarServiceWorker`, `pdvAplicarAtualizacaoServiceWorker`, `pdvMostrarBannerAtualizacao` (PWA), `pdvRegistrarAtalhosTeclado` | n/a | n/a | n/a | n/a |

**Resumo honesto:** o motor offline (fila, idempotência, PIN, autorização, catálogo, caixa/venda) está implementado, compilado (K) e testado em simulação; as telas estão escritas e **não compiladas**; nada foi validado na Synapse real; faltam os grupos marcados "não".
