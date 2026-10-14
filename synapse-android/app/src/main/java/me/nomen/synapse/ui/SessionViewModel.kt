package me.nomen.synapse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nomen.synapse.AppContainer
import me.nomen.synapse.SynapseApplication
import me.nomen.synapse.data.SemConexaoException
import me.nomen.synapse.data.SynapseEnv
import me.nomen.synapse.domain.Usuario
import kotlin.coroutines.cancellation.CancellationException

sealed interface SessionState {
    data object Verificando : SessionState
    data class SemDominio(val erro: String? = null) : SessionState
    data class Deslogado(val dominio: String, val erro: String? = null, val entrando: Boolean = false) : SessionState
    data class Logado(val dominio: String, val usuario: Usuario) : SessionState
}

class SessionViewModel(private val c: AppContainer) : ViewModel() {
    private val _estado = MutableStateFlow<SessionState>(SessionState.Verificando)
    val estado: StateFlow<SessionState> = _estado.asStateFlow()

    private var loginEmAndamento = false
    private var revalidando = false

    init {
        verificarInicial()
        viewModelScope.launch { c.sessaoSuspeita.collect { revalidar() } }
    }

    private fun verificarInicial() {
        val dominio = c.secureStore.dominioBase
        if (dominio == null) { _estado.value = SessionState.SemDominio(); return }
        viewModelScope.launch {
            _estado.value = try {
                val u = c.auth.verificarSessao()
                if (u != null) { c.usuarioAtual = u; SessionState.Logado(dominio, u) } else SessionState.Deslogado(dominio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SemConexaoException) {
                SessionState.Deslogado(dominio, erro = e.message)
            } catch (e: Exception) {
                SessionState.Deslogado(dominio, erro = e.message)
            }
        }
    }

    fun configurarDominio(entrada: String) {
        val d = SynapseEnv.normalizar(entrada)
        if (d == null) {
            _estado.value = SessionState.SemDominio("Domínio inválido. Use apenas o endereço da loja, por exemplo minhaloja.com.br (somente HTTPS).")
            return
        }
        c.cookieJar.limpar()
        c.secureStore.dominioBase = d
        _estado.value = SessionState.Deslogado(d)
    }

    fun trocarDominio() {
        // Nunca descarta operações do PDV ainda não confirmadas no Ritmo da loja atual.
        viewModelScope.launch {
            val abertas = c.pdvStore.todasOps().count { it.status != "confirmado" }
            val atual = _estado.value as? SessionState.Deslogado
            if (abertas > 0 && atual != null) {
                _estado.value = atual.copy(erro = "Há $abertas operação(ões) do PDV ainda não confirmadas no Ritmo desta loja. Sincronize (ou exporte) antes de trocar de loja.")
                return@launch
            }
            executarTrocaDeDominio()
        }
    }

    private fun executarTrocaDeDominio() {
        c.cookieJar.limpar()
        c.ritmo.csrfToken = null
        c.secureStore.dominioBase = null
        _estado.value = SessionState.SemDominio()
    }

    fun login(email: String, senha: String) {
        val atual = _estado.value as? SessionState.Deslogado ?: return
        if (loginEmAndamento) return
        if (email.isBlank() || senha.isEmpty()) {
            _estado.value = atual.copy(erro = "Informe o e-mail e a senha.")
            return
        }
        loginEmAndamento = true
        _estado.value = atual.copy(erro = null, entrando = true)
        viewModelScope.launch {
            try {
                val u = c.auth.login(email.trim(), senha)
                c.usuarioAtual = u
                _estado.value = SessionState.Logado(atual.dominio, u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.value = atual.copy(erro = e.message ?: "Não foi possível entrar.", entrando = false)
            } finally {
                loginEmAndamento = false
            }
        }
    }

    fun logout() {
        val atual = _estado.value as? SessionState.Logado ?: return
        viewModelScope.launch {
            c.auth.logout()
            _estado.value = SessionState.Deslogado(atual.dominio)
        }
    }

    /** Chamada autenticada recebeu 401/403: confirma com o Ritmo antes de encerrar (403 pode ser só permissão). */
    private fun revalidar() {
        val atual = _estado.value as? SessionState.Logado ?: return
        if (revalidando) return
        revalidando = true
        viewModelScope.launch {
            try {
                if (c.auth.verificarSessao() == null) {
                    c.auth.logout()
                    _estado.value = SessionState.Deslogado(atual.dominio, erro = "Sua sessão expirou. Entre novamente.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sem rede para confirmar: mantém a sessão; a próxima ação do usuário tentará de novo.
            } finally {
                revalidando = false
            }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SynapseApplication
                return SessionViewModel(app.container) as T
            }
        }
    }
}
