package me.nomen.synapse.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

@Serializable
private class CookieSalvo(
    val name: String, val value: String, val expiresAt: Long, val domain: String,
    val path: String, val secure: Boolean, val httpOnly: Boolean, val hostOnly: Boolean
)

/** Armazenamento chave/valor mínimo (SharedPreferences cifrado no Android; mapa em memória nos testes JVM). */
interface ArmazenamentoChaveValor {
    fun ler(chave: String): String?
    fun gravar(chave: String, valor: String)
    fun remover(chave: String)
}

/**
 * Cookie jar que sobrevive ao fechamento do app (a sessão Frappe `sid` é o equivalente ao cookie
 * HttpOnly que o navegador guarda no painel web). Persistido em [SharedPreferences] criptografado.
 */
class PersistentCookieJar(private val armazenamento: ArmazenamentoChaveValor) : CookieJar {
    private val trava = Any()
    private val cookies = mutableListOf<Cookie>()

    init { carregar() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(trava) {
        val agora = System.currentTimeMillis()
        for (novo in cookies) {
            this.cookies.removeAll { it.name == novo.name && it.domain == novo.domain && it.path == novo.path }
            if (novo.expiresAt > agora) this.cookies.add(novo)
        }
        persistir()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(trava) {
        val agora = System.currentTimeMillis()
        if (cookies.removeAll { it.expiresAt <= agora }) persistir()
        cookies.filter { it.matches(url) }
    }

    fun limpar() = synchronized(trava) {
        cookies.clear()
        armazenamento.remover(CHAVE)
    }

    private fun persistir() {
        val salvos = cookies.map {
            CookieSalvo(it.name, it.value, it.expiresAt, it.domain, it.path, it.secure, it.httpOnly, it.hostOnly)
        }
        armazenamento.gravar(CHAVE, SynapseJson.encodeToString(ListSerializer(CookieSalvo.serializer()), salvos))
    }

    private fun carregar() {
        val bruto = armazenamento.ler(CHAVE) ?: return
        val salvos = runCatching {
            SynapseJson.decodeFromString(ListSerializer(CookieSalvo.serializer()), bruto)
        }.getOrDefault(emptyList())
        val agora = System.currentTimeMillis()
        salvos.filter { it.expiresAt > agora }.forEach { s ->
            cookies.add(
                Cookie.Builder().name(s.name).value(s.value).expiresAt(s.expiresAt).path(s.path)
                    .apply {
                        if (s.hostOnly) hostOnlyDomain(s.domain) else domain(s.domain)
                        if (s.secure) secure()
                        if (s.httpOnly) httpOnly()
                    }.build()
            )
        }
    }

    private companion object { const val CHAVE = "cookies" }
}
