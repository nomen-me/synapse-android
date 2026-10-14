package me.nomen.synapse.data

/** Sessão do Ritmo inexistente/expirada (HTTP 401/403 em chamada autenticada). */
class SessaoInvalidaException : Exception("Sessão expirada ou sem permissão. Entre novamente.")

/** Falha de rede: sem internet, DNS, timeout, TLS. */
class SemConexaoException(causa: Throwable? = null) :
    Exception("Sem conexão com o servidor. Verifique a internet e tente de novo.", causa)

/** Resposta de erro do servidor (Ritmo ou Harmonia). [message] já é legível para o usuário. */
class ApiException(val status: Int, mensagem: String) : Exception(mensagem)

class CredenciaisInvalidasException : Exception("E-mail ou senha incorretos.")

class UsuarioSemPapelException : Exception(
    "Usuário sem perfil Synapse reconhecido no Ritmo (Synapse Administrador, Lojista, Gerente, " +
        "Sub gerente, Operador de loja, Vendedor, Caixa ou Administração)."
)
