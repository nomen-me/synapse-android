package me.nomen.synapse

import android.app.Application
import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import me.nomen.synapse.data.AtendimentoRepository
import me.nomen.synapse.data.ConfigRepository
import me.nomen.synapse.data.ConsumoRepository
import me.nomen.synapse.data.PedidosRepository
import me.nomen.synapse.data.PedidosStore
import me.nomen.synapse.data.AuthRepository
import me.nomen.synapse.data.HarmoniaClient
import me.nomen.synapse.data.PersistentCookieJar
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.SecureStore
import me.nomen.synapse.data.SynapseEnv
import me.nomen.synapse.data.criarHttpClient
import me.nomen.synapse.domain.Usuario
import me.nomen.synapse.pdv.PdvConfigRepository
import me.nomen.synapse.pdv.PdvSincronizador
import me.nomen.synapse.pdv.PdvStore
import me.nomen.synapse.pdv.PinLockout
import me.nomen.synapse.pdv.SqlitePdvStore

/** Composição manual das dependências (sem framework de DI nesta etapa). */
class AppContainer(context: Context) {
    val secureStore = SecureStore(context)
    val cookieJar = PersistentCookieJar(secureStore.prefs)
    private val http = criarHttpClient(cookieJar)

    /** Emite quando uma chamada autenticada recebe 401/403; a sessão é então revalidada (não deslogada às cegas). */
    val sessaoSuspeita = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emite quando a sessão é encerrada localmente; ViewModels com dados do usuário devem se limpar. */
    val sessaoEncerrada = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun env(): SynapseEnv =
        SynapseEnv.deDominio(checkNotNull(secureStore.dominioBase) { "Domínio da loja não configurado" })

    val ritmo = RitmoClient(http, { env().ritmo }, { sessaoSuspeita.tryEmit(Unit) })
    val harmonia = HarmoniaClient(http) { env().harmonia }

    /** Usuário da sessão vigente (definido pelo SessionViewModel); usado para rastrear quem solicitou ações. */
    @Volatile var usuarioAtual: Usuario? = null
    fun usuarioAtualNome(): String = usuarioAtual?.let { it.nome.ifBlank { it.email } } ?: ""

    val consumo = ConsumoRepository(harmonia)
    val config = ConfigRepository(ritmo)
    val pedidosRepo = PedidosRepository(ritmo, harmonia, config)
    val pedidosStore = PedidosStore(pedidosRepo)

    // PDV offline: fila/config/autorização vivem no aparelho e SOBREVIVEM ao logout (login offline por PIN e operações pendentes).
    val pdvStore: PdvStore = SqlitePdvStore(context)
    val pdvConfig = PdvConfigRepository(ritmo, pdvStore)
    val pdvSync = PdvSincronizador(ritmo, pdvConfig, pdvStore)
    val pinLockout = PinLockout()

    val auth = AuthRepository(ritmo) {
        cookieJar.limpar(); ritmo.csrfToken = null
        usuarioAtual = null; pedidosStore.limpar(); config.limparCache()
        sessaoEncerrada.tryEmit(Unit)
    }
    val atendimento = AtendimentoRepository(harmonia)
}

class SynapseApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
