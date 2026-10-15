package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.nomen.synapse.data.ApiException
import me.nomen.synapse.data.CredenciaisInvalidasException
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.SemConexaoException
import me.nomen.synapse.data.SessaoInvalidaException
import me.nomen.synapse.data.criarHttpClient
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class RitmoClientTest {
    private lateinit var server: MockWebServer
    private var suspeitas = 0
    private lateinit var ritmo: RitmoClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ritmo = RitmoClient(
            criarHttpClient(CookieJar.NO_COOKIES), { server.url("/").toString().trimEnd('/') },
            aoPossivelSessaoInvalida = { suspeitas++ }, esperaEntreTentativasMs = 0
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun loginOkEnviaFormulario() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":"Logged In","full_name":"Ana"}"""))
        ritmo.login("ana@loja.com", "s3nha")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/method/login", req.path)
        assertEquals("usr=ana%40loja.com&pwd=s3nha", req.body.readUtf8())
    }

    @Test fun loginComSenhaErradaLancaCredenciaisInvalidas() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Incorrect password"}"""))
        assertFailsWith<CredenciaisInvalidasException> { ritmo.login("a@b.com", "x") }
        Unit
    }

    @Test fun loginComErro500NaoViraCredenciaisInvalidas() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        assertFailsWith<ApiException> { ritmo.login("a@b.com", "x") }
        Unit
    }

    @Test fun getTentaTresVezesEmFalhaDeRede() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        server.enqueue(MockResponse().setBody("""{"message":"ana@loja.com"}"""))
        assertEquals("ana@loja.com", ritmo.usuarioLogado())
        assertEquals(3, server.requestCount)
    }

    @Test fun postNuncaEhRepetido() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("{}"))
        assertFailsWith<SemConexaoException> { ritmo.chamarMetodo("synapse_definir_pin", JsonObject(emptyMap())) }
        assertEquals(1, server.requestCount)
    }

    @Test fun csrfSoVaiEmEscrita() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":"tok123"}"""))
        ritmo.buscarCsrfToken()
        server.takeRequest()
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("{}"))
        ritmo.chamarMetodo("x", JsonObject(mapOf("a" to JsonPrimitive(1))))
        ritmo.obter("Item", "I-1")
        assertEquals("tok123", server.takeRequest().getHeader("X-Frappe-CSRF-Token"))
        assertNull(server.takeRequest().getHeader("X-Frappe-CSRF-Token"))
    }

    @Test fun visitanteGuestViraNulo() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":"Guest"}"""))
        assertNull(ritmo.usuarioLogado())
    }

    @Test fun http403SinalizaSessaoSuspeitaELancaSessaoInvalida() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        assertFailsWith<SessaoInvalidaException> { ritmo.obter("Item", "I-1") }
        assertEquals(1, suspeitas)
    }

    @Test fun papeisDoUsuario() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"full_name":"Ana Souza","roles":[{"role":"Guest"},{"role":"Gerente"}]}}"""))
        val p = ritmo.papeisDoUsuario("ana@loja.com")
        assertEquals("Ana Souza", p.nome)
        assertEquals(listOf("Guest", "Gerente"), p.papeis)
        val req = server.takeRequest()
        assertEquals(listOf("api", "resource", "User", "ana@loja.com"), req.requestUrl!!.pathSegments)
        assertTrue(req.requestUrl!!.queryParameter("fields")!!.contains("roles"))
    }
}
