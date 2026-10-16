package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nomen.synapse.data.ConfiguracaoException
import me.nomen.synapse.data.SynapseJson
import me.nomen.synapse.data.parseObjeto
import me.nomen.synapse.pdv.PdvConfigRepository
import me.nomen.synapse.pdv.PinCrypto
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class PdvAutorizacaoTest {
    private lateinit var srv: ServidorRitmoFalso
    private val store = StoreEmMemoria()
    private lateinit var config: PdvConfigRepository

    private val pacote = """{"message":{"pos_profile":"PDV1","sincronizado_em":"2026-10-12T08:00:00Z","versao":3,"restricao_configurada":true,
        "usuarios_autorizados":[{"user_id":"a@loja.com","nome_exibicao":"Ana","roles":["Caixa"],"employee":"EMP-1","pin_definido":false}]}}"""

    @Before fun setUp() {
        srv = ServidorRitmoFalso(); config = PdvConfigRepository(srv.ritmo(), store)
        store.cfg["deposito"] = parseObjeto("""{"chave":"deposito","warehouse":"W","pos_profile":"PDV1","company":"ML","currency":"BRL","selling_price_list":"P","modo_pagamento":"Dinheiro","conta_acrescimos":null}""")
    }

    @After fun tearDown() = srv.parar()

    @Test fun sincronizaAutorizacaoEGuardaLocalmente() = runBlocking {
        srv.metodos["synapse_sincronizar_caixa"] = MockResponse().setBody(pacote)
        val a = config.sincronizarAutorizacao()
        assertTrue(a.restricaoConfigurada); assertEquals(listOf("a@loja.com"), a.usuarios.map { it.userId })
        assertTrue(srv.chamadas.any { it.first == "GET" && it.second.endsWith("synapse_sincronizar_caixa") })
        assertNotNull(config.autorizacaoLocal())
        assertTrue(store.cfg["autorizacao_caixa"]!!.containsKey("sincronizado_localmente_em"))
    }

    @Test fun pacoteIncompletoNaoSobrescreveOEstadoLocalAnterior() = runBlocking {
        srv.metodos["synapse_sincronizar_caixa"] = MockResponse().setBody(pacote)
        config.sincronizarAutorizacao()
        srv.metodos["synapse_sincronizar_caixa"] = MockResponse().setBody("""{"message":{"pos_profile":"PDV1"}}""")
        assertFailsWith<ConfiguracaoException> { config.sincronizarAutorizacao() }
        assertEquals(1, config.autorizacaoLocal()!!.usuarios.size)                // a cópia boa foi mantida
    }

    @Test fun definirPinCalculaLocalEnviaSoHashESaltEAtualizaCopiaLocal() = runBlocking {
        srv.metodos["synapse_sincronizar_caixa"] = MockResponse().setBody(pacote)
        srv.metodos["synapse_definir_pin"] = MockResponse().setBody("""{"message":{"ok":true}}""")
        config.sincronizarAutorizacao()
        config.definirPinOnline("123456", "a@loja.com")
        val corpo = SynapseJson.parseToJsonElement(srv.chamadas.last { it.first == "POST" && it.second.endsWith("synapse_definir_pin") }.third).jsonObject
        assertFalse(srv.chamadas.any { it.third.contains("123456") })              // o PIN em si NUNCA vai ao servidor
        val iter = corpo["pin_kdf_iteracoes"]!!.jsonPrimitive.content.toInt()
        assertTrue(iter in 100_000..2_000_000)
        assertTrue(PinCrypto.verificar("123456", corpo["pin_salt"]!!.jsonPrimitive.content, iter, corpo["pin_hash"]!!.jsonPrimitive.content))
        val local = config.autorizacaoLocal()!!.usuarios.single()
        assertTrue(local.podeEntrarOffline)                                        // a cópia local já permite entrar offline
        assertTrue(PinCrypto.verificar("123456", local.pinSalt!!, local.pinIteracoes!!, local.pinHash!!))
    }

    @Test fun pinInvalidoNemChegaAoServidor() = runBlocking {
        assertFailsWith<IllegalArgumentException> { config.definirPinOnline("12345", "a@loja.com") }
        assertTrue(srv.chamadas.isEmpty()); Unit
    }

    @Test fun conectividadeRealDistingueRitmoNoArDeRedeSemResposta() = runBlocking {
        srv.metodos["synapse_csrf_token"] = MockResponse().setBody("""{"message":"tok"}""")
        assertTrue(srv.ritmo().ritmoAlcancavel())
        srv.metodos["synapse_csrf_token"] = MockResponse().setResponseCode(502)
        assertFalse(srv.ritmo().ritmoAlcancavel())
        srv.todasAsRespostas = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        assertFalse(srv.ritmo().ritmoAlcancavel())
        // uma única tentativa por chamada (sem retry): só os 2 pings que chegaram ao servidor com URL foram registrados
        assertEquals(2, srv.chamadas.count { it.second.endsWith("synapse_csrf_token") && it.first == "GET" })
    }
}
