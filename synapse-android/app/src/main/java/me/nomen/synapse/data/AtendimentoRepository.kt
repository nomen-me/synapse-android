package me.nomen.synapse.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Canal(val rotulo: String) {
    WHATSAPP("WhatsApp"), INSTAGRAM("Instagram"), FACEBOOK("Facebook"),
    TELEGRAM("Telegram"), SMS("SMS"), SITE("Chat do site");

    companion object {
        // Mesmo mapeamento do painel (CANAL_CURTO); canal desconhecido cai em SITE como no painel.
        fun deContrato(valor: String?): Canal = when (valor) {
            "whatsapp" -> WHATSAPP
            "instagram" -> INSTAGRAM
            "facebook" -> FACEBOOK
            "telegram" -> TELEGRAM
            "sms" -> SMS
            else -> SITE
        }
    }
}

data class Conversa(
    val id: Long, val nome: String, val canal: Canal,
    val naoLida: Boolean, val resolvida: Boolean, val contato: String?, val ultimaMensagem: String?
) {
    /** Painel: não lida => "aguarda"; resolved => "fechado"; demais => "aguarda". */
    val aguardandoResposta: Boolean get() = naoLida || !resolvida
}

enum class AutorMensagem { CLIENTE, AGENTE, LYRA }

data class Mensagem(val autor: AutorMensagem, val horaIso: String?, val texto: String)
data class ClienteContexto(val nome: String?, val cpf: String?, val contato: String?)
data class DetalheConversa(val mensagens: List<Mensagem>, val cliente: ClienteContexto, val pedidos: List<String>)

/**
 * Atendimento via Harmonia (contratos documentados em LEIA-ME_ENTREGA.md, WF-01..WF-04, e usados
 * por Harpa.* no painel). O app nunca fala direto com o Harpa/Chatwoot.
 */
class AtendimentoRepository(private val harmonia: HarmoniaClient) {

    suspend fun listarConversas(): List<Conversa> {
        val r = harmonia.chamar("atendimento/conversas")
        return r.lista("conversas").mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val id = c.long("id") ?: return@mapNotNull null
            Conversa(
                id = id,
                nome = c.str("nome") ?: "Contato sem nome",
                canal = Canal.deContrato(c.str("canal")),
                naoLida = c.bool("nao_lida") == true,
                resolvida = c.str("status") == "resolved",
                contato = c.str("contato"),
                ultimaMensagem = c.str("ultima_mensagem")
            )
        }
    }

    suspend fun detalhe(conversaId: Long): DetalheConversa {
        val r = harmonia.chamar("atendimento/mensagens", JsonObject(mapOf("conversa_id" to JsonPrimitive(conversaId))))
        val mensagens = r.lista("mensagens").mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            Mensagem(
                autor = when (m.str("autor")) {
                    "cliente" -> AutorMensagem.CLIENTE
                    "sistema" -> AutorMensagem.LYRA
                    else -> AutorMensagem.AGENTE
                },
                horaIso = m.str("hora"),
                texto = m.str("texto").orEmpty()
            )
        }
        val cli = r.objeto("cliente")
        return DetalheConversa(
            mensagens = mensagens,
            cliente = ClienteContexto(cli?.str("nome"), cli?.str("cpf"), cli?.str("contato")),
            pedidos = r.lista("ultimos_pedidos").mapNotNull { (it as? JsonObject)?.str("id") }
        )
    }

    /** Sem retry: reenviar poderia duplicar a mensagem ao cliente (decisão de idempotência do WF-03). */
    suspend fun enviar(conversaId: Long, texto: String) {
        harmonia.chamar(
            "atendimento/enviar",
            JsonObject(mapOf("conversa_id" to JsonPrimitive(conversaId), "mensagem" to JsonPrimitive(texto)))
        )
    }
}
