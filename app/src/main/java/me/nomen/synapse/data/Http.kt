package me.nomen.synapse.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val SynapseJson = Json { ignoreUnknownKeys = true; isLenient = true }

class RespostaHttp(val code: Int, val body: String)

/**
 * Cria o cliente HTTP único do app. Sem interceptor de log (nada de credenciais/tokens em logcat) e
 * sem retry automático de conexão: POST nunca é repetido pela biblioteca (idempotência — a regra
 * de repetição de GET é explícita em RitmoClient).
 */
fun criarHttpClient(cookieJar: okhttp3.CookieJar): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

suspend fun OkHttpClient.executar(req: Request): RespostaHttp = suspendCancellableCoroutine { cont ->
    val call = newCall(req)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(SemConexaoException(e))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { r ->
                try {
                    val corpo = r.body?.string().orEmpty()
                    if (cont.isActive) cont.resume(RespostaHttp(r.code, corpo))
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(SemConexaoException(e))
                }
            }
        }
    })
}

/** [caminho] é fixo (ex.: "api/resource"); cada item de [dinamicos] (doctype, nome do doc...) é codificado como um único segmento. */
fun urlDe(base: String, caminho: String, vararg dinamicos: String, params: Map<String, String> = emptyMap()): HttpUrl =
    base.toHttpUrl().newBuilder().apply {
        addPathSegments(caminho)
        dinamicos.forEach { addPathSegment(it) }
        params.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()

fun parseObjeto(texto: String): JsonObject =
    (runCatching { SynapseJson.parseToJsonElement(texto) }.getOrNull() as? JsonObject) ?: JsonObject(emptyMap())

fun JsonObject.str(chave: String): String? = (this[chave] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.bool(chave: String): Boolean? = (this[chave] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.long(chave: String): Long? = (this[chave] as? JsonPrimitive)?.longOrNull
fun JsonObject.num(chave: String): Double? = (this[chave] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.objeto(chave: String): JsonObject? = this[chave] as? JsonObject
fun JsonObject.lista(chave: String): List<JsonElement> = (this[chave] as? JsonArray)?.toList().orEmpty()

private val TAGS_HTML = Regex("<[^>]*>")

/**
 * Extrai uma mensagem legível de erro: Frappe usa `_server_messages` (JSON dentro de JSON, com HTML),
 * `exception` ou `message`; o Harmonia usa `message`. Retorna null se nada utilizável.
 */
fun mensagemDeErro(corpo: JsonObject): String? {
    corpo.str("_server_messages")?.let { bruto ->
        runCatching {
            (SynapseJson.parseToJsonElement(bruto) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.mapNotNull { parseObjeto(it).str("message") }
                ?.joinToString(" ") { it.replace(TAGS_HTML, "").trim() }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
    }
    corpo.str("message")?.takeIf { it.isNotBlank() }?.let { return it.replace(TAGS_HTML, "").trim() }
    corpo.str("exception")?.takeIf { it.isNotBlank() }?.let { return it.substringAfterLast(':').trim() }
    return null
}
