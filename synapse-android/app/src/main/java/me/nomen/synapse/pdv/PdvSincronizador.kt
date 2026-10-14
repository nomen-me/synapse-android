package me.nomen.synapse.pdv

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.data.ApiException
import me.nomen.synapse.data.ConfiguracaoException
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.SemConexaoException
import me.nomen.synapse.data.SessaoInvalidaException
import me.nomen.synapse.data.lista
import me.nomen.synapse.data.num
import me.nomen.synapse.data.objeto
import me.nomen.synapse.data.str
import kotlin.coroutines.cancellation.CancellationException

class ResultadoSync(
    val tentadas: Int = 0, val confirmadas: Int = 0, val rejeitadas: Int = 0, val sessaoExpirada: Boolean = false,
    val pendentes: Int = 0, val rejeitadasTotal: Int = 0, val emAndamento: Boolean = false, val semConexao: Boolean = false,
    val avisos: List<String> = emptyList()
)

/** Valor de um item já com desconto/acréscimo próprios (valorItemComAjustePDV do painel). */
fun valorItemComAjuste(i: JsonObject): Double {
    val preco = i.num("preco") ?: 0.0
    val qtd = i.num("qtd") ?: 0.0
    var v = preco * qtd
    (i["desconto"] as? JsonObject)?.let { d ->
        val x = d.num("valor") ?: 0.0
        v -= if (d.str("tipo") == "percentual") v * (x / 100) else x
    }
    (i["acrescimo"] as? JsonObject)?.let { a ->
        val x = a.num("valor") ?: 0.0
        v += if (a.str("tipo") == "percentual") (preco * qtd) * (x / 100) else x
    }
    return maxOf(0.0, v)
}

/** pdvLimparDoc do painel: só letras e dígitos, maiúsculas (suporta CNPJ alfanumérico). */
fun limparDocumento(s: String?): String = (s ?: "").filter { it.isLetterOrDigit() && it.code < 128 }.uppercase()

fun normalizarStatusProducao(s: String?): String {
    val mapa = mapOf("Pendente" to "pendente", "Em Preparo" to "em_preparo", "Pronto" to "pronto", "Enviado" to "enviado", "Entregue" to "entregue", "Cancelado" to "cancelado")
    return mapa[s] ?: (s ?: "pendente").lowercase().replace(Regex("\\s+"), "_")
}

fun rotuloStatusProducao(s: String): String =
    mapOf("pendente" to "Pendente", "em_preparo" to "Em Preparo", "pronto" to "Pronto", "enviado" to "Enviado", "entregue" to "Entregue", "cancelado" to "Cancelado")[s] ?: s

/**
 * Envio da fila offline ao Ritmo. Porta fiel de sincronizarFilaPDV/pdvEnviarOperacao do painel:
 *  - idempotência por `synapse_op_id` (consulta antes de criar; "duplicate/already exists" conta como confirmado);
 *  - "enviando" é gravado ANTES da tentativa (se o app morrer, o próximo ciclo retoma);
 *  - falha de rede → continua pendente; sessão inválida → pausa o ciclo; erro do Ritmo → rejeitado (reenvio manual);
 *  - nenhuma operação financeira é repetida às cegas: só a mesma operação, com o mesmo op_id.
 */
class PdvSincronizador(
    private val ritmo: RitmoClient,
    private val config: PdvConfigRepository,
    private val store: PdvStore,
    /** Gancho pós-venda (NFC-e). Não faz parte desta etapa; falhas aqui nunca afetam a confirmação da venda. */
    private val aposVendaConfirmada: suspend (OperacaoFila, String) -> Unit = { _, _ -> }
) {
    private val mutex = Mutex()
    private val duplicada = Regex("duplicate|already exists", RegexOption.IGNORE_CASE)

    suspend fun enfileirar(op: OperacaoFila): OperacaoFila {
        val nova = op.copy(status = StatusOp.PENDENTE, tentativas = 0)
        store.salvarOp(nova)
        return nova
    }

    suspend fun sincronizarFila(online: Boolean): ResultadoSync {
        if (!mutex.tryLock()) return ResultadoSync(emAndamento = true)
        try {
            if (!online) return ResultadoSync(semConexao = true)
            val fila = store.todasOps().filter { it.status == StatusOp.PENDENTE || it.status == StatusOp.ENVIANDO }.sortedBy { it.criadoEm }
            var confirmadas = 0; var rejeitadas = 0; var sessaoExpirada = false
            val avisos = mutableListOf<String>()
            for (original in fila) {
                store.salvarOp(original.copy(status = StatusOp.ENVIANDO))
                var parar = false
                var status: String; var erro: String? = null; var tentativas = original.tentativas
                try {
                    enviarOperacao(original, avisos)
                    status = StatusOp.CONFIRMADO; confirmadas++
                } catch (e: CancellationException) {
                    throw e   // fica "enviando": o próximo ciclo reenvia com o mesmo op_id (idempotente)
                } catch (e: SessaoInvalidaException) {
                    status = StatusOp.PENDENTE; sessaoExpirada = true; parar = true
                } catch (e: SemConexaoException) {
                    status = StatusOp.PENDENTE; tentativas++; erro = e.message ?: "Falha de rede"
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    if (e is ApiException && duplicada.containsMatchIn(msg)) { status = StatusOp.CONFIRMADO; confirmadas++ }
                    else { status = StatusOp.REJEITADO; tentativas++; erro = msg.ifBlank { "Rejeitado pelo Ritmo" }; rejeitadas++ }
                }
                // Recarrega: o envio pode ter gravado campos novos no payload (ex.: ritmo_invoice_name).
                val atual = store.obterOp(original.opId) ?: original
                store.salvarOp(atual.copy(status = status, tentativas = tentativas, ultimoErro = erro))
                if (parar) break
            }
            val final = store.todasOps()
            return ResultadoSync(
                tentadas = fila.size, confirmadas = confirmadas, rejeitadas = rejeitadas, sessaoExpirada = sessaoExpirada,
                pendentes = final.count { it.status == StatusOp.PENDENTE || it.status == StatusOp.ENVIANDO },
                rejeitadasTotal = final.count { it.status == StatusOp.REJEITADO }, avisos = avisos
            )
        } finally { mutex.unlock() }
    }

    /** Reenvio manual de uma operação rejeitada (pdvReenviarOperacaoRejeitada): volta a pendente com contador zerado. */
    suspend fun reenfileirarRejeitada(opId: String) {
        val op = store.obterOp(opId) ?: return
        if (op.status == StatusOp.REJEITADO) store.salvarOp(op.copy(status = StatusOp.PENDENTE, tentativas = 0, ultimoErro = null))
    }

    // ---- envio por tipo ----------------------------------------------------------------------

    private suspend fun jaExiste(doctype: String, opId: String): String? {
        val filtro = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("synapse_op_id"), JsonPrimitive("="), JsonPrimitive(opId)))))
        return (ritmo.listar(doctype, campos = listOf("name"), filtros = filtro, limite = 1).lista("data").firstOrNull() as? JsonObject)?.str("name")
    }

    private suspend fun criarNome(doctype: String, corpo: JsonObject): String =
        ritmo.criar(doctype, corpo).objeto("data")?.str("name") ?: throw ApiException(200, "O Ritmo não devolveu o nome do documento criado ($doctype)")

    private suspend fun persistirCampos(opId: String, campos: Map<String, JsonElement>) {
        val op = store.obterOp(opId) ?: return
        store.salvarOp(op.copy(payload = JsonObject(op.payload.toMap() + campos)))
    }

    private suspend fun usuarioReal(op: OperacaoFila): String =
        op.operador?.str("user_id") ?: ritmo.usuarioLogado() ?: throw SessaoInvalidaException()

    suspend fun enviarOperacao(op: OperacaoFila, avisos: MutableList<String> = mutableListOf()): String {
        val dep = config.resolverDeposito(online = true)
            ?: throw ConfiguracaoException("Perfil de PDV (POS Profile) não resolvido — sem ele não dá pra submeter nada no Ritmo")
        val p = op.payload
        when (op.tipo) {
            TipoOp.ABERTURA -> {
                jaExiste("POS Opening Entry", op.opId)?.let { persistirCampos(op.opId, mapOf("ritmo_opening_name" to JsonPrimitive(it))); return it }
                val nome = criarNome("POS Opening Entry", buildJsonObject {
                    put("synapse_op_id", op.opId); put("company", dep.company); put("pos_profile", dep.posProfile); put("user", usuarioReal(op))
                    put("period_start_date", p.str("aberto_em")); put("docstatus", 1)
                    put("balance_details", JsonArray(listOf(buildJsonObject { put("mode_of_payment", dep.modoPagamento); put("opening_amount", p.num("troco_inicial") ?: 0.0) })))
                })
                persistirCampos(op.opId, mapOf("ritmo_opening_name" to JsonPrimitive(nome)))
                return nome
            }
            TipoOp.FECHAMENTO -> {
                jaExiste("POS Closing Entry", op.opId)?.let { return it }
                val caixaOpId = p.str("caixa_op_id")
                val abertura = caixaOpId?.let { store.obterOp(it) }
                val nomeAbertura = abertura?.payload?.str("ritmo_opening_name")
                    ?: throw ConfiguracaoException("Abertura de caixa ainda não sincronizou com o Ritmo — feche de novo depois que ela sincronizar")
                val vendas = store.todasOps().filter { it.tipo == TipoOp.VENDA && it.payload.str("caixa_op_id") == caixaOpId && it.payload.str("ritmo_invoice_name") != null }
                val fechadoEm = p.str("fechado_em") ?: throw ConfiguracaoException("Fechamento sem data (fechado_em)")
                return criarNome("POS Closing Entry", buildJsonObject {
                    put("synapse_op_id", op.opId); put("company", dep.company); put("pos_profile", dep.posProfile); put("user", usuarioReal(op))
                    put("pos_opening_entry", nomeAbertura); put("period_start_date", abertura.payload.str("aberto_em"))
                    put("period_end_date", fechadoEm); put("posting_date", fechadoEm.take(10)); put("posting_time", "23:59:59")
                    put("pos_transactions", JsonArray(vendas.map { v ->
                        buildJsonObject {
                            put("pos_invoice", v.payload.str("ritmo_invoice_name"))
                            put("customer", (v.payload["cliente"] as? JsonObject)?.str("nome") ?: "Cliente Balcão")
                            put("grand_total", v.payload.num("total") ?: 0.0)
                        }
                    }))
                    put("docstatus", 1)
                })
            }
            TipoOp.VENDA -> return enviarVenda(op, dep, avisos)
            TipoOp.PEDIDO_VENDA -> return enviarPedidoVenda(op, dep)
            TipoOp.PRODUCAO_STATUS -> {
                val nome = p.str("producao_item_name") ?: throw ConfiguracaoException("Operação de produção sem producao_item_name")
                ritmo.atualizar("Synapse Producao Item", nome, buildJsonObject { put("status_producao", p.str("novo_status")) })
                return nome
            }
            else -> throw ConfiguracaoException("Tipo de operação desconhecido: ${op.tipo}")
        }
    }

    internal fun mapearItem(i: JsonObject, warehouse: String?): JsonObject {
        val qtd = i.num("qtd") ?: 0.0
        val preco = i.num("preco") ?: 0.0
        val rateFinal = if (qtd > 0) valorItemComAjuste(i) / qtd else preco
        return buildJsonObject {
            put("item_code", i.str("item_code")); put("qty", qtd); put("price_list_rate", preco)
            put("rate", Math.round(rateFinal * 100) / 100.0)
            if (warehouse != null) put("warehouse", warehouse)
            if (i.verdadeiro("item_generico")) put("synapse_item_generico", 1)
            i.str("preco_autorizado_por")?.let { put("synapse_preco_autorizado_por", it) }
            i.str("preco_motivo")?.let { put("synapse_preco_motivo", it) }
        }
    }

    internal fun mapearAjustesDoc(venda: JsonObject, dep: DepositoPdv): JsonObject {
        val extra = linkedMapOf<String, JsonElement>()
        (venda["desconto_geral"] as? JsonObject)?.let { d ->
            extra["apply_discount_on"] = JsonPrimitive("Net Total")
            val v = d.num("valor") ?: 0.0
            if (d.str("tipo") == "percentual") extra["additional_discount_percentage"] = JsonPrimitive(v) else extra["discount_amount"] = JsonPrimitive(v)
        }
        val acrescimos = venda.lista("acrescimos").mapNotNull { it as? JsonObject }
        if (acrescimos.isNotEmpty()) {
            val conta = dep.contaAcrescimos ?: throw ConfiguracaoException(
                "Há acréscimos na venda, mas nenhuma conta contábil está configurada para lançá-los (Company/POS Profile → synapse_conta_acrescimos). Configure no Ritmo antes de sincronizar."
            )
            extra["taxes"] = JsonArray(acrescimos.map { a ->
                buildJsonObject {
                    put("charge_type", "Actual"); put("account_head", conta)
                    put("description", (a.str("tipo") ?: "") + (a.str("descricao")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
                    put("tax_amount", a.num("valor") ?: 0.0)
                }
            })
        }
        (venda["vendedor"] as? JsonObject)?.let { v ->
            extra["sales_team"] = JsonArray(listOf(buildJsonObject { put("sales_person", v.str("sales_person")); put("allocated_percentage", 100) }))
        }
        (venda["identificacao"] as? JsonObject)?.let { extra["synapse_identificacao_atendimento"] = JsonPrimitive("${it.str("tipo")} ${it.str("valor")}") }
        return JsonObject(extra)
    }

    private suspend fun enviarVenda(op: OperacaoFila, dep: DepositoPdv, avisos: MutableList<String>): String {
        val v = op.payload
        val nome = jaExiste("POS Invoice", op.opId) ?: run {
            val ajustes = mapearAjustesDoc(v, dep)   // falha cedo se houver acréscimo sem conta configurada
            val total = v.num("total") ?: 0.0
            val base = buildJsonObject {
                put("synapse_op_id", op.opId); put("company", dep.company); put("pos_profile", dep.posProfile)
                put("synapse_operador_real", op.operador?.str("user_id"))
                put("currency", dep.currency); put("conversion_rate", 1); put("selling_price_list", dep.priceList)
                put("price_list_currency", dep.currency); put("plc_conversion_rate", 1)
                put("customer", (v["cliente"] as? JsonObject)?.str("nome") ?: "Cliente Balcão")
                put("is_pos", 1); put("set_warehouse", dep.warehouse); put("paid_amount", total)
                put("items", JsonArray(v.lista("itens").mapNotNull { (it as? JsonObject)?.let { i -> mapearItem(i, dep.warehouse) } }))
                put("payments", JsonArray(listOf(buildJsonObject { put("mode_of_payment", dep.modoPagamento); put("amount", total) })))
                put("docstatus", 1)
            }
            criarNome("POS Invoice", JsonObject(base.toMap() + ajustes.toMap()))
        }
        persistirCampos(op.opId, mapOf("ritmo_invoice_name" to JsonPrimitive(nome)))
        runCatching { aposVendaConfirmada(op, nome) }.onFailure { if (it is CancellationException) throw it else avisos += "NFC-e segue pendente: ${it.message}" }

        v.lista("itens").forEachIndexed { idx, el ->
            val item = el as? JsonObject ?: return@forEachIndexed
            val st = item.str("status_producao") ?: return@forEachIndexed
            val opIdProd = "${op.opId}_prod_$idx"
            try {
                if (jaExiste("Synapse Producao Item", opIdProd) != null) return@forEachIndexed
                criarNome("Synapse Producao Item", buildJsonObject {
                    put("synapse_op_id", opIdProd); put("pos_invoice", nome); put("item_code", item.str("item_code"))
                    put("frase", item.str("frase") ?: item.str("nome")); put("preenchimentos_json", (item["preenchimentos"] ?: JsonObject(emptyMap())).toString())
                    put("alerta", if (item.verdadeiro("alerta_cozinha")) 1 else 0)
                    put("status_producao", rotuloStatusProducao(normalizarStatusProducao(st)))
                })
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                avisos += "Item de produção \"${item.str("frase") ?: item.str("nome")}\" não pôde ser registrado agora — segue só local até a próxima sincronização (${e.message})"
            }
        }
        return nome
    }

    private suspend fun enviarPedidoVenda(op: OperacaoFila, dep: DepositoPdv): String {
        jaExiste("Sales Order", op.opId)?.let { return it }
        val pedido = op.payload
        val cli = pedido["cliente"] as? JsonObject
        if (cli == null || (cli.str("doc").isNullOrBlank() && cli.str("nome").isNullOrBlank()))
            throw ConfiguracaoException("Pedido sem cliente identificável — não é possível criar Sales Order sem um Customer")
        val docLimpo = cli.str("doc")?.let(::limparDocumento)?.takeIf { it.isNotEmpty() }
        var customerId: String? = null
        if (docLimpo != null) customerId = primeiroNome("Customer", "tax_id", docLimpo)
        if (customerId == null) customerId = primeiroNome("Customer", "synapse_pedido_op_id", op.opId)
        if (customerId == null) {
            customerId = criarNome("Customer", buildJsonObject {
                put("customer_name", cli.str("nome") ?: ("Cliente " + (docLimpo ?: op.opId)))
                if (docLimpo != null) put("tax_id", docLimpo)
                put("synapse_pedido_op_id", op.opId)
                put("customer_type", if (docLimpo != null && docLimpo.length == 14) "Company" else "Individual")
            })
        }
        val ajustes = mapearAjustesDoc(pedido, dep)
        val base = buildJsonObject {
            put("synapse_op_id", op.opId); put("synapse_operador_real", op.operador?.str("user_id"))
            put("company", dep.company); put("currency", dep.currency); put("conversion_rate", 1)
            put("selling_price_list", dep.priceList); put("price_list_currency", dep.currency); put("plc_conversion_rate", 1)
            put("customer", customerId); put("transaction_date", op.criadoEm.take(10))
            pedido.str("data_entrega")?.takeIf { it.isNotBlank() }?.let { put("delivery_date", it) }
            put("order_type", "Sales")
            put("items", JsonArray(pedido.lista("itens").mapNotNull { (it as? JsonObject)?.let { i -> mapearItem(i, null) } }))  // Sales Order não baixa estoque
        }
        return criarNome("Sales Order", JsonObject(base.toMap() + ajustes.toMap()))
    }

    private suspend fun primeiroNome(doctype: String, campo: String, valor: String): String? {
        val filtro = JsonArray(listOf(JsonArray(listOf(JsonPrimitive(campo), JsonPrimitive("="), JsonPrimitive(valor)))))
        return (ritmo.listar(doctype, campos = listOf("name"), filtros = filtro, limite = 1).lista("data").firstOrNull() as? JsonObject)?.str("name")
    }
}
