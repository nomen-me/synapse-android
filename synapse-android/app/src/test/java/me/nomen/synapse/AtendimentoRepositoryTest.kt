package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import me.nomen.synapse.data.ApiException
import me.nomen.synapse.data.AtendimentoRepository
import me.nomen.synapse.data.AutorMensagem
import me.nomen.synapse.data.Canal
import me.nomen.synapse.data.HarmoniaClient
import me.nomen.synapse.data.criarHttpClient
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class AtendimentoRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: AtendimentoRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        repo = AtendimentoRepository(HarmoniaClient(criarHttpClient(CookieJar.NO_COOKIES)) { server.url("/").toString().trimEnd('/') })
    }

    @After fun tearDown() = server.shutdown()

    @Test fun mapeiaConversasConformeContrato() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"conversas":[
            {"id":123,"nome":"Maria","canal":"whatsapp","nao_lida":true,"status":"open","contato":"+5521","ultima_mensagem":"oi"},
            {"id":7,"nome":"Zé","canal":"outro","nao_lida":false,"status":"resolved"}]}"""))
        val l = repo.listarConversas()
        assertEquals("/webhook/atendimento/conversas", server.takeRequest().path)
        assertEquals(2, l.size)
        assertEquals(Canal.WHATSAPP, l[0].canal); assertTrue(l[0].aguardandoResposta)
        assertEquals(Canal.SITE, l[1].canal); assertFalse(l[1].aguardandoResposta)
    }

    @Test fun listaVaziaEhListaVazia() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"conversas":[]}"""))
        assertTrue(repo.listarConversas().isEmpty())
    }

    @Test fun erroDoHarmoniaNaoViraListaVazia() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"sucesso":false,"message":"Harpa indisponível"}"""))
        val e = assertFailsWith<ApiException> { repo.listarConversas() }
        assertEquals("Harpa indisponível", e.message)
        Unit
    }

    @Test fun detalheMapeiaAutoresEPedidos() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"mensagens":[{"autor":"cliente","hora":"2026-10-12T10:00:00Z","texto":"a"},
            {"autor":"sistema","hora":"2026-10-12T10:01:00Z","texto":"b"},{"autor":"agente","texto":"c"}],
            "cliente":{"nome":"Maria","cpf":null,"contato":"5521"},"ultimos_pedidos":[{"id":"SAL-ORD-1"}]}"""))
        val d = repo.detalhe(123)
        val corpo = server.takeRequest().body.readUtf8()
        assertEquals("""{"conversa_id":123}""", corpo)
        assertEquals(listOf(AutorMensagem.CLIENTE, AutorMensagem.LYRA, AutorMensagem.AGENTE), d.mensagens.map { it.autor })
        assertEquals(null, d.cliente.cpf)
        assertEquals(listOf("SAL-ORD-1"), d.pedidos)
    }

    @Test fun enviarUsaContratoDoWF03() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"sucesso":true,"id":99}"""))
        repo.enviar(123, "olá")
        val req = server.takeRequest()
        assertEquals("/webhook/atendimento/enviar", req.path)
        assertEquals("""{"conversa_id":123,"mensagem":"olá"}""", req.body.readUtf8())
    }
}
