package me.nomen.synapse

import me.nomen.synapse.data.ArmazenamentoChaveValor
import me.nomen.synapse.data.PersistentCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ArmazenamentoEmMemoria : ArmazenamentoChaveValor {
    val mapa = HashMap<String, String>()
    override fun ler(chave: String) = mapa[chave]
    override fun gravar(chave: String, valor: String) { mapa[chave] = valor }
    override fun remover(chave: String) { mapa.remove(chave) }
}

class CookieJarTest {
    private val url = "https://ritmo.loja.com/api/method/login".toHttpUrl()
    private fun sid(valor: String, expira: Long = System.currentTimeMillis() + 3_600_000) =
        Cookie.Builder().name("sid").value(valor).domain("ritmo.loja.com").path("/").secure().httpOnly().expiresAt(expira).build()

    @Test fun sessaoSobreviveAoReiniciarOApp() {
        val disco = ArmazenamentoEmMemoria()
        PersistentCookieJar(disco).saveFromResponse(url, listOf(sid("abc")))
        val reaberto = PersistentCookieJar(disco)                       // "novo processo", mesmo armazenamento
        val enviados = reaberto.loadForRequest("https://ritmo.loja.com/api/resource/Item".toHttpUrl())
        assertEquals(1, enviados.size); assertEquals("abc", enviados[0].value)
        assertTrue(enviados[0].secure && enviados[0].httpOnly)
    }

    @Test fun cookieNaoVaiParaOutroHostNemEmHttp() {
        val jar = PersistentCookieJar(ArmazenamentoEmMemoria()).also { it.saveFromResponse(url, listOf(sid("abc"))) }
        assertTrue(jar.loadForRequest("https://harmonia.loja.com/webhook/x".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://ritmo.loja.com/api".toHttpUrl()).isEmpty())   // Secure: nunca em HTTP
    }

    @Test fun novoCookieDeMesmoNomeSubstituiOAntigo() {
        val jar = PersistentCookieJar(ArmazenamentoEmMemoria())
        jar.saveFromResponse(url, listOf(sid("velho"))); jar.saveFromResponse(url, listOf(sid("novo")))
        assertEquals(listOf("novo"), jar.loadForRequest(url).map { it.value })
    }

    @Test fun cookieExpiradoNaoEhEnviadoNemPersistido() {
        val disco = ArmazenamentoEmMemoria()
        val jar = PersistentCookieJar(disco)
        jar.saveFromResponse(url, listOf(sid("morto", expira = System.currentTimeMillis() - 1000)))
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertTrue(PersistentCookieJar(disco).loadForRequest(url).isEmpty())
    }

    @Test fun limparApagaTudoInclusiveDoArmazenamento() {
        val disco = ArmazenamentoEmMemoria()
        val jar = PersistentCookieJar(disco).also { it.saveFromResponse(url, listOf(sid("abc"))) }
        jar.limpar()
        assertTrue(jar.loadForRequest(url).isEmpty()); assertNull(disco.ler("cookies"))
        assertTrue(PersistentCookieJar(disco).loadForRequest(url).isEmpty())
    }

    @Test fun dadosCorrompidosNoArmazenamentoNaoDerrubamOApp() {
        val disco = ArmazenamentoEmMemoria().also { it.gravar("cookies", "{isto não é json") }
        assertTrue(PersistentCookieJar(disco).loadForRequest(url).isEmpty())
    }
}
