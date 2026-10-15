package me.nomen.synapse.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.nomen.synapse.data.ClasseStatus
import me.nomen.synapse.data.faturamento
import me.nomen.synapse.data.fluxoDePedidos
import me.nomen.synapse.data.pedidosEmEspera
import me.nomen.synapse.data.volumePorDia
import me.nomen.synapse.domain.formatarMoeda
import me.nomen.synapse.ui.Aviso
import me.nomen.synapse.ui.CarregandoLinha
import me.nomen.synapse.ui.TomAviso
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun DashboardScreen(
    irParaPedidos: (ClasseStatus) -> Unit, irParaAtendimento: () -> Unit,
    vm: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
) {
    val dados by vm.store.estado.collectAsStateWithLifecycle()
    val conversas by vm.conversas.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.carregar() }

    val vendas = dados.vendas
    val colunas = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 1

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Visão geral", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::atualizar, enabled = !dados.carregando) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar dashboard") }
        }
        dados.erro?.let { Aviso(it, TomAviso.ERRO, rotuloAcao = "Tentar novamente", acao = vm::atualizar) }
        if (vendas == null && dados.carregando) CarregandoLinha("Carregando pedidos do Ritmo…")

        if (vendas != null) {
            val cartoes: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> Metrica("Faturamento", formatarMoeda(faturamento(vendas)), "Soma dos pedidos de venda aprovados ou concluídos", m) },
                { m -> Metrica("Fluxo de pedidos", fluxoDePedidos(vendas).toString(), "Pedidos de venda não cancelados", m) },
                { m ->
                    val texto = conversas.aguardando?.toString() ?: if (conversas.erro != null) "Indisponível" else "…"
                    Metrica("Conversas aguardando", texto, conversas.erro ?: "Atendimento", m, onClick = irParaAtendimento)
                }
            )
            if (colunas == 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { cartoes.forEach { it(Modifier.weight(1f)) } }
            else cartoes.forEach { it(Modifier.fillMaxWidth()) }

            val espera = pedidosEmEspera(vendas)
            Text("Alertas", style = MaterialTheme.typography.titleMedium)
            if (espera > 0) {
                Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Ver pedidos em espera") { irParaPedidos(ClasseStatus.AGUARDA) }) {
                    Text("$espera pedido(s) em espera", Modifier.padding(16.dp).heightIn(min = 24.dp))
                }
            } else Text("Nada exige atenção agora.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Pedidos por dia (últimos 7 dias)", style = MaterialTheme.typography.titleMedium)
            GraficoVolume(vendas)
        }
    }
}

@Composable
private fun Metrica(titulo: String, valor: String, apoio: String, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val base = if (onClick != null) modifier.clickable(role = Role.Button, onClickLabel = "Abrir $titulo", onClick = onClick) else modifier
    Card(base) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valor, style = MaterialTheme.typography.headlineMedium)
            Text(apoio, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GraficoVolume(vendas: List<me.nomen.synapse.data.Pedido>) {
    val volume = volumePorDia(vendas, 7)
    val max = (volume.maxOfOrNull { it.qtd } ?: 0).coerceAtLeast(1)
    val resumo = volume.joinToString("; ") { "${rotuloDia(it)} ${it.qtd}" }
    Row(
        Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "Pedidos por dia nos últimos 7 dias: $resumo" },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom
    ) {
        volume.forEach { v ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(v.qtd.toString(), style = MaterialTheme.typography.labelMedium)   // valor sempre escrito
                Box(Modifier.fillMaxWidth().height((100.dp * v.qtd / max).coerceAtLeast(4.dp)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                Text(rotuloDia(v), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun rotuloDia(v: me.nomen.synapse.data.VolumeDia): String =
    v.data.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("pt", "BR")).replace(".", "")
