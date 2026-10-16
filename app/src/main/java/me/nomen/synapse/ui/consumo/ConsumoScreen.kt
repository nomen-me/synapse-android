package me.nomen.synapse.ui.consumo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.nomen.synapse.data.ConsumoServicos
import me.nomen.synapse.data.PontoConsumo
import me.nomen.synapse.domain.Perfis
import me.nomen.synapse.domain.Usuario
import me.nomen.synapse.ui.Aviso
import me.nomen.synapse.ui.CarregandoLinha
import me.nomen.synapse.ui.TomAviso

@Composable
fun ConsumoScreen(usuario: Usuario, vm: ConsumoViewModel = viewModel(factory = ConsumoViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.carregar() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Consumo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::carregar, enabled = !ui.carregando) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar consumo") }
        }
        if (ui.carregando) CarregandoLinha("Atualizando métricas…")

        Text("Servidor", style = MaterialTheme.typography.titleMedium)
        ui.erroVps?.let { Aviso(it, TomAviso.ERRO, rotuloAcao = "Tentar novamente", acao = vm::carregar) }
        ui.vps?.let { v ->
            BarraUso("CPU", v.cpu); BarraUso("Memória (RAM)", v.ram); BarraUso("Disco", v.disco)
            ui.atualizadoEm?.let { Text("Atualizado às $it", style = MaterialTheme.typography.bodySmall) }
        }

        Text("Serviços", style = MaterialTheme.typography.titleMedium)
        ui.erroServicos?.let { Aviso(it, TomAviso.ERRO, rotuloAcao = "Tentar novamente", acao = vm::carregar) }
        when (val s = ui.servicos) {
            is ConsumoServicos.Pendente -> {
                Aviso("Métrica em configuração: o consumo de IA e de frete ainda não tem fonte de dados no servidor.", TomAviso.INFO)
                if (usuario.perfil == Perfis.ADMINISTRADOR) Text("501 — PENDENTE_FONTE_DE_DADOS: ${s.detalhe}", style = MaterialTheme.typography.bodySmall)
            }
            is ConsumoServicos.Disponivel -> {
                s.pendencias.forEach { Aviso("Fonte pendente — $it", TomAviso.INFO) }
                if (s.pendencias.none { it.startsWith("ia:") }) Grafico("Requisições de IA por dia", s.ia, "req")
                if (s.pendencias.none { it.startsWith("frete:") }) Grafico("Fretes por semana", s.frete, "fretes")
                Text("Saldo de frete: ${s.freteSaldo}"); Text("Fretes no mês: ${s.freteVolume}")
            }
            null -> Unit
        }
    }
}

@Composable
private fun BarraUso(nome: String, pct: Int) {
    // Estado sempre em texto (Normal / Atenção / Crítico) além da barra: nunca só cor.
    val estado = when { pct >= 90 -> "Crítico"; pct >= 70 -> "Atenção"; else -> "Normal" }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$nome: $pct por cento, $estado" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(nome); Text("$pct% — $estado") }
        Box(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun Grafico(titulo: String, pontos: List<PontoConsumo>, unidade: String) {
    val max = (pontos.maxOfOrNull { it.valor } ?: 0).coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall)
            if (pontos.isEmpty()) Text("Sem dados no período")
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                pontos.forEach { p ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text("${p.valor}", style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.fillMaxWidth().height((70.dp * p.valor / max).coerceAtLeast(3.dp)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Text(p.rotulo, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(pontos.joinToString("; ") { "${it.rotulo} ${it.valor} $unidade" }, Modifier.semantics { contentDescription = "$titulo: " + pontos.joinToString("; ") { "${it.rotulo} ${it.valor} $unidade" } }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
