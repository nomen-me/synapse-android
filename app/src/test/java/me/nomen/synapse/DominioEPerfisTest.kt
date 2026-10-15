package me.nomen.synapse

import me.nomen.synapse.data.SynapseEnv
import me.nomen.synapse.data.mensagemDeErro
import me.nomen.synapse.data.parseObjeto
import me.nomen.synapse.domain.Aba
import me.nomen.synapse.domain.Perfis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DominioEPerfisTest {
    @Test fun normalizaDominio() {
        assertEquals("minhaloja.com.br", SynapseEnv.normalizar("  MinhaLoja.com.br "))
        assertEquals("nomen.me", SynapseEnv.normalizar("https://painel.nomen.me/"))
        assertEquals("nomen.me", SynapseEnv.normalizar("ritmo.nomen.me"))
    }

    @Test fun rejeitaDominioInseguroOuInvalido() {
        assertNull(SynapseEnv.normalizar("http://nomen.me"))
        assertNull(SynapseEnv.normalizar("nomen"))
        assertNull(SynapseEnv.normalizar("192.168.0.1:8080"))
        assertNull(SynapseEnv.normalizar(""))
    }

    @Test fun derivaSubdominios() {
        val e = SynapseEnv.deDominio("nomen.me")
        assertEquals("https://ritmo.nomen.me", e.ritmo)
        assertEquals("https://harmonia.nomen.me", e.harmonia)
    }

    @Test fun identificaPrimeiroPapelSynapse() {
        assertEquals("Vendedor", Perfis.identificar(listOf("Guest", "Vendedor", "Gerente")))
        assertNull(Perfis.identificar(listOf("Guest", "System Manager")))
    }

    @Test fun administradorVeTudo() {
        assertEquals(Aba.entries.toList(), Perfis.abasPermitidas(Perfis.ADMINISTRADOR))
    }

    @Test fun caixaSoVeSuasAbasMaisProducaoEUnidades() {
        assertEquals(
            listOf(Aba.DASHBOARD, Aba.PDV, Aba.PRODUCAO, Aba.UNIDADES, Aba.TEMA),
            Perfis.abasPermitidas("Caixa")
        )
    }

    @Test fun vendedorNaoVeFiscalNemIntegracoes() {
        val abas = Perfis.abasPermitidas("Vendedor")
        assertFalse(Aba.FISCAL in abas); assertFalse(Aba.INTEGRACOES in abas); assertFalse(Aba.PDV in abas)
        assertTrue(Aba.ATENDIMENTO in abas)
    }

    @Test fun filiaisSoParaLojistaEAdmin() {
        assertTrue(Perfis.subAbaCadastroPermitida("Lojista", "filiais"))
        assertTrue(Perfis.subAbaCadastroPermitida(Perfis.ADMINISTRADOR, "filiais"))
        assertFalse(Perfis.subAbaCadastroPermitida("Gerente", "filiais"))
        assertTrue(Perfis.subAbaCadastroPermitida("Gerente", "clientes"))
    }

    @Test fun extraiMensagemDeErroDoFrappeEDoHarmonia() {
        val frappe = parseObjeto("""{"_server_messages":"[\"{\\\"message\\\": \\\"<b>Sem permissão</b> para Item\\\"}\"]"}""")
        assertEquals("Sem permissão para Item", mensagemDeErro(frappe))
        assertEquals("Harpa indisponível", mensagemDeErro(parseObjeto("""{"sucesso":false,"message":"Harpa indisponível"}""")))
        assertNull(mensagemDeErro(parseObjeto("{}")))
    }
}
