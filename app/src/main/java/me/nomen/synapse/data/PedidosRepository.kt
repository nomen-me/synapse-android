package me.nomen.synapse.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.domain.hojeIso
import kotlin.coroutines.cancellation.CancellationException

enum class TipoPedido(val doctype: String, val rotuloParte: String, val rotulo: String) {
    VENDA("Sales Order", "Cliente", "Vendas"), COMPRA("Purchase Order", "Fornecedor", "Compras")
}

enum class ClasseStatus(val rotulo: String) { AGUARDA("Aguardando"), APROVADO("Aprovado"), PAGO("Concluído"), CANCELADO("Cancelado") }

class StatusPedido(val classe: ClasseStatus, val rotulo: String)

class Pedido(
    val id: String, val tipo: TipoPedido, val parte: String, val valor: Double,
    val status: StatusPedido, val docstatus: Int, val dataIso: String?
)

class ItemPedido(val produto: String, val qtd: Double, val valor: Double)
class DetalhePedido(val itens: List<ItemPedido>, val endereco: String)
class Opcao(val id: String, val nome: String)
class Produto(val codigo: String, val nome: String, val precoPadrao: Double)
class ItemNovoPedido(val codigo: String, val nome: String, val qtd: Double, val rate: Double)
class SituacaoEnvio(val transportadora: String?, val codigo: String?, val entregue: Boolean)

/** Mapeia o status do Ritmo (mapearStatusRitmo do painel). Status desconhecido cai em AGUARDA com o texto original. */
fun mapearStatusRitmo(status: String?): StatusPedido = when (status) {
    "Draft" -> StatusPedido(ClasseStatus.AGUARDA, "Rascunho")
    "On Hold" -> StatusPedido(ClasseStatus.AGUARDA, "Em espera")
    "To Deliver and Bill" -> StatusPedido(ClasseStatus.APROVADO, "A entregar e faturar")
    "To Bill" -> StatusPedido(ClasseStatus.APROVADO, "A faturar")
    "To Deliver" -> StatusPedido(ClasseStatus.APROVADO, "A entregar")
    "Completed" -> StatusPedido(ClasseStatus.PAGO, "Concluído")
    "Closed" -> StatusPedido(ClasseStatus.PAGO, "Encerrado")
    "Cancelled" -> StatusPedido(ClasseStatus.CANCELADO, "Cancelado")
    else -> StatusPedido(ClasseStatus.AGUARDA, status ?: "—")
}

class PedidosRepository(
    private val ritmo: RitmoClient,
    private val harmonia: HarmoniaClient,
    private val config: ConfigRepository
) {
    suspend fun listar(tipo: TipoPedido): List<Pedido> {
        val campos = if (tipo == TipoPedido.VENDA)
            listOf("name", "customer_name", "customer", "grand_total", "status", "docstatus", "transaction_date")
        else listOf("name", "supplier_name", "supplier", "grand_total", "status", "docstatus", "transaction_date")
        val r = ritmo.listar(tipo.doctype, campos = campos, limite = 200)
        return r.lista("data").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("name") ?: return@mapNotNull null
            Pedido(
                id = id, tipo = tipo,
                parte = o.str("customer_name") ?: o.str("supplier_name") ?: o.str("customer") ?: o.str("supplier") ?: "—",
                valor = o.num("grand_total") ?: 0.0, status = mapearStatusRitmo(o.str("status")),
                docstatus = o.num("docstatus")?.toInt() ?: 0, dataIso = o.str("transaction_date")
            )
        }
    }

    /** Itens são child table: só vêm com o documento inteiro (mesmo padrão do painel). */
    suspend fun detalhe(p: Pedido): DetalhePedido {
        val d = ritmo.obter(p.tipo.doctype, p.id).objeto("data")
        val itens = d?.lista("items").orEmpty().mapNotNull { el ->
            val i = el as? JsonObject ?: return@mapNotNull null
            ItemPedido(i.str("item_name") ?: i.str("item_code") ?: "—", i.num("qty") ?: 0.0, i.num("amount") ?: 0.0)
        }
        val endereco = d?.str("shipping_address") ?: d?.str("customer_address") ?: d?.str("supplier_address") ?: "—"
        return DetalhePedido(itens, endereco)
    }

    /**
     * O app NÃO cancela: solicita à Lyra (lyra/alterar), que conduz estorno/estoque/financeiro.
     * Retorna o `resumo` da Lyra, se houver. Nunca assume que o cancelamento já ocorreu.
     */
    suspend fun solicitarCancelamento(id: String, solicitante: String): String? =
        harmonia.chamar(
            "lyra/alterar",
            buildJsonObject {
                put("tipo", "pedidos"); put("id", id); put("instrucao", "Cancelar este pedido, solicitado por $solicitante.")
            }
        ).str("resumo")

    suspend fun situacaoEnvio(pedidoId: String): SituacaoEnvio {
        val r = harmonia.chamar("envio/situacao", buildJsonObject { put("pedidoId", pedidoId) })
        if (r.bool("sucesso") != true) throw ApiException(200, r.str("erro") ?: "Não foi possível consultar agora")
        return SituacaoEnvio(r.str("transportadora"), r.str("codigo"), r.bool("entregue") == true)
    }

    suspend fun clientes(): List<Opcao> = opcoes("Customer", "customer_name")
    suspend fun fornecedores(): List<Opcao> = opcoes("Supplier", "supplier_name")

    private suspend fun opcoes(doctype: String, campoNome: String): List<Opcao> =
        ritmo.listar(doctype, campos = listOf("name", campoNome), limite = 300).lista("data").mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val id = o.str("name") ?: return@mapNotNull null
            Opcao(id, o.str(campoNome) ?: id)
        }

    suspend fun produtos(): List<Produto> =
        ritmo.listar("Item", campos = listOf("item_code", "item_name", "standard_rate"), limite = 300).lista("data").mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val code = o.str("item_code") ?: return@mapNotNull null
            Produto(code, o.str("item_name") ?: code, o.num("standard_rate") ?: 0.0)
        }

    /** Retorna o nome do documento criado. POST: nunca repetido automaticamente. */
    suspend fun criarVenda(cliente: String, itens: List<ItemNovoPedido>): String {
        val pos = runCatching { config.posProfileBasico() }.getOrNull()
        val company = pos?.company ?: config.companyUnica()
        val corpo = buildJsonObject {
            put("customer", cliente); put("company", company); put("order_type", "Sales")
            put("transaction_date", hojeIso())
            pos?.currency?.let { put("currency", it) }
            pos?.priceList?.let { put("selling_price_list", it) }
            put("conversion_rate", 1); put("plc_conversion_rate", 1)
            put("items", JsonArray(itens.map { buildJsonObject { put("item_code", it.codigo); put("qty", it.qtd); put("rate", it.rate) } }))
        }
        return ritmo.criar(TipoPedido.VENDA.doctype, corpo).objeto("data")?.str("name") ?: ""
    }

    suspend fun criarCompra(fornecedor: String, itens: List<ItemNovoPedido>, dataPrevista: String): String {
        val company = config.companyUnica()
        val corpo = buildJsonObject {
            put("supplier", fornecedor); put("company", company)
            put("transaction_date", hojeIso()); put("schedule_date", dataPrevista); put("conversion_rate", 1)
            put("items", JsonArray(itens.map {
                buildJsonObject { put("item_code", it.codigo); put("qty", it.qtd); put("rate", it.rate); put("schedule_date", dataPrevista) }
            }))
        }
        return ritmo.criar(TipoPedido.COMPRA.doctype, corpo).objeto("data")?.str("name") ?: ""
    }
}

/** Cache compartilhado entre Dashboard e Pedidos (o painel usa PEDIDOS_VENDA/PEDIDOS_COMPRA globais). */
class PedidosStore(private val repo: PedidosRepository) {
    data class Estado(
        val vendas: List<Pedido>? = null, val compras: List<Pedido>? = null,
        val carregando: Boolean = false, val erro: String? = null
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()
    private val mutex = Mutex()

    suspend fun garantir() { if (_estado.value.vendas == null) carregar() }

    /** Uma carga por vez; chamadas concorrentes esperam e reaproveitam o resultado recente. */
    suspend fun carregar() {
        if (!mutex.tryLock()) return
        try {
            _estado.update { it.copy(carregando = true, erro = null) }
            try {
                coroutineScope {
                    val v = async { repo.listar(TipoPedido.VENDA) }
                    val c = async { repo.listar(TipoPedido.COMPRA) }
                    val vendas = v.await(); val compras = c.await()
                    _estado.update { it.copy(vendas = vendas, compras = compras, carregando = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nunca finge "sem pedidos": mantém o que havia e mostra o erro.
                _estado.update { it.copy(carregando = false, erro = mensagemAmigavel(e, "carregar os pedidos")) }
            }
        } finally { mutex.unlock() }
    }

    fun limpar() { _estado.value = Estado() }
}

fun mensagemAmigavel(e: Exception, acao: String): String = when (e) {
    is SemConexaoException -> e.message ?: "Sem conexão."
    is SessaoInvalidaException -> e.message ?: "Sessão expirada."
    is ApiException -> "Não foi possível $acao: ${e.message}"
    is ConfiguracaoException -> e.message ?: "Configuração pendente."
    else -> "Não foi possível $acao agora."
}
