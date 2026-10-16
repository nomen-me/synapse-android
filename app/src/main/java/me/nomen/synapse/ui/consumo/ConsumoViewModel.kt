package me.nomen.synapse.ui.consumo

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
import me.nomen.synapse.data.ConsumoRepository
import me.nomen.synapse.data.ConsumoServicos
import me.nomen.synapse.data.UsoVps
import me.nomen.synapse.data.mensagemAmigavel
import kotlin.coroutines.cancellation.CancellationException

data class ConsumoUi(
    val carregando: Boolean = false, val vps: UsoVps? = null, val erroVps: String? = null,
    val servicos: ConsumoServicos? = null, val erroServicos: String? = null, val atualizadoEm: String? = null
)

class ConsumoViewModel(private val repo: ConsumoRepository, sessaoEncerrada: Flow<Unit>) : ViewModel() {
    private val _ui = MutableStateFlow(ConsumoUi())
    val ui: StateFlow<ConsumoUi> = _ui.asStateFlow()

    init { viewModelScope.launch { sessaoEncerrada.collect { _ui.value = ConsumoUi() } } }

    fun carregar() {
        if (_ui.value.carregando) return
        _ui.update { ConsumoUi(carregando = true, vps = it.vps, servicos = it.servicos) }
        viewModelScope.launch {
            var vps: UsoVps? = null; var erroVps: String? = null
            var serv: ConsumoServicos? = null; var erroServ: String? = null
            try { vps = repo.metricasVps() } catch (e: CancellationException) { throw e } catch (e: Exception) { erroVps = mensagemAmigavel(e, "carregar as métricas do servidor") }
            try { serv = repo.servicos() } catch (e: CancellationException) { throw e } catch (e: Exception) { erroServ = mensagemAmigavel(e, "carregar o consumo de serviços") }
            val hora = java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
            _ui.value = ConsumoUi(false, vps, erroVps, serv, erroServ, hora)
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SynapseApplication).container
                return ConsumoViewModel(c.consumo, c.sessaoEncerrada) as T
            }
        }
    }
}
