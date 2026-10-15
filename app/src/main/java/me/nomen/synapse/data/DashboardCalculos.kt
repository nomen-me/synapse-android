package me.nomen.synapse.data

import me.nomen.synapse.domain.parseDataIso
import java.time.LocalDate

class VolumeDia(val data: LocalDate, val qtd: Int)

/** Faturamento = soma de vendas pagas/aprovadas (igual ao renderDashboard do painel). */
fun faturamento(vendas: List<Pedido>): Double =
    vendas.filter { it.status.classe == ClasseStatus.PAGO || it.status.classe == ClasseStatus.APROVADO }.sumOf { it.valor }

fun fluxoDePedidos(vendas: List<Pedido>): Int = vendas.count { it.status.classe != ClasseStatus.CANCELADO }

fun pedidosEmEspera(vendas: List<Pedido>): Int = vendas.count { it.status.classe == ClasseStatus.AGUARDA }

/** Pedidos por dia nos últimos [janelaDias] dias, do mais antigo ao de hoje (agruparVolumePorDia do painel). */
fun volumePorDia(pedidos: List<Pedido>, janelaDias: Int, hoje: LocalDate = LocalDate.now()): List<VolumeDia> {
    val contagem = IntArray(janelaDias)
    pedidos.forEach { p ->
        val d = parseDataIso(p.dataIso) ?: return@forEach
        val diff = java.time.temporal.ChronoUnit.DAYS.between(d, hoje).toInt()
        if (diff in 0 until janelaDias) contagem[janelaDias - 1 - diff]++
    }
    return (0 until janelaDias).map { VolumeDia(hoje.minusDays((janelaDias - 1 - it).toLong()), contagem[it]) }
}
