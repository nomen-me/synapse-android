package me.nomen.synapse.ui.pedidos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nomen.synapse.SynapseApplication
import me.nomen.synapse.data.ClasseStatus
import me.nomen.synapse.data.DetalhePedido
import me.nomen.synapse.data.ItemNovoPedido
import me.nomen.synapse.data.Opcao
import me.nomen.synapse.data.Pedido
import me.nomen.synapse.data.PedidosRepository
import me.nomen.synapse.data.PedidosStore
import me.nomen.synapse.data.Produto
import me.nomen.synapse.data.SemConexaoException
import me.nomen.synapse.data.SituacaoEnvio
import me.nomen.synapse.data.TipoPedido
import me.nomen.synapse.data.mensagemAmigavel
import me.nomen.synapse.domain.hojeIso
import me.nomen.synapse.domain.parseDataIso
import kotlin.coroutines.cancellation.CancellationException

class NovoPedidoUi(
    val tipo: TipoPedido = TipoPedido.VENDA,
    val partes: List<Opcao>? = null, val produtos: List<Produto>? = null, val erroCarga: String? = null,
    val parte: Opcao? = null, val itens: List<ItemNovoPedido> = emptyList(),
    val produtoSel: Produto? = null, val qtd: String = "1", val preco: String = "",
    val dataPrevista: String = hojeIso(), val salvando: Boolean = false, val erro: String? = null
)

class PedidosUi(
    val tipo: TipoPedido = TipoPedido.VENDA, val busca: String = "", val filtro: ClasseStatus? = null,
    val selecionado: Pedido? = null, val detalhe: DetalhePedido? = null, val carregandoDetalhe: Boolean = false, val erroDetalhe: String? = null,
    val confirmandoCancelamento: Boolean = false, val enviandoCancelamento: Boolean = false,
    val envio: SituacaoEnvio? = null, val consultandoEnvio: Boolean = false, val erroEnvio: String? = null,
    val novo: NovoPedidoUi? = null,
    val aviso: String? = null, val avisoErro: Boolean = false
)

class PedidosViewModel(
    private val store: PedidosStore,
    private val repo: PedidosRepository,
    private val solicitante: () -> String,
    sessaoEncerrada: Flow<Unit>
) : ViewModel() {
    private val _ui = MutableStateFlow(PedidosUi())
    val ui: StateFlow<PedidosUi> = _ui.asStateFlow()
    val dados = store.estado
    private var jobDetalhe: Job? = null

    init {
        viewModelScope.launch { sessaoEncerrada.collect { jobDetalhe?.cancel(); _ui.value = PedidosUi() } }
    }

    fun garantir() { viewModelScope.launch { store.garantir() } }
    fun recarregar() { viewModelScope.launch { store.carregar() } }

    fun aplicarFiltroInicial(f: ClasseStatus?) { if (f != null) _ui.update { it.copy(filtro = f, tipo = TipoPedido.VENDA) } }
    fun mudarTipo(t: TipoPedido) = _ui.update { it.copy(tipo = t, filtro = null) }
    fun buscar(t: String) = _ui.update { it.copy(busca = t) }
    fun limparFiltro() = _ui.update { it.copy(filtro = null) }
    fun fecharAviso() = _ui.update { it.copy(aviso = null) }

    // ---- detalhe ----
    fun abrir(p: Pedido) {
        jobDetalhe?.cancel()
        _ui.update { it.copy(selecionado = p, detalhe = null, erroDetalhe = null, carregandoDetalhe = true, envio = null, erroEnvio = null, confirmandoCancelamento = false) }
        jobDetalhe = viewModelScope.launch {
            try {
                val d = repo.detalhe(p)
                _ui.update { if (it.selecionado?.id == p.id) it.copy(detalhe = d, carregandoDetalhe = false) else it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.update { if (it.selecionado?.id == p.id) it.copy(carregandoDetalhe = false, erroDetalhe = mensagemAmigavel(e, "carregar os itens")) else it }
            }
        }
    }

    fun fechar() { jobDetalhe?.cancel(); _ui.update { it.copy(selecionado = null, detalhe = null, confirmandoCancelamento = false) } }

    fun consultarEnvio() {
        val p = _ui.value.selecionado ?: return
        if (_ui.value.consultandoEnvio) return
        _ui.update { it.copy(consultandoEnvio = true, erroEnvio = null, envio = null) }
        viewModelScope.launch {
            try {
                val s = repo.situacaoEnvio(p.id)
                _ui.update { it.copy(consultandoEnvio = false, envio = s) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.update { it.copy(consultandoEnvio = false, erroEnvio = if (e is me.nomen.synapse.data.ApiException) e.message else "Não foi possível consultar agora — tente novamente em instantes") }
            }
        }
    }

    // ---- cancelamento (solicitação à Lyra; o app não cancela) ----
    fun pedirConfirmacaoCancelamento() = _ui.update { it.copy(confirmandoCancelamento = true) }
    fun desistirCancelamento() = _ui.update { it.copy(confirmandoCancelamento = false) }

    fun solicitarCancelamento() {
        val p = _ui.value.selecionado ?: return
        if (_ui.value.enviandoCancelamento) return
        _ui.update { it.copy(enviandoCancelamento = true) }
        viewModelScope.launch {
            try {
                val resumo = repo.solicitarCancelamento(p.id, solicitante())
                _ui.update {
                    it.copy(
                        enviandoCancelamento = false, confirmandoCancelamento = false, selecionado = null, detalhe = null,
                        aviso = "Solicitação de cancelamento enviada à Lyra" + (resumo?.let { r -> " — $r" } ?: " — acompanhe o status do pedido"), avisoErro = false
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: SemConexaoException) {
                // Resultado desconhecido: não reenvia sozinho.
                _ui.update { it.copy(enviandoCancelamento = false, confirmandoCancelamento = false, aviso = "Não foi possível confirmar se a solicitação foi enviada. Atualize a lista e confira o status antes de solicitar de novo.", avisoErro = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(enviandoCancelamento = false, confirmandoCancelamento = false, aviso = mensagemAmigavel(e, "enviar a solicitação à Lyra"), avisoErro = true) }
            }
        }
    }

    // ---- novo pedido ----
    fun abrirNovo() {
        _ui.update { it.copy(novo = NovoPedidoUi()) }
        carregarOpcoesNovo()
    }

    fun fecharNovo() = _ui.update { it.copy(novo = null) }

    private fun carregarOpcoesNovo() {
        viewModelScope.launch {
            try {
                val clientes = repo.clientes(); val forn = repo.fornecedores(); val prods = repo.produtos()
                _ui.update { u ->
                    val n = u.novo ?: return@update u
                    u.copy(novo = NovoPedidoUi(
                        tipo = n.tipo, partes = if (n.tipo == TipoPedido.VENDA) clientes else forn, produtos = prods,
                        parte = n.parte, itens = n.itens, produtoSel = n.produtoSel, qtd = n.qtd, preco = n.preco, dataPrevista = n.dataPrevista
                    ).also { cache = Cache(clientes, forn) })
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.update { u -> u.novo?.let { n -> u.copy(novo = copiar(n, erroCarga = mensagemAmigavel(e, "carregar clientes, fornecedores e produtos"))) } ?: u }
            }
        }
    }

    private class Cache(val clientes: List<Opcao>, val fornecedores: List<Opcao>)
    private var cache: Cache? = null

    private fun copiar(n: NovoPedidoUi, tipo: TipoPedido = n.tipo, partes: List<Opcao>? = n.partes, parte: Opcao? = n.parte,
                       itens: List<ItemNovoPedido> = n.itens, produtoSel: Produto? = n.produtoSel, qtd: String = n.qtd, preco: String = n.preco,
                       dataPrevista: String = n.dataPrevista, salvando: Boolean = n.salvando, erro: String? = n.erro, erroCarga: String? = n.erroCarga) =
        NovoPedidoUi(tipo, partes, n.produtos, erroCarga, parte, itens, produtoSel, qtd, preco, dataPrevista, salvando, erro)

    private fun editarNovo(f: (NovoPedidoUi) -> NovoPedidoUi) = _ui.update { u -> u.novo?.let { u.copy(novo = f(it)) } ?: u }

    fun novoMudarTipo(t: TipoPedido) = editarNovo { n ->
        copiar(n, tipo = t, partes = cache?.let { if (t == TipoPedido.VENDA) it.clientes else it.fornecedores } ?: n.partes, parte = null, itens = emptyList(), erro = null)
    }
    fun novoEscolherParte(o: Opcao) = editarNovo { copiar(it, parte = o, erro = null) }
    fun novoEscolherProduto(p: Produto) = editarNovo { copiar(it, produtoSel = p, preco = if (p.precoPadrao > 0) p.precoPadrao.toString() else "") }
    fun novoQtd(v: String) = editarNovo { copiar(it, qtd = v) }
    fun novoPreco(v: String) = editarNovo { copiar(it, preco = v) }
    fun novoData(v: String) = editarNovo { copiar(it, dataPrevista = v) }
    fun novoRemoverItem(i: Int) = editarNovo { copiar(it, itens = it.itens.filterIndexed { idx, _ -> idx != i }) }

    fun novoAdicionarItem() = editarNovo { n ->
        val p = n.produtoSel ?: return@editarNovo copiar(n, erro = "Selecione um produto")
        val q = n.qtd.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (q <= 0) return@editarNovo copiar(n, erro = "Informe uma quantidade válida")
        val r = if (n.preco.isNotBlank()) n.preco.replace(',', '.').toDoubleOrNull() else p.precoPadrao
        if (r == null || r < 0) return@editarNovo copiar(n, erro = "Informe um preço válido")
        copiar(n, itens = n.itens + ItemNovoPedido(p.codigo, p.nome, q, r), produtoSel = null, qtd = "1", preco = "", erro = null)
    }

    fun novoSalvar() {
        val n = _ui.value.novo ?: return
        if (n.salvando) return
        val parte = n.parte ?: return editarNovo { copiar(it, erro = if (it.tipo == TipoPedido.VENDA) "Selecione o cliente" else "Selecione o fornecedor") }
        if (n.itens.isEmpty()) return editarNovo { copiar(it, erro = "Adicione ao menos um item") }
        val data = if (n.tipo == TipoPedido.COMPRA) parseDataIso(n.dataPrevista)?.toString() else null
        if (n.tipo == TipoPedido.COMPRA && data == null) return editarNovo { copiar(it, erro = "Data prevista inválida (use AAAA-MM-DD)") }
        editarNovo { copiar(it, salvando = true, erro = null) }
        viewModelScope.launch {
            try {
                val nome = if (n.tipo == TipoPedido.VENDA) repo.criarVenda(parte.id, n.itens) else repo.criarCompra(parte.id, n.itens, data!!)
                _ui.update { it.copy(novo = null, aviso = "Pedido $nome criado no Ritmo", avisoErro = false) }
                store.carregar()
            } catch (e: CancellationException) { throw e } catch (e: SemConexaoException) {
                editarNovo { copiar(it, salvando = false, erro = "Não foi possível confirmar se o pedido foi criado. Confira a lista de pedidos antes de tentar de novo.") }
                store.carregar()
            } catch (e: Exception) {
                editarNovo { copiar(it, salvando = false, erro = mensagemAmigavel(e, "criar o pedido")) }
            }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SynapseApplication).container
                return PedidosViewModel(c.pedidosStore, c.pedidosRepo, { c.usuarioAtualNome() }, c.sessaoEncerrada) as T
            }
        }
    }
}
