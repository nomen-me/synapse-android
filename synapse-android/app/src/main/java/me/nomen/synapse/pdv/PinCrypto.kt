package me.nomen.synapse.pdv

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN offline do Caixa. Mesmo esquema do painel web (PBKDF2-HMAC-SHA256, 256 bits, hash em hex minúsculo,
 * salt em Base64 padrão) — um PIN definido no painel valida no app e vice-versa. O servidor só guarda/devolve
 * o que o cliente calculou; ele nunca deriva o hash.
 */
object PinCrypto {
    private val SEIS_DIGITOS = Regex("^\\d{6}$")

    fun pinValido(pin: String) = SEIS_DIGITOS.matches(pin)

    fun gerarSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun paraBase64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun deBase64(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun derivarHashHex(pin: String, salt: ByteArray, iteracoes: Int): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iteracoes, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return bytes.joinToString("") { "%02x".format(it) }
        } finally { spec.clearPassword() }
    }

    /** Calibra as iterações para ~[alvoMs] ms neste aparelho, limitado a 100 mil–2 milhões (igual ao painel). */
    fun calibrarIteracoes(alvoMs: Long = 300): Int {
        val base = 50_000
        val t0 = System.nanoTime()
        derivarHashHex("000000", gerarSalt(), base)
        val msPorIteracao = ((System.nanoTime() - t0) / 1_000_000.0) / base
        val alvo = if (msPorIteracao <= 0.0) 2_000_000 else Math.round(alvoMs / msPorIteracao).toInt().coerceAtLeast(0)
        return alvo.coerceIn(100_000, 2_000_000)
    }

    /** Comparação em tempo constante. */
    fun verificar(pin: String, saltBase64: String, iteracoes: Int, hashEsperadoHex: String): Boolean {
        val calc = derivarHashHex(pin, deBase64(saltBase64), iteracoes)
        return MessageDigest.isEqual(calc.toByteArray(), hashEsperadoHex.lowercase().toByteArray())
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** 5 erros seguidos bloqueiam aquele operador por 60 s (pdvTentativasPinPorOperador do painel). Em memória, por terminal. */
class PinLockout(private val agoraMs: () -> Long = System::currentTimeMillis) {
    private class Estado(var erros: Int = 0, var bloqueadoAte: Long = 0)
    private val estados = HashMap<String, Estado>()

    @Synchronized fun bloqueado(userId: String): Boolean = agoraMs() < (estados[userId]?.bloqueadoAte ?: 0)

    @Synchronized fun registrarErro(userId: String) {
        val e = estados.getOrPut(userId) { Estado() }
        e.erros++
        if (e.erros >= 5) { e.bloqueadoAte = agoraMs() + 60_000; e.erros = 0 }
    }

    @Synchronized fun registrarAcerto(userId: String) { estados.remove(userId) }
}
