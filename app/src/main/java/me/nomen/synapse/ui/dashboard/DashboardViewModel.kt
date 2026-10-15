package me.nomen.synapse.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nomen.synapse.SynapseApplication
import me.nomen.synapse.data.AtendimentoRepository
import me.nomen.synapse.data.PedidosStore
import me.nomen.synapse.data.mensagemAmigavel
import kotlin.coroutines.cancellation.CancellationException

/** Conversas aguardando resposta: null = não carregado; erro explícito em vez de "0". */
class ConversasUi(val aguardando: Int? = null, val erro: String? = null, val carregando: Boolean = false)

class DashboardViewModel(
    val store: PedidosStore,
    private val atendimento: AtendimentoRepository,
    sessaoEncerrada: Flow<Unit>
) : ViewModel() {
    private val _conversas = MutableStateFlow(ConversasUi())
    val conversas: StateFlow<ConversasUi> = _conversas.asStateFlow()

    init { viewModelScope.launch { sessaoEncerrada.collect { _conversas.value = ConversasUi() } } }

    fun carregar() {
        viewModelScope.launch { store.garantir() }
        if (_conversas.value.carregando) return
        _conversas.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                val n = atendimento.listarConversas().count { it.aguardandoResposta }
                _conversas.value = ConversasUi(aguardando = n)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _conversas.update { it.copy(carregando = false, erro = mensagemAmigavel(e, "ler as conversas")) }
            }
        }
    }

    fun atualizar() {
        viewModelScope.launch { store.carregar() }
        _conversas.value = ConversasUi(); carregar()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SynapseApplication).container
                return DashboardViewModel(c.pedidosStore, c.atendimento, c.sessaoEncerrada) as T
            }
        }
    }
}
