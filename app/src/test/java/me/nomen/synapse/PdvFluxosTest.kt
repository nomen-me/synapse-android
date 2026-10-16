package me.nomen.synapse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nomen.synapse.data.SynapseJson
import me.nomen.synapse.data.parseObjeto
import me.nomen.synapse.pdv.AcrescimoVenda
import me.nomen.synapse.pdv.Ajuste
import me.nomen.synapse.pdv.Carrinho
import me.nomen.synapse.pdv.ContextoVenda
import me.nomen.synapse.pdv.LoginOffline
import me.nomen.synapse.pdv.OperadorPdv
import me.nomen.synapse.pdv.PdvCaixaService
import me.nomen.synapse.pdv.PdvCatalogoRepository
import me.nomen.synapse.pdv.PdvConfigRepository
import me.nomen.synapse.pdv.PdvSincronizador
import me.nomen.synapse.pdv.PinCrypto
import me.nomen.synapse.pdv.PinLockout
import me.nomen.synapse.pdv.ProdutoPdv
import me.nomen.synapse.pdv.ResultadoLoginOffline
import me.nomen.synapse.pdv.StatusOp
import me.nomen.synapse.pdv.TipoOp
import me.nomen.synapse.pdv.ehPesavel
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

private fun prod(code: String = "A", preco: Double = 100.0, estoque: Double? = 5.0, uom: String? = "Unidade") =
    ProdutoPdv(code, "Produto $code", preco, estoque, null, null, uom, null)

class CarrinhoTest {
    @Test fun somaItensDescontoGeralEAcrescimo() {
        var c = Carrinho().adicionar(prod()).carrinho.adicionar(prod()).carrinho
        assertEquals(200.0, c.total(), 0.0)
        c = c.comDescontoGeral(Ajuste("percentual", 10.0)).adicionarAcrescimo(AcrescimoVenda("Frete", null, 10.0))
        assertEquals(190.0, c.total(), 0.0)           // 200 - 20 + 10
        assertEquals(0.0, c.comDescontoGeral(Ajuste("valor", 9999.0)).total(), 0.0)   // nunca negativo
    }

    @Test fun descontoEAcrescimoPorItem() {
        val c0 = Carrinho().adicionar(prod()).carrinho
        val k = c0.itens[0].chave
        assertEquals(80.0, c0.comDescontoItem(k, Ajuste("valor", 20.0)).total(), 0.0)
        assertEquals(110.0, c0.comAcrescimoItem(k, Ajuste("percentual", 10.0)).total(), 0.0)
    }

    @Test fun estoqueLocalLimitaQuantidade() {
        var r = Carrinho().adicionar(prod(estoque = 2.0), 2.0)
        assertNull(r.erro)
        r = r.carrinho.adicionar(prod(estoque = 2.0))
        assertNotNull(r.erro); assertEquals(2.0, r.carrinho.itens[0].qtd, 0.0)
        assertNotNull(r.carrinho.alterarQtd(r.carrinho.itens[0].chave, 1.0, 2.0).erro)
    }

    @Test fun produtoSemEstoqueConhecidoNaoBloqueia() { assertNull(Carrinho().adicionar(prod(estoque = null), 50.0).erro) }

    @Test fun zerarQuantidadeRemoveALinha() {
        val c = Carrinho().adicionar(prod()).carrinho.adicionar(prod()).carrinho
        assertTrue(c.alterarQtd(c.itens[0].chave, -2.0, null).carrinho.vazio)
    }

    @Test fun linhaPesavelNaoUsaMaisMenos() {
        val c = Carrinho().adicionar(prod(uom = "Kg"), 0.350).carrinho
        assertTrue(c.itens[0].pesavel); assertNotNull(c.alterarQtd(c.itens[0].chave, 1.0, null).erro)
        assertEquals(35.0, c.total(), 1e-9)
        assertEquals(1.0, c.quantidadeDeItens, 0.0)
    }

    @Test fun precoLivreGuardaOriginalEQuemAutorizou() {
        val c = Carrinho().adicionar(prod()).carrinho
        val p = c.comPrecoLivre(c.itens[0].chave, 80.0, "gerente@loja.com", "cliente fiel")
        assertEquals(100.0, p.itens[0].precoOriginal!!, 0.0); assertEquals(80.0, p.total(), 0.0)
        assertEquals("gerente@loja.com", p.itens[0].precoAutorizadoPor)
    }

    @Test fun preenchimentosDiferentesViramLinhasDiferentes() {
        val a = Carrinho().adicionar(prod(), 1.0, parseObjeto("""{"g1":"x"}""")).carrinho.adicionar(prod(), 1.0, parseObjeto("""{"g1":"y"}""")).carrinho
        assertEquals(2, a.itens.size)
    }

    @Test fun unidadesPesaveisSemAcento() { assertTrue(ehPesavel("Quilogramas")); assertTrue(ehPesavel(" KG ")); assertFalse(ehPesavel("Unidade")); assertFalse(ehPesavel(null)) }
}

class PdvFluxoCompletoTest {
    private lateinit var srv: ServidorRitmoFalso
    private val store = StoreEmMemoria()
    private lateinit var sync: PdvSincronizador
    private lateinit var catalogo: PdvCatalogoRepository
    private lateinit var caixa: PdvCaixaService
    private var relogio = 0
    private val operador = OperadorPdv("caixa@loja.com", "Caixa", null, "online")
    private val ctx = ContextoVenda()

    @Before fun setUp() {
        srv = ServidorRitmoFalso()
        val ritmo = srv.ritmo()
        store.cfg["deposito"] = parseObjeto("""{"chave":"deposito","warehouse":"Loja - ML","pos_profile":"PDV1","company":"ML","currency":"BRL","selling_price_list":"Padrão","modo_pagamento":"Dinheiro","conta_acrescimos":null}""")
        val config = PdvConfigRepository(ritmo, store)
        sync = PdvSincronizador(ritmo, config, store)
        catalogo = PdvCatalogoRepository(ritmo, config, store)
        var n = 0
        caixa = PdvCaixaService(store, sync, catalogo, agora = { "2026-10-12T%02d:00:00.000Z".format(8 + relogio++) }, novoUuid = { "u${n++}" })
        runBlocking { store.salvarConfig("catalogo", parseObjeto("""{"itens":[{"item_code":"A","nome":"Produto A","preco":100,"estoque":5,"uom":"Unidade"}]}""")) }
    }

    @After fun tearDown() = srv.parar()

    private fun carrinhoCom(qtd: Double = 1.0) = Carrinho().adicionar(prod(), qtd).carrinho

    @Test fun naoVendeSemCaixaAbertoNemComCarrinhoVazio() = runBlocking {
        assertFailsWith<IllegalStateException> { caixa.finalizarVenda(carrinhoCom(), ctx, null, operador) }
        caixa.abrir(50.0, operador)
        assertFailsWith<IllegalArgumentException> { caixa.finalizarVenda(Carrinho(), ctx, null, operador) }
        assertFailsWith<IllegalStateException> { caixa.abrir(10.0, operador) }   // já aberto
        Unit
    }

    @Test fun diaCompletoOfflineDepoisSincroniza() = runBlocking {
        // --- OFFLINE: abrir, 2 vendas, fechar. Nada vai ao Ritmo; tudo fica pendente e o estoque local baixa.
        caixa.abrir(50.0, operador)
        caixa.finalizarVenda(carrinhoCom(2.0), ctx, parseObjeto("""{"metodo":"dinheiro"}"""), operador)
        caixa.finalizarVenda(carrinhoCom(1.0), ctx, null, operador)
        caixa.fechar(operador)
        assertNull(caixa.caixaAberto())
        val offline = sync.sincronizarFila(online = false)
        assertTrue(offline.semConexao); assertTrue(srv.chamadas.isEmpty())
        assertEquals(4, store.ops.values.count { it.status == StatusOp.PENDENTE })
        assertEquals(2.0, catalogo.local()[0].estoque!!, 0.0)            // 5 - 2 - 1

        // --- ONLINE: a fila sai em ordem, o fechamento referencia a abertura e as 2 vendas.
        val r = sync.sincronizarFila(online = true)
        assertEquals(4, r.confirmadas); assertEquals(0, r.pendentes)
        assertEquals(1, srv.posts("POS Opening Entry").size); assertEquals(2, srv.posts("POS Invoice").size); assertEquals(1, srv.posts("POS Closing Entry").size)
        val fech = SynapseJson.parseToJsonElement(srv.posts("POS Closing Entry").single().third).jsonObject
        assertEquals(2, (fech["pos_transactions"] as JsonArray).size)

        // --- Nova sincronização não reenvia nada (sem duplicidade).
        val antes = srv.posts("POS Invoice").size
        sync.sincronizarFila(online = true)
        assertEquals(antes, srv.posts("POS Invoice").size)
    }

    @Test fun cliqueDuploNoFinalizarNaoDuplicaVendaNemBaixaEstoqueDeNovo() = runBlocking {
        caixa.abrir(0.0, operador)
        val id = caixa.gerarId("venda")
        val a = caixa.finalizarVenda(carrinhoCom(2.0), ctx, null, operador, id)
        val b = caixa.finalizarVenda(carrinhoCom(2.0), ctx, null, operador, id)
        assertEquals(a.opId, b.opId)
        assertEquals(1, store.ops.values.count { it.tipo == TipoOp.VENDA })
        assertEquals(3.0, catalogo.local()[0].estoque!!, 0.0)             // baixou uma única vez
    }

    @Test fun quedaDoAppNoMeioDoEnvioReenviaComOMesmoIdSemDuplicar() = runBlocking {
        caixa.abrir(0.0, operador)
        val v = caixa.finalizarVenda(carrinhoCom(), ctx, null, operador)
        sync.sincronizarFila(true)                                       // abertura + venda confirmadas
        // Simula: o Ritmo já tem a venda, mas o app morreu antes de gravar "confirmado" (ficou "enviando").
        store.ops[v.opId] = store.ops[v.opId]!!.copy(status = StatusOp.ENVIANDO)
        val postsAntes = srv.posts("POS Invoice").size
        srv.existentes = mapOf("POS Invoice" to "INV-1")
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.confirmadas)
        assertEquals(postsAntes, srv.posts("POS Invoice").size)           // consultou por synapse_op_id e NÃO criou outra
        assertEquals(StatusOp.CONFIRMADO, store.ops[v.opId]!!.status)
    }

    @Test fun reenvioDeRejeitadaDepoisDeCorrigirOProblema() = runBlocking {
        caixa.abrir(0.0, operador)
        val v = caixa.finalizarVenda(carrinhoCom(), ctx, null, operador)
        srv.falhaPost = { d -> if (d == "POS Invoice") MockResponse().setResponseCode(417).setBody("""{"message":"Sem estoque"}""") else null }
        val r1 = sync.sincronizarFila(true)
        assertEquals(1, r1.rejeitadas); assertEquals(StatusOp.REJEITADO, store.ops[v.opId]!!.status)
        assertEquals(0, sync.sincronizarFila(true).tentadas - 0)         // rejeitada NÃO é reenviada sozinha
        srv.falhaPost = null
        sync.reenfileirarRejeitada(v.opId)
        val r2 = sync.sincronizarFila(true)
        assertEquals(1, r2.confirmadas); assertEquals(StatusOp.CONFIRMADO, store.ops[v.opId]!!.status)
    }

    @Test fun redeQuebraNoMeioDaFilaMantemOrdemSemPerderNada() = runBlocking {
        caixa.abrir(0.0, operador)
        caixa.finalizarVenda(carrinhoCom(1.0), ctx, null, operador)
        srv.todasAsRespostas = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
        val r1 = sync.sincronizarFila(true)
        assertEquals(0, r1.confirmadas); assertEquals(2, r1.pendentes)
        assertTrue(store.ops.values.all { it.status == StatusOp.PENDENTE && it.tentativas == 1 })
        srv.todasAsRespostas = null
        val r2 = sync.sincronizarFila(true)
        assertEquals(2, r2.confirmadas); assertEquals(0, r2.pendentes)
    }

    @Test fun pedidoDeVendaExigeClienteEVaiComoSalesOrder() = runBlocking {
        assertFailsWith<IllegalArgumentException> { caixa.finalizarPedido(carrinhoCom(), ctx, null, null, operador) }
        val cli = parseObjeto("""{"nome":"Maria","doc":"123.456.789-09"}""")
        caixa.finalizarPedido(carrinhoCom(), ContextoVenda(cliente = cli), "2026-10-20", " entregar à tarde ", operador)
        val r = sync.sincronizarFila(true)
        assertEquals(1, r.confirmadas); assertEquals(1, srv.posts("Sales Order").size); assertTrue(srv.posts("POS Invoice").isEmpty())
        val so = SynapseJson.parseToJsonElement(srv.posts("Sales Order").single().third).jsonObject
        assertEquals("2026-10-20", so["delivery_date"]!!.jsonPrimitive.content)
    }

    @Test fun payloadDaVendaTemAsChavesDoPainel() = runBlocking {
        caixa.abrir(0.0, operador)
        val c = carrinhoCom().comDescontoGeral(Ajuste("percentual", 10.0)).adicionarAcrescimo(AcrescimoVenda("Frete", "moto", 5.0))
        val v = caixa.finalizarVenda(c, ContextoVenda(identificacao = parseObjeto("""{"tipo":"Mesa","valor":"7"}""")), null, operador)
        val chaves = v.payload.keys
        assertTrue(chaves.containsAll(listOf("op_id", "criado_em", "caixa_op_id", "cliente", "vendedor", "identificacao", "desconto_geral", "acrescimos", "itens", "total", "pagamento", "nfce")))
        assertEquals(95.0, v.payload["total"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("registrada", v.payload["nfce"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("caixa@loja.com", v.operador!!["user_id"]!!.jsonPrimitive.content)
        assertTrue(v.payload["itens"]!!.jsonArray[0].jsonObject.keys.containsAll(listOf("item_code", "nome", "qtd", "preco", "preco_original", "item_generico")))
    }

    // ---- catálogo ----
    @Test fun catalogoSincronizaItemsEBinEDetectaDivergencia() = runBlocking {
        srv.listas["Item"] = { _ -> """{"data":[{"item_code":"A","item_name":"Produto A","standard_rate":120,"stock_uom":"Unidade"},{"item_code":"K","item_name":"Café","standard_rate":30,"stock_uom":"Kg"}]}""" }
        srv.listas["Bin"] = { _ -> """{"data":[{"item_code":"A","actual_qty":3},{"item_code":"K","actual_qty":10}]}""" }
        val r = catalogo.sincronizar(online = true)
        assertTrue(r.atualizadoOnline)
        assertEquals(2, r.produtos.size)
        assertEquals(120.0, r.produtos.first { it.itemCode == "A" }.preco, 0.0)
        assertEquals(3.0, r.produtos.first { it.itemCode == "A" }.estoque!!, 0.0)
        assertEquals(1, r.divergencias.size)                                  // local 5 x Ritmo 3, sem venda pendente
        assertEquals(2, catalogo.local().size)                                // persistiu
    }

    @Test fun divergenciaNaoAlertaQuandoHaVendaPendenteDoItem() = runBlocking {
        caixa.abrir(0.0, operador)
        caixa.finalizarVenda(carrinhoCom(2.0), ctx, null, operador)           // local 5 -> 3, venda pendente
        srv.listas["Item"] = { _ -> """{"data":[{"item_code":"A","item_name":"Produto A","standard_rate":100}]}""" }
        srv.listas["Bin"] = { _ -> """{"data":[{"item_code":"A","actual_qty":5}]}""" }
        val r = catalogo.sincronizar(true)
        assertTrue(r.divergencias.isEmpty())
    }

    @Test fun falhaNoBinMantemPrecoEEstoqueAnterior() = runBlocking {
        srv.listas["Item"] = { _ -> """{"data":[{"item_code":"A","item_name":"Produto A","standard_rate":150}]}""" }
        srv.listas["Bin"] = { _ -> throw IllegalStateException("x") }          // o servidor falso devolverá 500
        val r = catalogo.sincronizar(true)
        assertEquals(150.0, r.produtos[0].preco, 0.0); assertEquals(5.0, r.produtos[0].estoque!!, 0.0)
        assertTrue(r.avisos.any { it.contains("Bin") })
    }

    @Test fun listarTodosPaginaEmBlocosDe50() = runBlocking {
        srv.listas["Item"] = { inicio -> if (inicio == 0) "{\"data\":[" + (1..50).joinToString(",") { """{"item_code":"I$it","item_name":"n$it","standard_rate":1}""" } + "]}"
                                          else """{"data":[{"item_code":"I51","item_name":"n51","standard_rate":1}]}""" }
        srv.listas["Bin"] = { _ -> """{"data":[]}""" }
        val r = catalogo.sincronizar(true)
        assertEquals(52, r.produtos.size)                                     // 51 do Ritmo + "A" local preservado
    }

    @Test fun offlineDevolveACopiaLocalSemRede() = runBlocking {
        val r = catalogo.sincronizar(online = false)
        assertFalse(r.atualizadoOnline); assertEquals(1, r.produtos.size); assertTrue(srv.chamadas.isEmpty())
    }

    @Test fun buscaSemAcentoPorNomeOuCodigo() {
        val l = listOf(ProdutoPdv("CAF1", "Café Coado", 5.0, null, null, null, null, null), prod("B"))
        assertEquals(1, PdvCatalogoRepository.filtrar(l, "cafe").size)
        assertEquals(1, PdvCatalogoRepository.filtrar(l, "caf1").size)
        assertEquals(2, PdvCatalogoRepository.filtrar(l, " ").size)
    }
}

class LoginOfflineTest {
    private val store = StoreEmMemoria()
    private var agora = 0L
    private val lockout = PinLockout { agora }
    private lateinit var login: LoginOffline
    private lateinit var srv: ServidorRitmoFalso

    private fun usuarioJson(id: String, pin: String?): String {
        if (pin == null) return """{"user_id":"$id","nome_exibicao":"Op $id","roles":["Caixa"],"employee":"EMP-1","pin_definido":false}"""
        val salt = PinCrypto.gerarSalt()
        return """{"user_id":"$id","nome_exibicao":"Op $id","roles":["Caixa"],"employee":"EMP-1","pin_definido":true,"pin_hash":"${PinCrypto.derivarHashHex(pin, salt, 1000)}","pin_salt":"${PinCrypto.paraBase64(salt)}","pin_kdf_iteracoes":1000}"""
    }

    private fun autorizacao(restricao: Boolean, vararg usuarios: String) = runBlocking {
        store.salvarConfig("autorizacao_caixa", parseObjeto("""{"chave":"autorizacao_caixa","pos_profile":"PDV1","sincronizado_em":"2026-10-12T08:00:00Z","restricao_configurada":$restricao,"usuarios":[${usuarios.joinToString(",")}]}"""))
    }

    @Before fun setUp() { srv = ServidorRitmoFalso(); login = LoginOffline(PdvConfigRepository(srv.ritmo(), store), lockout) }
    @After fun tearDown() = srv.parar()

    @Test fun entraComPinCorreto() = runBlocking {
        autorizacao(true, usuarioJson("a@loja.com", "123456"))
        val r = login.entrar("a@loja.com", "123456")
        assertTrue(r is ResultadoLoginOffline.Ok); assertEquals("EMP-1", (r as ResultadoLoginOffline.Ok).operador.employee)
        assertTrue(srv.chamadas.isEmpty())                                    // 100% local: nenhuma chamada de rede
    }

    @Test fun pinErradoBloqueiaAposCincoTentativas() = runBlocking {
        autorizacao(true, usuarioJson("a@loja.com", "123456"))
        repeat(5) { assertEquals("PIN incorreto.", (login.entrar("a@loja.com", "000000") as ResultadoLoginOffline.Erro).mensagem) }
        assertTrue((login.entrar("a@loja.com", "123456") as ResultadoLoginOffline.Erro).mensagem.startsWith("Muitas tentativas"))   // bloqueado até com o PIN certo
        agora += 61_000
        assertTrue(login.entrar("a@loja.com", "123456") is ResultadoLoginOffline.Ok)
    }

    @Test fun formatoInvalidoNaoContaComoErro() = runBlocking {
        autorizacao(true, usuarioJson("a@loja.com", "123456"))
        repeat(10) { assertEquals("Digite os 6 dígitos do PIN.", (login.entrar("a@loja.com", "12ab") as ResultadoLoginOffline.Erro).mensagem) }
        assertTrue(login.entrar("a@loja.com", "123456") is ResultadoLoginOffline.Ok)
    }

    @Test fun naoAutorizadoSemPinOuSemRestricao() = runBlocking {
        autorizacao(true, usuarioJson("a@loja.com", "123456"), usuarioJson("b@loja.com", null))
        assertTrue((login.entrar("x@loja.com", "123456") as ResultadoLoginOffline.Erro).mensagem.contains("não autorizado"))
        assertTrue((login.entrar("b@loja.com", "123456") as ResultadoLoginOffline.Erro).mensagem.contains("definiu o PIN"))
        assertEquals(listOf("a@loja.com"), login.operadoresDisponiveis().map { it.userId })
        autorizacao(false)
        assertTrue(login.operadoresDisponiveis().isEmpty()); assertTrue(login.diagnostico()!!.contains("POS Profile User"))
    }

    @Test fun semAutorizacaoSincronizadaExplicaOQueFazer() = runBlocking {
        assertTrue(login.diagnostico()!!.contains("entre online uma vez"))
        assertTrue(login.entrar("a@loja.com", "123456") is ResultadoLoginOffline.Erro)
    }
}
