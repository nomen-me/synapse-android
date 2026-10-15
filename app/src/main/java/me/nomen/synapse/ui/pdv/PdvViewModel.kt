package me.nomen.synapse.ui.pdv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.AppContainer
import me.nomen.synapse.SynapseApplication
import me.nomen.synapse.data.mensagemAmigavel
import me.nomen.synapse.domain.Usuario
import me.nomen.synapse.pdv.Ajuste
import me.nomen.synapse.pdv.Carrinho
import me.nomen.synapse.pdv.CaixaPdv
import me.nomen.synapse.pdv.ContextoVenda
import me.nomen.synapse.pdv.OperacaoFila
import me.nomen.synapse.pdv.OperadorPdv
import me.nomen.synapse.pdv.PdvCatalogoRepository
import me.nomen.synapse.pdv.PinCrypto
import me.nomen.synapse.pdv.ProdutoPdv
import me.nomen.synapse.pdv.ResultadoSync
import me.nomen.synapse.pdv.StatusOp
import kotlin.coroutines.cancellation.CancellationException

class Checkout(val opId: String, val metodo: String = "dinheiro", val recebido: String = "", val enviando: Boolean = false, val erro: String? = null)

class PdvUi(
    val carregando: Boolean = true, val caixa: CaixaPdv? = null, val produtos: List<ProdutoPdv> = emptyList(), val busca: String = "",
    val carrinho: Carrinho = Carrinho(), val online: Boolean? = null, val sincronizando: Boolean = false, val ultimoSync: ResultadoSync? = null,
    val fila: List<OperacaoFila> = emptyList(), val aviso: String? = null, val avisoErro: Boolean = false,
    val checkout: Checkout? = null, val concluida: OperacaoFila? = null, val filaAberta: Boolean = false, val pinAberto: Boolean = false,
    val divergencias: Int = 0
) {
    val pendentes: Int get() = fila.count { it.status == StatusOp.PENDENTE || it.status == StatusOp.ENVIANDO }
    val rejeitadas: Int get() = fila.count { it.status == StatusOp.REJEITADO }
    val visiveis: List<ProdutoPdv> get() = PdvCatalogoRepository.filtrar(produtos, busca)
}

class PdvViewModel(private val c: AppContainer, sessaoEncerrada: Flow<Unit>) : ViewModel() {
    private val _ui = MutableStateFlow(PdvUi())
    val ui: StateFlow<PdvUi> = _ui.asStateFlow()
    private var ciclo: Job? = null
    private var usuario: Usuario? = null
    private var autorizacaoSincronizada = false

    init { viewModelScope.launch { sessaoEncerrada.collect { /* a fila do PDV sobrevive ao logout; só o estado de tela zera */ ciclo?.cancel(); ciclo = null; autorizacaoSincronizada = false; _ui.value = PdvUi() } } }

    private fun operador(): OperadorPdv {
        val u = usuario ?: return OperadorPdv.DESCONHECIDO
        return if (u.offline) OperadorPdv(u.email, u.nome, u.employee, "offline") else OperadorPdv(u.email, u.nome, null, "online")
    }

    /** Idempotente: pode ser chamada em toda recomposição inicial; só inicia um ciclo. */
    fun iniciar(u: Usuario) {
        usuario = u
        if (ciclo != null) return
        viewModelScope.launch {
            val caixa = c.pdvCaixa.caixaAberto()
            _ui.update { it.copy(carregando = false, caixa = caixa, produtos = c.pdvCatalogo.local(), fila = c.pdvStore.todasOps()) }
        }
        ciclo = viewModelScope.launch {
            var primeira = true
            while (true) {
                cicloUmaVez(primeira); primeira = false
                delay(30_000)   // mesmo retry leve de 30 s do painel
            }
        }
    }

    private suspend fun recarregarFila() { _ui.update { it.copy(fila = c.pdvStore.todasOps()) } }

    private suspend fun cicloUmaVez(primeira: Boolean) {
        val online = c.ritmo.ritmoAlcancavel()
        _ui.update { it.copy(online = online) }
        if (!online) return
        val offlineUser = usuario?.offline == true
        if (!offlineUser && primeira) {
            try {
                val r = c.pdvCatalogo.sincronizar(true)
                _ui.update { it.copy(produtos = r.produtos, divergencias = r.divergencias.size, aviso = r.avisos.firstOrNull() ?: it.aviso, avisoErro = r.avisos.isNotEmpty()) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        if (!offlineUser && !autorizacaoSincronizada) {
            try { c.pdvConfig.sincronizarAutorizacao(); autorizacaoSincronizada = true } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        if (_ui.value.pendentes > 0 || primeira) sincronizar()
    }

    fun sincronizarAgora() { viewModelScope.launch { sincronizar() } }

    private suspend fun sincronizar() {
        if (_ui.value.sincronizando) return
        _ui.update { it.copy(sincronizando = true) }
        try {
            val r = c.pdvSync.sincronizarFila(online = _ui.value.online == true)
            _ui.update { it.copy(ultimoSync = r) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _ui.update { it.copy(aviso = mensagemAmigavel(e, "sincronizar a fila"), avisoErro = true) }
        } finally {
            _ui.update { it.copy(sincronizando = false) }
            recarregarFila()
        }
    }

    fun reenviar(opId: String) { viewModelScope.launch { c.pdvSync.reenfileirarRejeitada(opId); recarregarFila(); sincronizar() } }

    fun alternarFila() = _ui.update { it.copy(filaAberta = !it.filaAberta) }
    fun fecharAviso() = _ui.update { it.copy(aviso = null) }
    fun buscar(t: String) = _ui.update { it.copy(busca = t) }

    // ---- caixa ----
    fun abrirCaixa(trocoTexto: String) {
        val troco = trocoTexto.replace(',', '.').toDoubleOrNull() ?: if (trocoTexto.isBlank()) 0.0 else null
        if (troco == null || troco < 0) { _ui.update { it.copy(aviso = "Troco inicial inválido", avisoErro = true) }; return }
        viewModelScope.launch {
            try {
                val cx = c.pdvCaixa.abrir(troco, operador())
                _ui.update { it.copy(caixa = cx, aviso = if (it.online == true) "Caixa aberto" else "Caixa aberto — offline, sincroniza quando a internet voltar", avisoErro = false) }
                recarregarFila(); sincronizarAgora()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _ui.update { it.copy(aviso = e.message, avisoErro = true) } }
        }
    }

    fun fecharCaixa() {
        viewModelScope.launch {
            try {
                c.pdvCaixa.fechar(operador())
                _ui.update { it.copy(caixa = null, carrinho = Carrinho(), aviso = "Caixa fechado", avisoErro = false) }
                recarregarFila(); sincronizarAgora()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _ui.update { it.copy(aviso = e.message, avisoErro = true) } }
        }
    }

    // ---- carrinho ----
    fun adicionar(p: ProdutoPdv) = _ui.update { u -> val r = u.carrinho.adicionar(p); u.copy(carrinho = r.carrinho, aviso = r.erro ?: u.aviso, avisoErro = r.erro != null || u.avisoErro) }

    fun alterarQtd(chave: String, delta: Double) = _ui.update { u ->
        val estoque = u.produtos.firstOrNull { it.itemCode == u.carrinho.itens.firstOrNull { i -> i.chave == chave }?.itemCode }?.estoque
        val r = u.carrinho.alterarQtd(chave, delta, estoque)
        u.copy(carrinho = r.carrinho, aviso = r.erro ?: u.aviso, avisoErro = r.erro != null || u.avisoErro)
    }

    fun remover(chave: String) = _ui.update { it.copy(carrinho = it.carrinho.remover(chave)) }

    fun descontoGeral(tipo: String?, valorTexto: String) = _ui.update { u ->
        val v = valorTexto.replace(',', '.').toDoubleOrNull()
        u.copy(carrinho = u.carrinho.comDescontoGeral(if (tipo == null || v == null || v <= 0) null else Ajuste(tipo, v)))
    }

    // ---- checkout ----
    fun abrirCheckout() { if (!_ui.value.carrinho.vazio) _ui.update { it.copy(checkout = Checkout(c.pdvCaixa.gerarId("venda"))) } }
    fun fecharCheckout() = _ui.update { it.copy(checkout = null) }
    fun checkoutMetodo(m: String) = _ui.update { u -> u.copy(checkout = u.checkout?.let { Checkout(it.opId, m, it.recebido, it.enviando, null) }) }
    fun checkoutRecebido(v: String) = _ui.update { u -> u.copy(checkout = u.checkout?.let { Checkout(it.opId, it.metodo, v, it.enviando, null) }) }
    fun fecharConclusao() = _ui.update { it.copy(concluida = null) }

    /** O opId do checkout é gerado ao abrir: um segundo toque em "Confirmar" devolve a mesma operação (sem duplicar). */
    fun confirmarVenda() {
        val u = _ui.value; val ck = u.checkout ?: return
        if (ck.enviando) return
        val total = u.carrinho.total()
        val recebido = ck.recebido.replace(',', '.').toDoubleOrNull()
        if (ck.metodo == "dinheiro" && (recebido == null || recebido + 1e-9 < total)) {
            _ui.update { it.copy(checkout = Checkout(ck.opId, ck.metodo, ck.recebido, false, "Valor recebido menor que o total")) }; return
        }
        _ui.update { it.copy(checkout = Checkout(ck.opId, ck.metodo, ck.recebido, true, null)) }
        val pagamento: JsonObject = buildJsonObject {
            put("metodo", ck.metodo); put("valor", total)
            if (ck.metodo == "dinheiro" && recebido != null) { put("recebido", recebido); put("troco", recebido - total) }
        }
        viewModelScope.launch {
            try {
                val op = c.pdvCaixa.finalizarVenda(u.carrinho, ContextoVenda(), pagamento, operador(), ck.opId)
                val produtos = c.pdvCatalogo.local()
                _ui.update { it.copy(checkout = null, concluida = op, carrinho = Carrinho(), produtos = produtos) }
                recarregarFila(); sincronizarAgora()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.update { it.copy(checkout = Checkout(ck.opId, ck.metodo, ck.recebido, false, e.message ?: "Não foi possível registrar a venda")) }
            }
        }
    }

    // ---- PIN / autorização ----
    fun abrirPin() = _ui.update { it.copy(pinAberto = true) }
    fun fecharPin() = _ui.update { it.copy(pinAberto = false) }

    fun definirPin(pin: String, confirmacao: String) {
        val email = usuario?.takeUnless { it.offline }?.email ?: run { _ui.update { it.copy(aviso = "Defina o PIN entrando online.", avisoErro = true) }; return }
        if (!PinCrypto.pinValido(pin)) { _ui.update { it.copy(aviso = "O PIN precisa ter exatamente 6 dígitos numéricos", avisoErro = true) }; return }
        if (pin != confirmacao) { _ui.update { it.copy(aviso = "A confirmação do PIN não confere", avisoErro = true) }; return }
        viewModelScope.launch {
            try {
                c.pdvConfig.definirPinOnline(pin, email)
                _ui.update { it.copy(pinAberto = false, aviso = "PIN definido — já vale para entrar offline neste e em outros terminais depois da sincronização", avisoErro = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _ui.update { it.copy(aviso = mensagemAmigavel(e, "definir o PIN"), avisoErro = true) } }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SynapseApplication).container
                return PdvViewModel(c, c.sessaoEncerrada) as T
            }
        }
    }
}
