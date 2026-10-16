package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nomen.synapse.data.ClasseStatus
import me.nomen.synapse.data.ConfigRepository
import me.nomen.synapse.data.ConfiguracaoException
import me.nomen.synapse.data.HarmoniaClient
import me.nomen.synapse.data.ItemNovoPedido
import me.nomen.synapse.data.PedidosRepository
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.SynapseJson
import me.nomen.synapse.data.TipoPedido
import me.nomen.synapse.data.criarHttpClient
import me.nomen.synapse.data.faturamento
import me.nomen.synapse.data.mapearStatusRitmo
import me.nomen.synapse.data.volumePorDia
import me.nomen.synapse.domain.Perfis
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertFailsWith

class PedidosTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: PedidosRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = criarHttpClient(CookieJar.NO_COOKIES)
        val base = { server.url("/").toString().trimEnd('/') }
        val ritmo = RitmoClient(http, base, esperaEntreTentativasMs = 0)
        repo = PedidosRepository(ritmo, HarmoniaClient(http, base), ConfigRepository(ritmo))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun statusDoRitmoViraClasseERotulo() {
        assertEquals(ClasseStatus.PAGO, mapearStatusRitmo("Completed").classe)
        assertEquals("A entregar e faturar", mapearStatusRitmo("To Deliver and Bill").rotulo)
        assertEquals(ClasseStatus.CANCELADO, mapearStatusRitmo("Cancelled").classe)
        val desconhecido = mapearStatusRitmo("Xyz")
        assertEquals(ClasseStatus.AGUARDA, desconhecido.classe); assertEquals("Xyz", desconhecido.rotulo)
    }

    @Test fun listaPedidosDeVenda() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[
          {"name":"SAL-ORD-1","customer_name":"Maria","grand_total":150.5,"status":"To Deliver and Bill","docstatus":1,"transaction_date":"2026-10-12"}]}"""))
        val l = repo.listar(TipoPedido.VENDA)
        assertEquals("/api/resource/Sales%20Order", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(1, l.size); assertEquals("Maria", l[0].parte); assertEquals(150.5, l[0].valor, 0.0); assertEquals(1, l[0].docstatus)
    }

    @Test fun cancelamentoVaiParaALyraENaoParaOFrappe() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"resumo":"Em andamento"}"""))
        val r = repo.solicitarCancelamento("SAL-ORD-1", "Ana")
        val req = server.takeRequest()
        assertEquals("/webhook/lyra/alterar", req.path)
        val j = SynapseJson.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("pedidos", j["tipo"]!!.jsonPrimitive.content)
        assertEquals("SAL-ORD-1", j["id"]!!.jsonPrimitive.content)
        assertEquals("Cancelar este pedido, solicitado por Ana.", j["instrucao"]!!.jsonPrimitive.content)
        assertEquals("Em andamento", r)
    }

    @Test fun criarVendaUsaCompanyDoPosProfile() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"name":"PDV1","warehouse":"Loja","company":"Minha Loja","currency":"BRL","selling_price_list":"Padrão"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"name":"SAL-ORD-9"}}"""))
        val nome = repo.criarVenda("CUST-1", listOf(ItemNovoPedido("I-1", "Item", 2.0, 10.0)))
        assertEquals("SAL-ORD-9", nome)
        server.takeRequest()
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        val j = SynapseJson.parseToJsonElement(post.body.readUtf8()).jsonObject
        assertEquals("Minha Loja", j["company"]!!.jsonPrimitive.content)
        assertEquals("BRL", j["currency"]!!.jsonPrimitive.content)
        assertEquals(1, j["items"]!!.jsonArray.size)
    }

    @Test fun companyDuplicadaNaoEhEscolhidaSozinha() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"name":"A"},{"name":"B"}]}"""))
        assertFailsWith<ConfiguracaoException> { ConfigRepository(RitmoClient(criarHttpClient(CookieJar.NO_COOKIES), { server.url("/").toString().trimEnd('/') }, esperaEntreTentativasMs = 0)).companyUnica() }
        Unit
    }

    @Test fun faturamentoEVolume() {
        fun p(v: Double, s: String, d: String) = me.nomen.synapse.data.Pedido("x", TipoPedido.VENDA, "c", v, mapearStatusRitmo(s), 1, d)
        val hoje = LocalDate.of(2026, 10, 12)
        val lista = listOf(p(100.0, "Completed", "2026-10-12"), p(50.0, "To Bill", "2026-10-11"), p(999.0, "Cancelled", "2026-10-12"), p(10.0, "Draft", "2026-09-01"))
        assertEquals(150.0, faturamento(lista), 0.0)
        val v = volumePorDia(lista, 7, hoje)
        assertEquals(7, v.size); assertEquals(2, v.last().qtd); assertEquals(1, v[5].qtd)
    }

    @Test fun cancelarVendaSoLojistaEAdmin() {
        assertTrue(Perfis.acaoPermitida("Lojista", "cancelar_venda"))
        assertTrue(Perfis.acaoPermitida("Synapse Administrador", "cancelar_venda"))
        assertFalse(Perfis.acaoPermitida("Gerente", "cancelar_venda"))
        assertTrue(Perfis.acaoPermitida("Gerente", "acao_livre"))
    }
}
