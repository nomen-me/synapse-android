package me.nomen.synapse.data

/**
 * Ambiente de uma loja. Mesmo padrão do painel web (resolverAmbienteSynapse): a partir do domínio
 * base derivam-se ritmo./harmonia./harpa./acorde./eco. O app só precisa do Ritmo e do Harmonia.
 * Tudo por HTTPS.
 */
data class SynapseEnv(val dominioBase: String, val ritmo: String, val harmonia: String) {
    companion object {
        private val DOMINIO = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

        fun deDominio(dominioBase: String) = SynapseEnv(
            dominioBase = dominioBase,
            ritmo = "https://ritmo.$dominioBase",
            harmonia = "https://harmonia.$dominioBase"
        )

        /**
         * Normaliza o que o usuário digitou: aceita "minhaloja.com.br", "https://painel.minhaloja.com.br/"
         * etc. Rejeita http:// (somente HTTPS), IP/porta e valores inválidos. Retorna null se inválido.
         */
        fun normalizar(entrada: String): String? {
            var d = entrada.trim().lowercase()
            if (d.startsWith("http://")) return null
            d = d.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
            d = d.removePrefix("painel.").removePrefix("ritmo.").removePrefix("harmonia.")
            return d.takeIf { DOMINIO.matches(it) }
        }
    }
}
