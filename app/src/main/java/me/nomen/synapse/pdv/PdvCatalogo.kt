package me.nomen.synapse.pdv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.num
import me.nomen.synapse.data.lista
import me.nomen.synapse.data.str
import java.text.Normalizer
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val UOM_PESAVEL = setOf("kg", "kgs", "quilo", "quilos", "quilograma", "quilogramas", "kilogram", "kilograms")

/** pdvHfNorm do painel: sem acentos, aparado, minúsculo. */
fun normalizarTexto(s: String?): String =
    Normalizer.normalize(s ?: "", Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "").trim().lowercase()

fun ehPesavel(uom: String?): Boolean = !uom.isNullOrBlank() && normalizarTexto(uom) in UOM_PESAVEL

class ProdutoPdv(
    val itemCode: String, val nome: String, val preco: Double, val estoque: Double?,
    val composicaoId: String?, val categoria: String?, val uom: String?, val imagem: String?
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("item_code", itemCode); put("nome", nome); put("preco", preco); put("estoque", estoque)
        put("composicao_id", composicaoId); put("categoria", categoria); put("uom", uom); put("imagem", imagem)
    }

    companion object {
        fun deJson(o: JsonObject): ProdutoPdv? {
            val code = o.str("item_code") ?: return null
            return ProdutoPdv(code, o.str("nome") ?: code, o.num("preco") ?: 0.0, o.num("estoque"), o.str("composicao_id"), o.str("categoria"), o.str("uom"), o.str("imagem"))
        }
    }
}

class DivergenciaEstoque(val item: String, val local: Double, val ritmo: Double)
class ResultadoCatalogo(val produtos: List<ProdutoPdv>, val divergencias: List<DivergenciaEstoque>, val avisos: List<String>, val atualizadoOnline: Boolean)

/**
 * Catálogo do PDV (pdvCarregarCatalogo do painel): parte SEMPRE da cópia local; online, atualiza Items (preço) e
 * estoque por depósito (Bin). Falha ao ler o Bin mantém produto/preço e o estoque anterior; falha geral mantém a cópia local.
 */
class PdvCatalogoRepository(private val ritmo: RitmoClient, private val config: PdvConfigRepository, private val store: PdvStore) {

    suspend fun local(): List<ProdutoPdv> =
        (store.obterConfig("catalogo")?.get("itens") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(ProdutoPdv::deJson) }

    private suspend fun gravar(produtos: List<ProdutoPdv>) =
        store.salvarConfig("catalogo", buildJsonObject {
            put("chave", "catalogo"); put("atualizado_em", Instant.now().toString()); put("itens", JsonArray(produtos.map { it.toJson() }))
        })

    suspend fun sincronizar(online: Boolean): ResultadoCatalogo {
        val anteriores = local()
        if (!online) return ResultadoCatalogo(anteriores, emptyList(), emptyList(), false)
        val avisos = mutableListOf<String>(); val divergencias = mutableListOf<DivergenciaEstoque>()
        return try {
            val itens = ritmo.listarTodos("Item", listOf("item_code", "item_name", "standard_rate", "custom_composicao", "item_group", "image", "stock_uom"))
            val dep = config.resolverDeposito(online = true)
            val saldos = HashMap<String, Double>()
            if (dep != null) {
                try {
                    val filtro = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("warehouse"), JsonPrimitive("="), JsonPrimitive(dep.warehouse)))))
                    ritmo.listarTodos("Bin", listOf("item_code", "actual_qty"), filtro).forEach { b -> b.str("item_code")?.let { saldos[it] = b.num("actual_qty") ?: 0.0 } }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    avisos += "Estoque: não foi possível ler o Bin agora — catálogo segue só com produto/preço."
                }
            }
            val comVendaPendente = store.todasOps()
                .filter { it.tipo == TipoOp.VENDA && (it.status == StatusOp.PENDENTE || it.status == StatusOp.ENVIANDO) }
                .flatMap { op -> op.payload.lista("itens").mapNotNull { (it as? JsonObject)?.str("item_code") } }.toSet()
            val mapa = LinkedHashMap<String, ProdutoPdv>().also { m -> anteriores.forEach { m[it.itemCode] = it } }
            for (it in itens) {
                val code = it.str("item_code") ?: continue
                val anterior = mapa[code]
                val estoque = saldos[code] ?: anterior?.estoque
                if (anterior?.estoque != null && estoque != null && anterior.estoque != estoque && code !in comVendaPendente)
                    divergencias += DivergenciaEstoque(it.str("item_name") ?: code, anterior.estoque, estoque)
                mapa[code] = ProdutoPdv(code, it.str("item_name") ?: code, it.num("standard_rate") ?: 0.0, estoque, it.str("custom_composicao"), it.str("item_group"), it.str("stock_uom"), it.str("image"))
            }
            val resultado = mapa.values.toList()
            gravar(resultado)
            ResultadoCatalogo(resultado, divergencias, avisos, true)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            avisos += "Catálogo: usando a última cópia local — falha ao atualizar (${e.message})"
            ResultadoCatalogo(anteriores, emptyList(), avisos, false)
        }
    }

    /** Impede o próprio caixa de vender de novo o que já sabe estar esgotado (pdvDecrementarEstoqueLocal). */
    suspend fun decrementarEstoqueLocal(itens: List<ItemCarrinho>) {
        val atual = local()
        val novos = atual.map { p ->
            val vendido = itens.filter { it.itemCode == p.itemCode }.sumOf { it.qtd }
            if (vendido > 0 && p.estoque != null) ProdutoPdv(p.itemCode, p.nome, p.preco, maxOf(0.0, p.estoque - vendido), p.composicaoId, p.categoria, p.uom, p.imagem) else p
        }
        gravar(novos)
    }

    companion object {
        /** Busca por nome ou código, sem acentos (buscarProdutoPDV). */
        fun filtrar(produtos: List<ProdutoPdv>, termo: String): List<ProdutoPdv> {
            val t = normalizarTexto(termo)
            return if (t.isEmpty()) produtos else produtos.filter { normalizarTexto(it.nome).contains(t) || normalizarTexto(it.itemCode).contains(t) }
        }
    }
}
