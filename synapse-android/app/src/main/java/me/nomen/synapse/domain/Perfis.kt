package me.nomen.synapse.domain

/**
 * Áreas do painel. [naMatriz] reflete ABAS_DA_MATRIZ do painel web: só as abas da matriz são
 * filtradas por perfil. Produção e Unidades não fazem parte da matriz e ficam visíveis a todos
 * (comportamento idêntico ao painel — as permissões reais são validadas no backend).
 */
enum class Aba(val id: String, val titulo: String, val naMatriz: Boolean = true) {
    DASHBOARD("dashboard", "Dashboard"),
    PEDIDOS("pedidos", "Pedidos"),
    PDV("pdv", "Frente de Loja"),
    PRODUCAO("producao", "Produção", naMatriz = false),
    UNIDADES("unidades", "Unidades", naMatriz = false),
    ATENDIMENTO("atendimento", "Atendimento"),
    CADASTROS("cadastros", "Cadastros"),
    FISCAL("fiscal", "Fiscal"),
    INTEGRACOES("integracoes", "Integrações"),
    MARKETING("marketing", "Marketing"),
    TEMA("tema", "Tema da Loja"),
    CONSUMO("consumo", "Consumo");

    companion object {
        fun porId(id: String?): Aba? = entries.firstOrNull { it.id == id }
    }
}

/** Espelha MAPA_PAPEIS_SYNAPSE / PERMISSOES_TABS / tabsPermitidas() do painel. */
object Perfis {
    const val ADMINISTRADOR = "Synapse Administrador"
    /** Perfil do login offline por PIN: pela matriz do painel só enxerga o PDV (mais as áreas fora da matriz). */
    const val OFFLINE = "Synapse Offline"

    val papeisSynapse = listOf(
        ADMINISTRADOR, "Lojista", "Gerente", "Sub gerente", "Operador de loja", "Vendedor", "Caixa", "Administração"
    )

    private val permissoes: Map<String, Set<Aba>> = mapOf(
        "Lojista" to setOf(Aba.DASHBOARD, Aba.PEDIDOS, Aba.ATENDIMENTO, Aba.CADASTROS, Aba.FISCAL, Aba.INTEGRACOES, Aba.MARKETING, Aba.TEMA, Aba.CONSUMO, Aba.PDV),
        "Gerente" to setOf(Aba.DASHBOARD, Aba.PEDIDOS, Aba.ATENDIMENTO, Aba.CADASTROS, Aba.PDV),
        "Sub gerente" to setOf(Aba.DASHBOARD, Aba.PEDIDOS, Aba.ATENDIMENTO, Aba.CADASTROS, Aba.PDV),
        "Operador de loja" to setOf(Aba.DASHBOARD, Aba.PEDIDOS, Aba.ATENDIMENTO, Aba.CADASTROS),
        "Vendedor" to setOf(Aba.DASHBOARD, Aba.PEDIDOS, Aba.ATENDIMENTO, Aba.CADASTROS),
        "Caixa" to setOf(Aba.DASHBOARD, Aba.TEMA, Aba.PDV),
        "Administração" to setOf(Aba.DASHBOARD, Aba.PEDIDOS, Aba.CADASTROS, Aba.CONSUMO),
        OFFLINE to setOf(Aba.PDV)
    )

    /** Primeira Role do usuário (na ordem em que o Ritmo devolve) que seja uma Role Synapse. */
    fun identificar(rolesDoUsuario: List<String>): String? = rolesDoUsuario.firstOrNull { it in papeisSynapse }

    /** Abas visíveis ao perfil, na ordem de navegação do painel. */
    fun abasPermitidas(perfil: String): List<Aba> = Aba.entries.filter { aba ->
        when {
            !aba.naMatriz -> true
            perfil == ADMINISTRADOR -> true
            else -> permissoes[perfil]?.contains(aba) == true
        }
    }

    /** PDV_ACOES_RESTRITAS do painel: ação -> Roles canônicas (com prefixo "Synapse "). */
    private val acoesRestritas: Map<String, List<String>> = listOf(
        "preco_livre", "desconto_item", "acrescimo_item", "desconto_geral", "item_generico", "cancelar_venda"
    ).associateWith { listOf("Synapse Lojista", ADMINISTRADOR) }

    private fun canonico(perfil: String) = if (perfil.startsWith("Synapse ")) perfil else "Synapse $perfil"

    /** Gate de UX (pdvAcaoPermitida). A autorização que vale é a do backend; o app nunca é a barreira final. */
    fun acaoPermitida(perfil: String, acao: String): Boolean {
        val lista = acoesRestritas[acao] ?: return true
        val c = canonico(perfil)
        return c == ADMINISTRADOR || c in lista
    }

    /** Sub-abas restritas de Cadastros (CAD_SUBABAS_RESTRITAS do painel). */
    fun subAbaCadastroPermitida(perfil: String, subaba: String): Boolean = when {
        perfil == ADMINISTRADOR -> true
        subaba == "filiais" -> perfil == "Lojista"
        else -> true
    }
}

data class Usuario(
    val email: String, val nome: String, val perfil: String, val roleFrappe: String,
    /** Sessão do login offline por PIN (sem sessão no Ritmo; só PDV). */
    val offline: Boolean = false, val employee: String? = null
) {
    val iniciais: String
        get() = nome.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}
