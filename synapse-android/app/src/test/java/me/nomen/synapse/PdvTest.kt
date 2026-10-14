package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.SynapseJson
import me.nomen.synapse.data.criarHttpClient
import me.nomen.synapse.data.parseObjeto
import me.nomen.synapse.pdv.OperacaoFila
import me.nomen.synapse.pdv.PdvConfigRepository
import me.nomen.synapse.pdv.PdvSincronizador
import me.nomen.synapse.pdv.PdvStore
import me.nomen.synapse.pdv.PinCrypto
import me.nomen.synapse.pdv.PinLockout
import me.nomen.synapse.pdv.StatusOp
import me.nomen.synapse.pdv.TipoOp
import me.nomen.synapse.pdv.limparDocumento
import me.nomen.synapse.pdv.valorItemComAjuste
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private class StoreEmMemoria : PdvStore {
    val ops = LinkedHashMap<String, OperacaoFila>()
    val cfg = HashMap<String, JsonObject>()
    override suspend fun salvarOp(op: OperacaoFila) { ops[op.opId] = op }
    override suspend fun obterOp(opId: String) = ops[opId]
    override suspend fun todasOps() = ops.values.toList()
    override suspend fun salvarConfig(chave: String, valor: JsonObject) { cfg[chave] = valor }
    override suspend fun obterConfig(chave: String) = cfg[chave]
    override suspend fun limparTudo() { ops.clear(); cfg.clear() }
}

class PinCryptoTest {
    @Test fun vetoresPbkdf2Sha256() {
        // Vetores de referência (hashlib.pbkdf2_hmac): garantem compatibilidade com o WebCrypto do painel.
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", PinCrypto.derivarHashHex("password", "salt".toByteArray(), 1))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", PinCrypto.derivarHashHex("password", "salt".toByteArray(), 4096))
    }

    @Test fun verificaPinCertoEErrado() {
        val salt = PinCrypto.gerarSalt()
        val hash = PinCrypto.derivarHashHex("123456", salt, 1000)
        val b64 = PinCrypto.paraBase64(salt)
        assertTrue(PinCrypto.verificar("123456", b64, 1000, hash))
        assertFalse(PinCrypto.verificar("654321", b64, 1000, hash))
    }

    @Test fun pinPrecisaTerSeisDigitos() {
        assertTrue(PinCrypto.pinValido("012345")); assertFalse(PinCrypto.pinValido("12345")); assertFalse(PinCrypto.pinValido("12345a"))
    }

    @Test fun iteracoesCalibradasFicamNosLimites() {
        val it = PinCrypto.calibrarIteracoes(1)
        assertTrue(it in 100_000..2_000_000)
    }

    @Test fun cincoErrosBloqueiamPorUmMinuto() {
        var agora = 1_000_000L
        val l = PinLockout { agora }
        repeat(4) { l.registrarErro("u"); assertFalse(l.bloqueado("u")) }
        l.registrarErro("u"); assertTrue(l.bloqueado("u"))
        assertFalse(l.bloqueado("outro"))
        agora += 59_000; assertTrue(l.bloqueado("u"))
        agora += 2_000; assertFalse(l.bloqueado("u"))
    }
}

class PdvSincronizadorTest {
    private lateinit var server: MockWebServer
    private val chamadas = CopyOnWriteArrayList<Triple<String, String, String>>()   // método, caminho decodificado, corpo
    private var existentes: Map<String, String> = emptyMap()                        // doctype -> nome já existente (idempotência)
    private var falhaPost: ((String) -> MockResponse?)? = null
    private var todasAsRespostas: MockResponse? = null
    private val store = StoreEmMemoria()
    private lateinit var sync: PdvSincronizador

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            // O MockWebServer consulta peek() para políticas de socket (ex.: DISCONNECT_AT_START) antes de ler a requisição.
            override fun peek(): MockResponse = todasAsRespostas ?: super.peek()
            override fun dispatch(request: RecordedRequest): MockResponse {
                val segs = request.requestUrl!!.pathSegments
                val corpo = request.body.snapshot().utf8()
                chamadas += Triple(request.method!!, segs.joinToString("/"), corpo)
                todasAsRespostas?.let { return it }
                val doctype = segs.getOrNull(2)
                return when {
                    request.method == "GET" && segs.getOrNull(1) == "resource" && request.requestUrl!!.queryParameter("filters") != null ->
                        MockResponse().setBody(existentes[doctype]?.let { """{"data":[{"name":"$it"}]}""" } ?: """{"data":[]}""")
                    request.method == "GET" && segs.getOrNull(1) == "method" && segs.getOrNull(2) == "frappe.auth.get_logged_user" ->
                        MockResponse().setBody("""{"message":"caixa@loja.com"}""")
                    request.method == "POST" && segs.getOrNull(1) == "resource" ->
                        falhaPost?.invoke(doctype!!) ?: MockResponse().setBody("""{"data":{"name":"${nomeCriado(doctype!!)}"}}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        val ritmo = RitmoClient(criarHttpClient(CookieJar.NO_COOKIES), { server.url("/").toString().trimEnd('/') }, esperaEntreTentativasMs = 0)
        store.cfg["deposito"] = parseObjeto("""{"chave":"deposito","warehouse":"Loja - ML","pos_profile":"PDV1","company":"ML","currency":"BRL","selling_price_list":"Padrão","modo_pagamento":"Dinheiro","conta_acrescimos":null}""")
        sync = PdvSincronizador(ritmo, PdvConfigRepository(ritmo, store), store)
    }

    @After fun tearDown() = server.shutdown()

    private fun nomeCriado(doctype: String) = when (doctype) {
        "POS Opening Entry" -> "OPEN-1"; "POS Invoice" -> "INV-1"; "POS Closing Entry" -> "CLOSE-1"; "Customer" -> "CUST-1"; "Sales Order" -> "SO-1"; else -> "X-1"
    }

    private fun op(id: String, tipo: String, criado: String, payload: String) = OperacaoFila(
        opId = id, tipo = tipo, criadoEm = criado, operador = parseObjeto("""{"user_id":"caixa@loja.com","nome":"Caixa"}"""), payload = parseObjeto(payload)
    )

    private val vendaComDesconto = """{"caixa_op_id":"cx1","total":90,"itens":[{"item_code":"A","qtd":1,"preco":100,"desconto":{"tipo":"percentual","valor":10}}]}"""

    private fun posts(doctype: String) = chamadas.filter { it.first == "POST" && it.second == "api/resource/$doctype" }

    @Test fun vendaConfirmadaGravaNomeEMapeiaItem() = runBlocking {
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(online = true)
        assertEquals(1, r.confirmadas); assertEquals(0, r.pendentes)
        assertEquals(StatusOp.CONFIRMADO, store.ops["v1"]!!.status)
        assertEquals("INV-1", store.ops["v1"]!!.payload["ritmo_invoice_name"]!!.jsonPrimitive.content)
        val corpo = SynapseJson.parseToJsonElement(posts("POS Invoice").single().third).jsonObject
        assertEquals("v1", corpo["synapse_op_id"]!!.jsonPrimitive.content)
        assertEquals("Cliente Balcão", corpo["customer"]!!.jsonPrimitive.content)
        val item = corpo["items"]!!.jsonArray[0].jsonObject
        assertEquals(90.0, item["rate"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(100.0, item["price_list_rate"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("Loja - ML", item["warehouse"]!!.jsonPrimitive.content)
    }

    @Test fun operacaoJaExistenteNaoEhCriadaDeNovo() = runBlocking {
        existentes = mapOf("POS Invoice" to "INV-9")
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.confirmadas); assertTrue(posts("POS Invoice").isEmpty())
        assertEquals("INV-9", store.ops["v1"]!!.payload["ritmo_invoice_name"]!!.jsonPrimitive.content)
    }

    @Test fun falhaDeRedeMantemPendenteEContaTentativa() = runBlocking {
        todasAsRespostas = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(true)
        assertEquals(0, r.confirmadas); assertEquals(1, r.pendentes)
        assertEquals(StatusOp.PENDENTE, store.ops["v1"]!!.status); assertEquals(1, store.ops["v1"]!!.tentativas)
        assertNotNull(store.ops["v1"]!!.ultimoErro)
    }

    @Test fun sessaoInvalidaPausaOCicloSemPerderOperacoes() = runBlocking {
        todasAsRespostas = MockResponse().setResponseCode(403).setBody("{}")
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        store.salvarOp(op("v2", TipoOp.VENDA, "2026-10-12T10:05:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(true)
        assertTrue(r.sessaoExpirada); assertEquals(2, r.pendentes)
        assertEquals(StatusOp.PENDENTE, store.ops["v1"]!!.status); assertEquals(StatusOp.PENDENTE, store.ops["v2"]!!.status)
        assertTrue(posts("POS Invoice").isEmpty())
    }

    @Test fun erroDoRitmoRejeitaComMensagem() = runBlocking {
        falhaPost = { MockResponse().setResponseCode(417).setBody("""{"message":"Item A sem estoque"}""") }
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.rejeitadas); assertEquals(1, r.rejeitadasTotal); assertEquals(0, r.pendentes)
        assertEquals(StatusOp.REJEITADO, store.ops["v1"]!!.status)
        assertTrue(store.ops["v1"]!!.ultimoErro!!.contains("sem estoque"))
        // reenvio manual volta a pendente
        sync.reenfileirarRejeitada("v1")
        assertEquals(StatusOp.PENDENTE, store.ops["v1"]!!.status); assertEquals(0, store.ops["v1"]!!.tentativas)
    }

    @Test fun duplicadoNoServidorContaComoConfirmado() = runBlocking {
        falhaPost = { MockResponse().setResponseCode(409).setBody("""{"exception":"frappe.exceptions.DuplicateEntryError: Duplicate entry"}""") }
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.confirmadas); assertEquals(StatusOp.CONFIRMADO, store.ops["v1"]!!.status)
    }

    @Test fun acrescimoSemContaConfiguradaEhRejeitadoSemEnviar() = runBlocking {
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z",
            """{"total":110,"itens":[{"item_code":"A","qtd":1,"preco":100}],"acrescimos":[{"tipo":"Frete","valor":10}]}"""))
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.rejeitadas); assertTrue(posts("POS Invoice").isEmpty())
        assertTrue(store.ops["v1"]!!.ultimoErro!!.contains("conta contábil"))
    }

    @Test fun aberturaVendaEFechamentoNoMesmoCicloEmOrdem() = runBlocking {
        store.salvarOp(op("cx1", TipoOp.ABERTURA, "2026-10-12T08:00:00Z", """{"aberto_em":"2026-10-12T08:00:00Z","troco_inicial":50}"""))
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        store.salvarOp(op("f1", TipoOp.FECHAMENTO, "2026-10-12T18:00:00Z", """{"caixa_op_id":"cx1","fechado_em":"2026-10-12T18:00:00Z"}"""))
        val r = sync.sincronizarFila(true)
        assertEquals(3, r.confirmadas)
        assertEquals("OPEN-1", store.ops["cx1"]!!.payload["ritmo_opening_name"]!!.jsonPrimitive.content)
        val fech = SynapseJson.parseToJsonElement(posts("POS Closing Entry").single().third).jsonObject
        assertEquals("OPEN-1", fech["pos_opening_entry"]!!.jsonPrimitive.content)
        assertEquals(1, (fech["pos_transactions"] as JsonArray).size)
        assertEquals("2026-10-12", fech["posting_date"]!!.jsonPrimitive.content)
    }

    @Test fun fechamentoSemAberturaSincronizadaEhRejeitado() = runBlocking {
        store.salvarOp(op("cx1", TipoOp.ABERTURA, "2026-10-12T08:00:00Z", """{"aberto_em":"x","troco_inicial":0}"""))   // ainda sem ritmo_opening_name
        store.salvarOp(op("f1", TipoOp.FECHAMENTO, "2026-10-12T18:00:00Z", """{"caixa_op_id":"zzz","fechado_em":"2026-10-12T18:00:00Z"}"""))
        val r = sync.sincronizarFila(true)
        assertEquals(StatusOp.REJEITADO, store.ops["f1"]!!.status); assertEquals(1, r.rejeitadas)
    }

    @Test fun semConexaoNaoTentaNada() = runBlocking {
        store.salvarOp(op("v1", TipoOp.VENDA, "2026-10-12T10:00:00Z", vendaComDesconto))
        val r = sync.sincronizarFila(online = false)
        assertTrue(r.semConexao); assertTrue(chamadas.isEmpty()); assertEquals(StatusOp.PENDENTE, store.ops["v1"]!!.status)
    }

    @Test fun pedidoDeVendaCriaClienteEOrderSemEstoque() = runBlocking {
        store.salvarOp(op("p1", TipoOp.PEDIDO_VENDA, "2026-10-12T10:00:00Z",
            """{"cliente":{"nome":"Maria","doc":"123.456.789-09"},"itens":[{"item_code":"A","qtd":2,"preco":10}]}"""))
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.confirmadas)
        val cli = SynapseJson.parseToJsonElement(posts("Customer").single().third).jsonObject
        assertEquals("12345678909", cli["tax_id"]!!.jsonPrimitive.content)
        assertEquals("Individual", cli["customer_type"]!!.jsonPrimitive.content)
        val so = SynapseJson.parseToJsonElement(posts("Sales Order").single().third).jsonObject
        assertEquals("CUST-1", so["customer"]!!.jsonPrimitive.content)
        assertNull(so["items"]!!.jsonArray[0].jsonObject["warehouse"])
    }

    @Test fun calculosAuxiliares() {
        assertEquals(90.0, valorItemComAjuste(parseObjeto("""{"preco":100,"qtd":1,"desconto":{"tipo":"percentual","valor":10}}""")), 0.0)
        assertEquals(115.0, valorItemComAjuste(parseObjeto("""{"preco":50,"qtd":2,"acrescimo":{"tipo":"valor","valor":15}}""")), 0.0)
        assertEquals(0.0, valorItemComAjuste(parseObjeto("""{"preco":10,"qtd":1,"desconto":{"tipo":"valor","valor":50}}""")), 0.0)
        assertEquals("12ABC", limparDocumento("12.abc-/"))
    }
}
