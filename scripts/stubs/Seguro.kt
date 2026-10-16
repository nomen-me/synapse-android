package me.nomen.synapse.data
import android.content.Context
// Stub do SecureStore real (que usa EncryptedSharedPreferences/Keystore).
class SecureStore(context: Context) {
    val armazenamento: ArmazenamentoChaveValor = object : ArmazenamentoChaveValor {
        override fun ler(chave: String): String? = null
        override fun gravar(chave: String, valor: String) {}
        override fun remover(chave: String) {}
    }
    var dominioBase: String? = null
}
