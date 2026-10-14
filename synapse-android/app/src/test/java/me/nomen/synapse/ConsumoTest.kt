package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import me.nomen.synapse.data.ApiException
import me.nomen.synapse.data.ConsumoRepository
import me.nomen.synapse.data.ConsumoServicos
import me.nomen.synapse.data.HarmoniaClient
import me.nomen.synapse.data.criarHttpClient
import me.nomen.synapse.data.parseObjeto
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class ConsumoTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: ConsumoRepository
    private var respostaServicos: MockResponse = MockResponse().setBody("{}")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val corpo = request.body.snapshot().utf8()
                return when {
                    request.path == "/webhook/consumo/servicos" -> respostaServicos
                    corpo.contains("system.cpu") -> MockResponse().setBody("""{"data":[[1,37.6]]}""")
                    corpo.contains("system.ram") -> MockResponse().setBody("""{"data":[[1,3000,1000]]}""")
                    corpo.contains("disk_space") -> MockResponse().setBody("""{"data":[[1,45,55]]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        repo = ConsumoRepository(HarmoniaClient(criarHttpClient(CookieJar.NO_COOKIES)) { server.url("/").toString().trimEnd('/') })
    }

    @After fun tearDown() = server.shutdown()

    @Test fun calculaPercentuaisDoNetdata() = runBlocking {
        val u = repo.metricasVps()
        assertEquals(38, u.cpu); assertEquals(75, u.ram); assertEquals(45, u.disco)
    }

    @Test fun respostaAnomalaViraZero() {
        assertEquals(0, ConsumoRepository.percentualNetdata(parseObjeto("{}"), true))
        assertEquals(0, ConsumoRepository.percentualNetdata(parseObjeto("""{"data":[[1,0,0]]}"""), false))
    }

    @Test fun servicosIndisponiveisComo501SaoPendencia() = runBlocking {
        respostaServicos = MockResponse().setResponseCode(501).setBody("""{"sucesso":false,"status":"PENDENTE_FONTE_DE_DADOS","message":"PENDENTE_FONTE_DE_DADOS: ia, frete"}""")
        val r = repo.servicos()
        assertTrue(r is ConsumoServicos.Pendente)
    }

    @Test fun outroErroNaoEhConfundidoComPendencia() = runBlocking {
        respostaServicos = MockResponse().setResponseCode(500).setBody("""{"message":"PENDENTE_CONFIGURACAO_RITMO: variáveis ausentes"}""")
        assertFailsWith<ApiException> { repo.servicos() }
        Unit
    }

    @Test fun servicosDisponiveis() = runBlocking {
        respostaServicos = MockResponse().setBody("""{"ia":[{"d":"Seg","v":10}],"frete":[{"d":"S1","v":3}],"freteSaldo":"R$ 20,00","freteVolume":"12"}""")
        val r = repo.servicos() as ConsumoServicos.Disponivel
        assertEquals(10, r.ia[0].valor); assertEquals("R$ 20,00", r.freteSaldo)
    }
}
