package me.nomen.synapse.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Armazenamento criptografado (Android Keystore) do domínio da loja e dos cookies de sessão. */
class SecureStore(context: Context) {
    val prefs: SharedPreferences = run {
        val chave = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "synapse_secure", chave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var dominioBase: String?
        get() = prefs.getString(CHAVE_DOMINIO, null)
        set(valor) {
            prefs.edit().apply {
                if (valor == null) remove(CHAVE_DOMINIO) else putString(CHAVE_DOMINIO, valor)
            }.apply()
        }

    private companion object { const val CHAVE_DOMINIO = "dominio_base" }
}
