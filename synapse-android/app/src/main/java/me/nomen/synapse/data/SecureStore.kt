package me.nomen.synapse.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Armazenamento criptografado (Android Keystore) do domínio da loja e dos cookies de sessão. */
class SecureStore(context: Context) {
    val prefs: SharedPreferences = abrirComRecuperacao(context)

    /** Adaptador para o cookie jar. */
    val armazenamento: ArmazenamentoChaveValor = object : ArmazenamentoChaveValor {
        override fun ler(chave: String) = prefs.getString(chave, null)
        override fun gravar(chave: String, valor: String) { prefs.edit().putString(chave, valor).apply() }
        override fun remover(chave: String) { prefs.edit().remove(chave).apply() }
    }

    var dominioBase: String?
        get() = prefs.getString(CHAVE_DOMINIO, null)
        set(valor) {
            prefs.edit().apply {
                if (valor == null) remove(CHAVE_DOMINIO) else putString(CHAVE_DOMINIO, valor)
            }.apply()
        }

    private companion object {
        const val CHAVE_DOMINIO = "dominio_base"
        const val ARQUIVO = "synapse_secure"

        private fun abrir(context: Context): SharedPreferences {
            val chave = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, ARQUIVO, chave,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /**
         * Falha conhecida do Keystore (restauração de aparelho, chave invalidada, atualização do SO): o arquivo
         * cifrado fica ilegível e o app travaria na abertura. Aqui só há domínio e cookies de sessão (refazem-se
         * entrando de novo); a fila do PDV vive em outro banco e NÃO é tocada.
         */
        private fun abrirComRecuperacao(context: Context): SharedPreferences = try {
            abrir(context)
        } catch (e: Exception) {
            context.deleteSharedPreferences(ARQUIVO)
            abrir(context)
        }
    }
}
