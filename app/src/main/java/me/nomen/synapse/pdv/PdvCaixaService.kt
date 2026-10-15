package me.nomen.synapse.pdv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.data.bool
import me.nomen.synapse.data.num
import me.nomen.synapse.data.str
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val FORMATO_ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
fun agoraIso(): String = FORMATO_ISO_MS.format(Instant.now())

class CaixaPdv(val opId: String, val trocoInicial: Double, val abertoEm: String)

/** pdvIdentidadeOperador do painel: quem operou (online ou offline por PIN). */
class OperadorPdv(val userId: String?, val nome: String?, val employee: String?, val modo: String) {
    fun toJson(): JsonObject = buildJsonObject { put("user_id", userId); put("nome", nome); put("employee", employee); put("modo", modo) }
    companion object { val DESCONHECIDO = OperadorPdv(null, null, null, "desconhecido") }
}

class ContextoVenda(val cliente: JsonObject? = null, val vendedor: JsonObject? = null, val identificacao: JsonObject? = null)

/**
 * Caixa, venda e pedido do PDV. Tudo vira operação na fila local (já com status "pendente"); a confirmação só vem do
 * [PdvSincronizador] quando o Ritmo responde. Nenhuma venda é marcada como paga/concluída aqui.
 */
class PdvCaixaService(
    private val store: PdvStore,
    private val sync: PdvSincronizador,
    private val catalogo: PdvCatalogoRepository,
    private val agora: () -> String = ::agoraIso,
    private val novoUuid: () -> String = { UUID.randomUUID().toString() }
) {
    fun gerarId(prefixo: String) = prefixo + "_" + novoUuid()

    suspend fun caixaAberto(): CaixaPdv? {
        val c = store.obterConfig("caixa_atual") ?: return null
        if (c.bool("fechado") == true) return null
        return CaixaPdv(c.str("op_id") ?: return null, c.num("troco_inicial") ?: 0.0, c.str("aberto_em") ?: return null)
    }

    suspend fun abrir(trocoInicial: Double, operador: OperadorPdv): CaixaPdv {
        check(caixaAberto() == null) { "Já existe um caixa aberto neste terminal" }
        require(trocoInicial >= 0) { "O troco inicial não pode ser negativo" }
        val t = agora(); val opId = gerarId("caixa")
        store.salvarConfig("caixa_atual", buildJsonObject {
            put("chave", "caixa_atual"); put("op_id", opId); put("troco_inicial", trocoInicial); put("aberto_em", t); put("fechado", false)
        })
        sync.enfileirar(OperacaoFila(opId, TipoOp.ABERTURA, criadoEm = t, operador = operador.toJson(), payload = buildJsonObject { put("troco_inicial", trocoInicial); put("aberto_em", t) }))
        return CaixaPdv(opId, trocoInicial, t)
    }

    suspend fun fechar(operador: OperadorPdv): OperacaoFila {
        val caixa = checkNotNull(caixaAberto()) { "Não há caixa aberto" }
        val t = agora()
        store.salvarConfig("caixa_atual", buildJsonObject {
            put("chave", "caixa_atual"); put("op_id", caixa.opId); put("troco_inicial", caixa.trocoInicial); put("aberto_em", caixa.abertoEm); put("fechado", true); put("fechado_em", t)
        })
        return sync.enfileirar(OperacaoFila(gerarId("fechacaixa"), TipoOp.FECHAMENTO, criadoEm = t, operador = operador.toJson(),
            payload = buildJsonObject { put("caixa_op_id", caixa.opId); put("fechado_em", t) }))
    }

    private fun itensPayload(c: Carrinho): JsonElement = JsonArray(c.itens.map { it.paraPayload() })
    private fun nuloOu(o: JsonObject?): JsonElement = o ?: JsonNull

    /**
     * [opIdPreGerado]: a tela gera o id ao abrir o checkout; um segundo toque em "finalizar" com o mesmo id devolve a
     * operação já gravada (sem duplicar a fila nem baixar o estoque local duas vezes).
     */
    suspend fun finalizarVenda(carrinho: Carrinho, ctx: ContextoVenda, pagamento: JsonObject?, operador: OperadorPdv, opIdPreGerado: String? = null): OperacaoFila {
        val opId = opIdPreGerado ?: gerarId("venda")
        store.obterOp(opId)?.let { return it }
        require(!carrinho.vazio) { "O carrinho está vazio" }
        val caixa = checkNotNull(caixaAberto()) { "Abra o caixa antes de vender" }
        val t = agora()
        val payload = buildJsonObject {
            put("op_id", opId); put("criado_em", t); put("caixa_op_id", caixa.opId)
            put("cliente", nuloOu(ctx.cliente)); put("vendedor", nuloOu(ctx.vendedor)); put("identificacao", nuloOu(ctx.identificacao))
            put("desconto_geral", carrinho.descontoGeral?.toJson() ?: JsonNull)
            put("acrescimos", JsonArray(carrinho.acrescimos.map { it.toJson() }))
            put("itens", itensPayload(carrinho)); put("total", carrinho.total())
            put("pagamento", nuloOu(pagamento)); put("nfce", buildJsonObject { put("status", "registrada") })
        }
        val op = sync.enfileirar(OperacaoFila(opId, TipoOp.VENDA, criadoEm = t, operador = operador.toJson(), payload = payload))
        catalogo.decrementarEstoqueLocal(carrinho.itens)
        return op
    }

    /** Pedido de venda (Sales Order): exige cliente; não exige caixa; não baixa estoque nem cobra. */
    suspend fun finalizarPedido(carrinho: Carrinho, ctx: ContextoVenda, dataEntrega: String?, observacoes: String?, operador: OperadorPdv, opIdPreGerado: String? = null): OperacaoFila {
        val opId = opIdPreGerado ?: gerarId("pedido")
        store.obterOp(opId)?.let { return it }
        require(!carrinho.vazio) { "O carrinho está vazio" }
        requireNotNull(ctx.cliente) { "Selecione o cliente do pedido" }
        val t = agora()
        val payload = buildJsonObject {
            put("op_id", opId); put("criado_em", t)
            put("cliente", ctx.cliente); put("vendedor", nuloOu(ctx.vendedor)); put("identificacao", nuloOu(ctx.identificacao))
            put("desconto_geral", carrinho.descontoGeral?.toJson() ?: JsonNull)
            put("acrescimos", JsonArray(carrinho.acrescimos.map { it.toJson() }))
            put("itens", itensPayload(carrinho)); put("total", carrinho.total())
            put("data_entrega", dataEntrega?.takeIf { it.isNotBlank() }); put("observacoes", observacoes?.trim()?.takeIf { it.isNotEmpty() })
        }
        return sync.enfileirar(OperacaoFila(opId, TipoOp.PEDIDO_VENDA, criadoEm = t, operador = operador.toJson(), payload = payload))
    }
}
