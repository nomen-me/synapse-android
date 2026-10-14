package me.nomen.synapse.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PapeisUsuario(val nome: String?, val papeis: List<String>)

/**
 * Cliente do Ritmo (ERPNext/Frappe REST). Equivalente ao objeto `Ritmo` do painel
 * (synapse_painel_18-09t.html): sessão nativa por cookie + token CSRF nas escritas.
 *
 * GET é idempotente e tem até 3 tentativas em falha de rede; qualquer outro método NUNCA é repetido.
 */
class RitmoClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val aoPossivelSessaoInvalida: () -> Unit = {},
    private val esperaEntreTentativasMs: Long = 1000L
) {
    @Volatile var csrfToken: String? = null

    private val json = "application/json; charset=utf-8".toMediaType()

    // ---- sessão -----------------------------------------------------------------------------

    suspend fun login(usuario: String, senha: String) {
        csrfToken = null
        val req = Request.Builder()
            .url(urlDe(baseUrl(), "api/method/login"))
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("usr", usuario).add("pwd", senha).build())
            .build()
        val r = http.executar(req)
        if (r.code >= 500) throw ApiException(r.code, "O servidor da loja não respondeu corretamente (HTTP ${r.code}). Tente de novo em instantes.")
        if (r.code !in 200..299 || parseObjeto(r.body).str("message") != "Logged In") throw CredenciaisInvalidasException()
    }

    suspend fun logout() {
        try { requisitar("api/method/logout", metodo = "POST") } finally { csrfToken = null }
    }

    /** E-mail do usuário logado, ou null se a sessão é de visitante ("Guest"). */
    suspend fun usuarioLogado(): String? =
        requisitar("api/method/frappe.auth.get_logged_user").str("message")?.takeIf { it != "Guest" }

    /** Token CSRF da sessão (Server Script `synapse_csrf_token` no Ritmo). Falhar aqui não derruba o login. */
    suspend fun buscarCsrfToken() {
        csrfToken = try {
            requisitar("api/method/synapse_csrf_token").str("message")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun papeisDoUsuario(email: String): PapeisUsuario {
        val r = requisitar(
            "api/resource/User", email,
            params = mapOf("fields" to JsonArray(listOf(JsonPrimitive("roles"), JsonPrimitive("full_name"))).toString())
        )
        val data = r.objeto("data")
        val papeis = data?.lista("roles").orEmpty().mapNotNull { (it as? JsonObject)?.str("role") }
        return PapeisUsuario(data?.str("full_name"), papeis)
    }

    // ---- recursos genéricos (contratos do painel: Ritmo.listar/obter/criar/atualizar/excluir) ----

    suspend fun listar(
        doctype: String, campos: List<String> = listOf("name"), filtros: JsonElement? = null, limite: Int = 50
    ): JsonObject {
        val params = mutableMapOf(
            "fields" to JsonArray(campos.map { JsonPrimitive(it) }).toString(),
            "limit_page_length" to limite.toString()
        )
        filtros?.let { params["filters"] = it.toString() }
        return requisitar("api/resource", doctype, params = params)
    }

    /** Pacote de autorização offline do Caixa (Server Script `synapse_sincronizar_caixa`); o conteúdo vem em `message`. */
    suspend fun sincronizarCaixa(posProfile: String): JsonObject =
        requisitar("api/method/synapse_sincronizar_caixa", params = mapOf("pos_profile" to posProfile))

    suspend fun definirPin(pinHashHex: String, pinSaltBase64: String, iteracoes: Int): JsonObject = chamarMetodo(
        "synapse_definir_pin",
        JsonObject(mapOf("pin_hash" to JsonPrimitive(pinHashHex), "pin_salt" to JsonPrimitive(pinSaltBase64), "pin_kdf_iteracoes" to JsonPrimitive(iteracoes)))
    )

    /** Conectividade REAL: o Ritmo responde (não basta existir rede). Uma tentativa só, sem retry. */
    suspend fun ritmoAlcancavel(): Boolean = try {
        requisitar("api/method/synapse_csrf_token", tentativasGet = 1); true
    } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { false }

    suspend fun obter(doctype: String, nome: String): JsonObject = requisitar("api/resource", doctype, nome)
    suspend fun criar(doctype: String, dados: JsonObject): JsonObject = requisitar("api/resource", doctype, metodo = "POST", corpo = dados)
    suspend fun atualizar(doctype: String, nome: String, dados: JsonObject): JsonObject =
        requisitar("api/resource", doctype, nome, metodo = "PUT", corpo = dados)
    suspend fun excluir(doctype: String, nome: String): JsonObject = requisitar("api/resource", doctype, nome, metodo = "DELETE")
    suspend fun chamarMetodo(metodo: String, dados: JsonObject): JsonObject =
        requisitar("api/method", metodo, metodo = "POST", corpo = dados)

    // ---- transporte -------------------------------------------------------------------------

    private suspend fun requisitar(
        caminho: String, vararg dinamicos: String,
        metodo: String = "GET", corpo: JsonObject? = null, params: Map<String, String> = emptyMap(), tentativasGet: Int = 3
    ): JsonObject {
        val tentativas = if (metodo == "GET") tentativasGet else 1
        var ultima: SemConexaoException? = null
        for (i in 1..tentativas) {
            try {
                return umaVez(urlDe(baseUrl(), caminho, *dinamicos, params = params), metodo, corpo)
            } catch (e: SemConexaoException) {
                ultima = e
                if (i < tentativas) delay(esperaEntreTentativasMs)
            }
        }
        throw ultima ?: SemConexaoException()
    }

    private suspend fun umaVez(url: HttpUrl, metodo: String, corpo: JsonObject?): JsonObject {
        val rb = Request.Builder().url(url).header("Accept", "application/json")
        when (metodo) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            else -> rb.method(metodo, corpo?.toString()?.toRequestBody(json) ?: "".toRequestBody(null))
        }
        if (metodo != "GET") csrfToken?.let { rb.header("X-Frappe-CSRF-Token", it) }

        val r = http.executar(rb.build())
        if (r.code == 401 || r.code == 403) {
            aoPossivelSessaoInvalida()
            throw SessaoInvalidaException()
        }
        val obj = parseObjeto(r.body)
        if (r.code !in 200..299) throw ApiException(r.code, mensagemDeErro(obj) ?: "Falha ao falar com o Ritmo (HTTP ${r.code}).")
        return obj
    }
}
