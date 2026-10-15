package me.nomen.synapse.pdv

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.data.bool
import me.nomen.synapse.data.lista
import me.nomen.synapse.data.num
import me.nomen.synapse.data.str

object StatusOp { const val PENDENTE = "pendente"; const val ENVIANDO = "enviando"; const val CONFIRMADO = "confirmado"; const val REJEITADO = "rejeitado" }
object TipoOp {
    const val ABERTURA = "abertura_caixa"; const val FECHAMENTO = "fechamento_caixa"; const val VENDA = "venda"
    const val PEDIDO_VENDA = "pedido_venda"; const val PRODUCAO_STATUS = "producao_status"
}

/** Operação da fila offline. As chaves do JSON são exatamente as do painel (compatível com o pacote de pendências). */
data class OperacaoFila(
    val opId: String, val tipo: String, val status: String = StatusOp.PENDENTE, val tentativas: Int = 0,
    val ultimoErro: String? = null, val criadoEm: String, val operador: JsonObject? = null, val payload: JsonObject
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("op_id", opId); put("tipo", tipo); put("status", status); put("tentativas", tentativas)
        ultimoErro?.let { put("ultimo_erro", it) }
        put("criado_em", criadoEm)
        operador?.let { put("operador", it) }
        put("payload", payload)
    }

    companion object {
        fun deJson(o: JsonObject): OperacaoFila? {
            val id = o.str("op_id") ?: return null
            return OperacaoFila(
                opId = id, tipo = o.str("tipo") ?: return null, status = o.str("status") ?: StatusOp.PENDENTE,
                tentativas = o.num("tentativas")?.toInt() ?: 0, ultimoErro = o.str("ultimo_erro"),
                criadoEm = o.str("criado_em") ?: return null, operador = o["operador"] as? JsonObject,
                payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap())
            )
        }
    }
}

/** Config do depósito/POS Profile (chave "deposito" — mesmos campos do painel). */
class DepositoPdv(
    val posProfile: String, val warehouse: String, val company: String?, val currency: String?,
    val priceList: String?, val modoPagamento: String?, val contaAcrescimos: String?
) {
    companion object {
        fun deJson(o: JsonObject?): DepositoPdv? {
            val pos = o?.str("pos_profile") ?: return null
            val wh = o.str("warehouse") ?: return null
            return DepositoPdv(pos, wh, o.str("company"), o.str("currency"), o.str("selling_price_list"), o.str("modo_pagamento"), o.str("conta_acrescimos"))
        }
    }
}

class OperadorAutorizado(
    val userId: String, val nome: String, val roles: List<String>, val employee: String?,
    val pinDefinido: Boolean, val pinHash: String?, val pinSalt: String?, val pinIteracoes: Int?
) {
    val podeEntrarOffline: Boolean get() = pinDefinido && pinHash != null && pinSalt != null && pinIteracoes != null

    companion object {
        fun deJson(o: JsonObject): OperadorAutorizado? {
            val id = o.str("user_id") ?: return null
            return OperadorAutorizado(
                id, o.str("nome_exibicao") ?: id, o.lista("roles").mapNotNull { (it as? JsonPrimitive)?.content },
                o.str("employee"), o.bool("pin_definido") == true || (o.num("pin_definido") ?: 0.0) != 0.0,
                o.str("pin_hash"), o.str("pin_salt"), o.num("pin_kdf_iteracoes")?.toInt()
            )
        }
    }
}

class AutorizacaoCaixa(
    val posProfile: String?, val sincronizadoEm: String, val restricaoConfigurada: Boolean,
    val usuarios: List<OperadorAutorizado>
) {
    companion object {
        fun deJson(o: JsonObject?): AutorizacaoCaixa? {
            if (o == null) return null
            return AutorizacaoCaixa(
                o.str("pos_profile"), o.str("sincronizado_em") ?: return null, o.bool("restricao_configurada") == true,
                o.lista("usuarios").mapNotNull { (it as? JsonObject)?.let(OperadorAutorizado::deJson) }
            )
        }
    }
}

/** Truthiness no estilo JS usada pelo painel (`if (i.item_generico)`): true, número != 0 ou string não vazia. */
fun JsonObject.verdadeiro(chave: String): Boolean {
    val p = this[chave] as? JsonPrimitive ?: return false
    if (p is JsonNull) return false
    val c = p.content
    return !(c == "false" || c == "0" || c.isBlank())
}
