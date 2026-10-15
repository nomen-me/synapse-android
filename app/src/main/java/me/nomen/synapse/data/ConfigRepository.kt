package me.nomen.synapse.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConfiguracaoException(mensagem: String) : Exception(mensagem)

class PosProfileBasico(val nome: String, val warehouse: String?, val company: String?, val currency: String?, val priceList: String?)

/** Resoluções de configuração do Ritmo usadas por várias áreas (resolverCompanyUnica / pdvResolverDeposito do painel). */
class ConfigRepository(private val ritmo: RitmoClient) {
    private val mutex = Mutex()
    @Volatile private var companyCache: String? = null

    /**
     * limite=2 de propósito (igual ao painel): distinguir "exatamente 1 Company" de "2 ou mais".
     * A Synapse não escolhe sozinha entre várias Companies.
     */
    suspend fun companyUnica(): String {
        companyCache?.let { return it }
        return mutex.withLock {
            companyCache ?: run {
                val r = ritmo.listar("Company", campos = listOf("name"), limite = 2)
                val empresas = r.lista("data").mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.str("name") }
                when (empresas.size) {
                    1 -> empresas[0].also { companyCache = it }
                    0 -> throw ConfiguracaoException("Nenhuma Company configurada no Ritmo ainda")
                    else -> throw ConfiguracaoException("Existe mais de uma Company no Ritmo — configure qual esta unidade deve usar, a Synapse não escolhe sozinha")
                }
            }
        }
    }

    /** Primeiro POS Profile (premissa do painel: um caixa/depósito ativo). Null se não houver. */
    suspend fun posProfileBasico(): PosProfileBasico? {
        val r = ritmo.listar("POS Profile", campos = listOf("name", "warehouse", "company", "currency", "selling_price_list"), limite = 1)
        val p = r.lista("data").firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
        val nome = p.str("name") ?: return null
        return PosProfileBasico(nome, p.str("warehouse"), p.str("company"), p.str("currency"), p.str("selling_price_list"))
    }

    fun limparCache() { companyCache = null }
}
