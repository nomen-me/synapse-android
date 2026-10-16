package me.nomen.synapse

import kotlinx.serialization.json.JsonObject
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.criarHttpClient
import me.nomen.synapse.pdv.OperacaoFila
import me.nomen.synapse.pdv.PdvStore
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class StoreEmMemoria : PdvStore {
    val ops = LinkedHashMap<String, OperacaoFila>()
    val cfg = HashMap<String, JsonObject>()
    override suspend fun salvarOp(op: OperacaoFila) { ops[op.opId] = op }
    override suspend fun obterOp(opId: String) = ops[opId]
    override suspend fun todasOps() = ops.values.toList()
    override suspend fun salvarConfig(chave: String, valor: JsonObject) { cfg[chave] = valor }
    override suspend fun obterConfig(chave: String) = cfg[chave]
    override suspend fun limparTudo() { ops.clear(); cfg.clear() }
}

/** Ritmo simulado (HTTP real local): registra chamadas e responde por doctype. NÃO é o Ritmo de verdade. */
class ServidorRitmoFalso {
    val server = MockWebServer()
    val chamadas = CopyOnWriteArrayList<Triple<String, String, String>>()   // método, caminho decodificado, corpo
    @Volatile var existentes: Map<String, String> = emptyMap()               // doctype -> nome já existente (idempotência)
    @Volatile var falhaPost: ((String) -> MockResponse?)? = null
    @Volatile var todasAsRespostas: MockResponse? = null
    val metodos = ConcurrentHashMap<String, MockResponse>()                   // api/method/<nome> -> resposta (GET ou POST)
    val listas = ConcurrentHashMap<String, (Int) -> String>()                // doctype -> corpo da listagem (recebe limit_start)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun peek(): MockResponse = todasAsRespostas ?: super.peek()
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Com DISCONNECT_AT_START o servidor chama dispatch() com uma requisição "de contabilidade" sem URL.
                val url = request.requestUrl ?: return todasAsRespostas ?: MockResponse().setResponseCode(400)
                val segs = url.pathSegments
                val doctype = segs.getOrNull(2)
                chamadas += Triple(request.method ?: "", segs.joinToString("/"), request.body.snapshot().utf8())
                todasAsRespostas?.let { return it }
                return when {
                    segs.getOrNull(1) == "method" && doctype != null && metodos.containsKey(doctype) -> metodos[doctype]!!
                    request.method == "GET" && segs.getOrNull(1) == "resource" && listas.containsKey(doctype) ->
                        try { MockResponse().setBody(listas[doctype]!!.invoke(url.queryParameter("limit_start")?.toInt() ?: 0)) }
                        catch (e: Exception) { MockResponse().setResponseCode(500).setBody("""{"message":"erro simulado"}""") }   // lambda que lança = servidor com erro 500
                    request.method == "GET" && segs.getOrNull(1) == "resource" && url.queryParameter("filters") != null ->
                        MockResponse().setBody(existentes[doctype]?.let { """{"data":[{"name":"$it"}]}""" } ?: """{"data":[]}""")
                    request.method == "GET" && segs.getOrNull(2) == "frappe.auth.get_logged_user" -> MockResponse().setBody("""{"message":"caixa@loja.com"}""")
                    request.method == "POST" && segs.getOrNull(1) == "resource" ->
                        falhaPost?.invoke(doctype!!) ?: MockResponse().setBody("""{"data":{"name":"${nomeCriado(doctype!!)}"}}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
    }

    fun nomeCriado(doctype: String) = when (doctype) {
        "POS Opening Entry" -> "OPEN-1"; "POS Invoice" -> "INV-1"; "POS Closing Entry" -> "CLOSE-1"
        "Customer" -> "CUST-1"; "Sales Order" -> "SO-1"; else -> "X-1"
    }

    fun ritmo() = RitmoClient(criarHttpClient(CookieJar.NO_COOKIES), { server.url("/").toString().trimEnd('/') }, esperaEntreTentativasMs = 0)
    fun posts(doctype: String) = chamadas.filter { it.first == "POST" && it.second == "api/resource/$doctype" }
    fun parar() = server.shutdown()
}
