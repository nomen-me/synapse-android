package me.nomen.synapse.ui.atendimento

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nomen.synapse.AppContainer
import me.nomen.synapse.SynapseApplication
import me.nomen.synapse.data.ApiException
import me.nomen.synapse.data.AtendimentoRepository
import me.nomen.synapse.data.Conversa
import me.nomen.synapse.data.DetalheConversa
import me.nomen.synapse.data.SemConexaoException
import kotlin.coroutines.cancellation.CancellationException

data class AtendimentoUi(
    val carregandoLista: Boolean = false,
    val conversas: List<Conversa>? = null,          // null = ainda não carregado
    val erroLista: String? = null,
    val somenteAguardando: Boolean = false,
    val selecionadaId: Long? = null,
    val carregandoDetalhe: Boolean = false,
    val detalhe: DetalheConversa? = null,
    val erroDetalhe: String? = null,
    val rascunho: String = "",
    val enviando: Boolean = false,
    val erroEnvio: String? = null
) {
    val selecionada: Conversa? get() = conversas?.firstOrNull { it.id == selecionadaId }
    val visiveis: List<Conversa>
        get() = conversas.orEmpty().let { l -> if (somenteAguardando) l.filter { it.aguardandoResposta } else l }
}

class AtendimentoViewModel(
    private val repo: AtendimentoRepository,
    sessaoEncerrada: kotlinx.coroutines.flow.Flow<Unit>
) : ViewModel() {
    private val _ui = MutableStateFlow(AtendimentoUi())
    val ui: StateFlow<AtendimentoUi> = _ui.asStateFlow()

    private var jobLista: Job? = null
    private var jobDetalhe: Job? = null

    init {
        // Nunca reaproveitar dados de um usuário anterior.
        viewModelScope.launch {
            sessaoEncerrada.collect {
                jobLista?.cancel(); jobDetalhe?.cancel()
                _ui.value = AtendimentoUi()
            }
        }
    }

    /** Carrega a lista só se ainda não foi carregada (seguro contra recomposição/rotação). */
    fun garantirLista() {
        val s = _ui.value
        if (s.conversas == null && !s.carregandoLista) atualizarLista()
    }

    fun atualizarLista() {
        if (_ui.value.carregandoLista) return
        _ui.update { it.copy(carregandoLista = true, erroLista = null) }
        jobLista = viewModelScope.launch {
            try {
                val lista = repo.listarConversas()
                _ui.update { it.copy(carregandoLista = false, conversas = lista) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nunca finge "nenhuma conversa" em caso de erro: mantém a lista anterior e mostra o erro.
                _ui.update { it.copy(carregandoLista = false, erroLista = mensagem(e, "carregar as conversas")) }
            }
        }
    }

    fun alternarFiltroAguardando() = _ui.update { it.copy(somenteAguardando = !it.somenteAguardando) }

    fun abrir(id: Long) {
        jobDetalhe?.cancel()
        _ui.update { it.copy(selecionadaId = id, detalhe = null, erroDetalhe = null, rascunho = "", erroEnvio = null) }
        carregarDetalhe(id)
    }

    fun voltar() {
        jobDetalhe?.cancel()
        _ui.update { it.copy(selecionadaId = null, detalhe = null, erroDetalhe = null, erroEnvio = null) }
    }

    fun recarregarDetalhe() { _ui.value.selecionadaId?.let { jobDetalhe?.cancel(); carregarDetalhe(it) } }

    private fun carregarDetalhe(id: Long) {
        _ui.update { it.copy(carregandoDetalhe = true, erroDetalhe = null) }
        jobDetalhe = viewModelScope.launch {
            try {
                val d = repo.detalhe(id)
                _ui.update { if (it.selecionadaId == id) it.copy(carregandoDetalhe = false, detalhe = d) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    if (it.selecionadaId == id) it.copy(carregandoDetalhe = false, erroDetalhe = mensagem(e, "abrir a conversa")) else it
                }
            }
        }
    }

    fun editarRascunho(texto: String) = _ui.update { it.copy(rascunho = texto) }

    /**
     * Envia UMA vez (o webhook não é idempotente e nunca é repetido automaticamente). Se a rede falhar
     * o resultado é desconhecido: o rascunho é mantido, a conversa é recarregada para o usuário conferir
     * e o aviso pede para conferir antes de reenviar.
     */
    fun enviar() {
        val s = _ui.value
        val id = s.selecionadaId ?: return
        val texto = s.rascunho.trim()
        if (texto.isEmpty() || s.enviando) return
        _ui.update { it.copy(enviando = true, erroEnvio = null) }
        viewModelScope.launch {
            try {
                repo.enviar(id, texto)
                _ui.update { if (it.selecionadaId == id) it.copy(enviando = false, rascunho = "") else it.copy(enviando = false) }
                carregarDetalhe(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SemConexaoException) {
                _ui.update { it.copy(enviando = false, erroEnvio = "Não foi possível confirmar se a mensagem foi enviada. Confira a conversa antes de reenviar.") }
                carregarDetalhe(id)
            } catch (e: Exception) {
                _ui.update { it.copy(enviando = false, erroEnvio = "Mensagem não enviada: " + (e.message ?: "erro desconhecido")) }
            }
        }
    }

    private fun mensagem(e: Exception, acao: String): String = when (e) {
        is SemConexaoException -> e.message ?: "Sem conexão."
        is ApiException -> "Não foi possível $acao: ${e.message}"
        else -> "Não foi possível $acao agora."
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val c: AppContainer = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SynapseApplication).container
                return AtendimentoViewModel(c.atendimento, c.sessaoEncerrada) as T
            }
        }
    }
}
