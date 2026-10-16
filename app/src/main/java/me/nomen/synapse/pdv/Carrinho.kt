package me.nomen.synapse.pdv

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Ajuste(val tipo: String, val valor: Double) {   // tipo: "percentual" | "valor"
    fun toJson(): JsonObject = buildJsonObject { put("tipo", tipo); put("valor", valor) }
}

data class AcrescimoVenda(val tipo: String, val descricao: String?, val valor: Double) {
    fun toJson(): JsonObject = buildJsonObject { put("tipo", tipo); put("descricao", descricao); put("valor", valor) }
}

data class ItemCarrinho(
    val chave: String, val itemCode: String, val nome: String, val preco: Double, val qtd: Double,
    val pesavel: Boolean = false, val composicaoId: String? = null, val preenchimentos: JsonObject = JsonObject(emptyMap()),
    val frase: String? = null, val alertaCozinha: Boolean = false, val statusProducao: String? = null,
    val desconto: Ajuste? = null, val acrescimo: Ajuste? = null, val precoOriginal: Double? = null,
    val precoAutorizadoPor: String? = null, val precoMotivo: String? = null,
    val itemGenerico: Boolean = false, val autorizadoPor: String? = null
) {
    /** Item no formato do payload da fila (mesmas chaves do painel). */
    fun paraPayload(): JsonObject = buildJsonObject {
        put("item_code", itemCode); put("nome", nome); put("qtd", qtd); put("preco", preco)
        put("composicao_id", composicaoId)
        put("preenchimentos", if (preenchimentos.isEmpty()) JsonNull else preenchimentos)
        put("frase", frase); put("alerta_cozinha", alertaCozinha); put("status_producao", statusProducao)
        put("desconto", desconto?.toJson() ?: JsonNull); put("acrescimo", acrescimo?.toJson() ?: JsonNull)
        put("preco_original", precoOriginal); put("preco_autorizado_por", precoAutorizadoPor); put("preco_motivo", precoMotivo)
        put("item_generico", itemGenerico); put("item_generico_autorizado_por", autorizadoPor)
    }

    val valorComAjuste: Double get() = valorItemComAjuste(paraPayload())
}

class ResultadoCarrinho(val carrinho: Carrinho, val erro: String? = null)

/** Carrinho imutável do PDV. A matemática do total reproduz totalCarrinhoPDV do painel. */
data class Carrinho(
    val itens: List<ItemCarrinho> = emptyList(),
    val descontoGeral: Ajuste? = null,
    val acrescimos: List<AcrescimoVenda> = emptyList()
) {
    val vazio: Boolean get() = itens.isEmpty()
    val quantidadeDeItens: Double get() = itens.sumOf { if (it.pesavel) 1.0 else it.qtd }

    fun total(): Double {
        var sub = itens.sumOf { it.valorComAjuste }
        descontoGeral?.let { sub -= if (it.tipo == "percentual") sub * (it.valor / 100) else it.valor }
        sub += acrescimos.sumOf { it.valor }
        return maxOf(0.0, sub)
    }

    fun adicionar(produto: ProdutoPdv, qtd: Double = 1.0, preenchimentos: JsonObject = JsonObject(emptyMap()), extra: (ItemCarrinho) -> ItemCarrinho = { it }): ResultadoCarrinho {
        val q = if (qtd > 0) qtd else 1.0
        val chave = chaveDoItem(produto.itemCode, preenchimentos)
        val existente = itens.firstOrNull { it.chave == chave }
        val atual = existente?.qtd ?: 0.0
        if (produto.estoque != null && atual + q > produto.estoque) return ResultadoCarrinho(this, "Estoque local esgotado para \"${produto.nome}\"")
        val novos = if (existente != null) itens.map { if (it.chave == chave) it.copy(qtd = it.qtd + q) else it }
        else itens + extra(ItemCarrinho(chave, produto.itemCode, produto.nome, produto.preco, q, ehPesavel(produto.uom), produto.composicaoId, preenchimentos))
        return ResultadoCarrinho(copy(itens = novos))
    }

    /** ± quantidade; linha pesável não usa ±1 (edita o peso). Zerar remove a linha. */
    fun alterarQtd(chave: String, delta: Double, estoqueDisponivel: Double?): ResultadoCarrinho {
        val item = itens.firstOrNull { it.chave == chave } ?: return ResultadoCarrinho(this)
        if (item.pesavel) return ResultadoCarrinho(this, "Linha pesável: edite o peso")
        if (delta > 0 && estoqueDisponivel != null && item.qtd + delta > estoqueDisponivel) return ResultadoCarrinho(this, "Estoque local esgotado para \"${item.nome}\"")
        val nova = item.qtd + delta
        return ResultadoCarrinho(copy(itens = if (nova <= 0) itens.filter { it.chave != chave } else itens.map { if (it.chave == chave) it.copy(qtd = nova) else it }))
    }

    fun definirQtd(chave: String, qtd: Double, estoqueDisponivel: Double?): ResultadoCarrinho {
        val item = itens.firstOrNull { it.chave == chave } ?: return ResultadoCarrinho(this)
        if (qtd <= 0) return ResultadoCarrinho(remover(chave))
        if (estoqueDisponivel != null && qtd > estoqueDisponivel) return ResultadoCarrinho(this, "Estoque local esgotado para \"${item.nome}\"")
        return ResultadoCarrinho(copy(itens = itens.map { if (it.chave == chave) it.copy(qtd = qtd) else it }))
    }

    fun remover(chave: String) = copy(itens = itens.filter { it.chave != chave })
    fun comDescontoItem(chave: String, d: Ajuste?) = copy(itens = itens.map { if (it.chave == chave) it.copy(desconto = d) else it })
    fun comAcrescimoItem(chave: String, a: Ajuste?) = copy(itens = itens.map { if (it.chave == chave) it.copy(acrescimo = a) else it })
    fun comDescontoGeral(d: Ajuste?) = copy(descontoGeral = d)
    fun adicionarAcrescimo(a: AcrescimoVenda) = copy(acrescimos = acrescimos + a)
    fun removerAcrescimo(indice: Int) = copy(acrescimos = acrescimos.filterIndexed { i, _ -> i != indice })

    /** Preço livre exige supervisor + motivo (PDV_ACOES_RESTRITAS.preco_livre); guarda o preço original. */
    fun comPrecoLivre(chave: String, novoPreco: Double, autorizadoPor: String, motivo: String) =
        copy(itens = itens.map { if (it.chave == chave) it.copy(precoOriginal = it.precoOriginal ?: it.preco, preco = novoPreco, precoAutorizadoPor = autorizadoPor, precoMotivo = motivo) else it })

    companion object {
        fun chaveDoItem(itemCode: String, preenchimentos: JsonObject) =
            itemCode + if (preenchimentos.isNotEmpty()) "|$preenchimentos" else ""
    }
}
