package me.nomen.synapse.data

import kotlin.coroutines.cancellation.CancellationException
import me.nomen.synapse.domain.Perfis
import me.nomen.synapse.domain.Usuario

/** Equivalente a bffLogin / bffVerificarSessao / fazerLogout do painel. */
class AuthRepository(
    private val ritmo: RitmoClient,
    private val aoEncerrarSessaoLocal: () -> Unit
) {
    suspend fun login(email: String, senha: String): Usuario {
        ritmo.login(email, senha)
        try {
            val info = ritmo.papeisDoUsuario(email)
            val papel = Perfis.identificar(info.papeis)
            if (papel == null) {
                runCatching { ritmo.logout() }
                aoEncerrarSessaoLocal()
                throw UsuarioSemPapelException()
            }
            ritmo.buscarCsrfToken()
            return Usuario(email, info.nome ?: email, papel, papel)
        } catch (e: UsuarioSemPapelException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { ritmo.logout() }
            aoEncerrarSessaoLocal()
            throw e
        }
    }

    /** Usuário da sessão vigente, ou null se não há sessão/perfil. Falha de rede é propagada (≠ "sem sessão"). */
    suspend fun verificarSessao(): Usuario? {
        val email: String? = try {
            ritmo.usuarioLogado()
        } catch (e: SessaoInvalidaException) {
            null
        } catch (e: ApiException) {
            null
        }
        if (email == null) return null
        val info = ritmo.papeisDoUsuario(email)
        val papel = Perfis.identificar(info.papeis) ?: return null
        ritmo.buscarCsrfToken()
        return Usuario(email, info.nome ?: email, papel, papel)
    }

    /** Sai sempre localmente, mesmo se o Ritmo não responder (comportamento do painel). */
    suspend fun logout() {
        try { ritmo.logout() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        aoEncerrarSessaoLocal()
    }
}
