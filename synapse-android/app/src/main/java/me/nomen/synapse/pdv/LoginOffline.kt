package me.nomen.synapse.pdv

sealed interface ResultadoLoginOffline {
    class Ok(val operador: OperadorAutorizado, val autorizadoEm: String) : ResultadoLoginOffline
    class Erro(val mensagem: String) : ResultadoLoginOffline
}

/** Login offline por PIN (tentarLoginOffline do painel): usa só a autorização sincronizada no aparelho. */
class LoginOffline(private val config: PdvConfigRepository, private val lockout: PinLockout) {

    /** Operadores que podem entrar offline neste terminal (têm PIN definido). Vazio = nenhum. */
    suspend fun operadoresDisponiveis(): List<OperadorAutorizado> =
        config.autorizacaoLocal()?.takeIf { it.restricaoConfigurada }?.usuarios.orEmpty().filter { it.podeEntrarOffline }

    suspend fun diagnostico(): String? {
        val a = config.autorizacaoLocal() ?: return "Este terminal ainda não sincronizou a autorização do caixa — entre online uma vez para habilitar o modo offline."
        if (!a.restricaoConfigurada) return "O Ritmo não tem operadores configurados para este caixa (POS Profile User) — ninguém pode entrar offline."
        if (a.usuarios.none { it.podeEntrarOffline }) return "Nenhum operador tem PIN definido ainda — defina o PIN online."
        return null
    }

    suspend fun entrar(userId: String, pin: String): ResultadoLoginOffline {
        val aut = config.autorizacaoLocal() ?: return ResultadoLoginOffline.Erro(diagnostico() ?: "Sem autorização local")
        if (!aut.restricaoConfigurada) return ResultadoLoginOffline.Erro(diagnostico()!!)
        val op = aut.usuarios.firstOrNull { it.userId == userId } ?: return ResultadoLoginOffline.Erro("Operador não autorizado para este caixa.")
        if (!op.podeEntrarOffline) return ResultadoLoginOffline.Erro("Este operador ainda não definiu o PIN — defina online primeiro.")
        if (lockout.bloqueado(userId)) return ResultadoLoginOffline.Erro("Muitas tentativas — aguarde um minuto e tente de novo.")
        if (!PinCrypto.pinValido(pin)) return ResultadoLoginOffline.Erro("Digite os 6 dígitos do PIN.")
        return if (PinCrypto.verificar(pin, op.pinSalt!!, op.pinIteracoes!!, op.pinHash!!)) {
            lockout.registrarAcerto(userId); ResultadoLoginOffline.Ok(op, aut.sincronizadoEm)
        } else {
            lockout.registrarErro(userId); ResultadoLoginOffline.Erro("PIN incorreto.")
        }
    }
}
