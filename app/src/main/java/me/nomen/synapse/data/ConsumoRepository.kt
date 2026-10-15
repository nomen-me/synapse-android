package me.nomen.synapse.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UsoVps(val cpu: Int, val ram: Int, val disco: Int)
class PontoConsumo(val rotulo: String, val valor: Int)

sealed interface ConsumoServicos {
    class Disponivel(val ia: List<PontoConsumo>, val frete: List<PontoConsumo>, val freteSaldo: String, val freteVolume: String) : ConsumoServicos
    /** HTTP 501 PENDENTE_FONTE_DE_DADOS: o backend ainda não tem a fonte (estado real hoje, ver workflow consumo/servicos). */
    class Pendente(val detalhe: String) : ConsumoServicos
}

/**
 * Consumo: métricas do servidor (Acorde/Netdata via Harmonia `monitoramento/metricas`) e consumo de serviços
 * (`consumo/servicos`). Mesmo cálculo e mesmo tratamento de "fonte pendente" do painel (bffConsumoVPS / consumoFontePendente).
 */
class ConsumoRepository(private val harmonia: HarmoniaClient) {

    suspend fun metricasVps(): UsoVps = coroutineScope {
        val cpu = async { harmonia.chamar("monitoramento/metricas", consulta("system.cpu")) }
        val ram = async { harmonia.chamar("monitoramento/metricas", consulta("system.ram")) }
        val disco = async { harmonia.chamar("monitoramento/metricas", consulta("disk_space._")) }
        UsoVps(percentualNetdata(cpu.await(), cpu = true), percentualNetdata(ram.await(), cpu = false), percentualNetdata(disco.await(), cpu = false))
    }

    suspend fun servicos(): ConsumoServicos = try {
        val r = harmonia.chamar("consumo/servicos")
        ConsumoServicos.Disponivel(pontos(r, "ia"), pontos(r, "frete"), r.str("freteSaldo") ?: "—", r.str("freteVolume") ?: "—")
    } catch (e: ApiException) {
        if (e.status == 501 && e.message.orEmpty().contains("PENDENTE_FONTE_DE_DADOS")) ConsumoServicos.Pendente(e.message.orEmpty()) else throw e
    }

    private fun consulta(chart: String): JsonObject = buildJsonObject { put("chart", chart); put("points", 1); put("format", "json"); put("after", -1) }

    private fun pontos(r: JsonObject, chave: String): List<PontoConsumo> =
        r.lista(chave).mapNotNull { el -> (el as? JsonObject)?.let { PontoConsumo(it.str("d") ?: "", it.num("v")?.toInt() ?: 0) } }

    companion object {
        /** cpu: coluna 1 já é o percentual; ram/disco: usado / (usado + livre). Qualquer anomalia vira 0 (como no painel). */
        fun percentualNetdata(resposta: JsonObject, cpu: Boolean): Int {
            val linha = (resposta["data"] as? JsonArray)?.firstOrNull() as? JsonArray ?: return 0
            fun col(i: Int) = (linha.getOrNull(i) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            if (cpu) return Math.round(col(1)).toInt()
            val usado = col(1); val livre = col(2)
            val total = usado + livre
            if (total <= 0.0) return 0
            return Math.round(usado / total * 100).toInt()
        }
    }
}
