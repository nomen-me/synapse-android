package me.nomen.synapse.ui.atendimento

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.nomen.synapse.data.AutorMensagem
import me.nomen.synapse.data.Conversa
import me.nomen.synapse.data.Mensagem
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AtendimentoScreen(vm: AtendimentoViewModel = viewModel(factory = AtendimentoViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.garantirLista() }

    val largo = LocalConfiguration.current.screenWidthDp >= 600
    if (largo) {
        Row(Modifier.fillMaxSize()) {
            ListaConversas(ui, vm, Modifier.width(340.dp).fillMaxHeight())
            VerticalDivider(Modifier.fillMaxHeight())
            if (ui.selecionadaId == null) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("Selecione uma conversa.", style = MaterialTheme.typography.bodyLarge)
                }
            } else DetalheConversaView(ui, vm, mostrarVoltar = false, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        if (ui.selecionadaId == null) ListaConversas(ui, vm, Modifier.fillMaxSize())
        else {
            BackHandler { vm.voltar() }
            DetalheConversaView(ui, vm, mostrarVoltar = true, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ListaConversas(ui: AtendimentoUi, vm: AtendimentoViewModel, modifier: Modifier) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilterChip(
                selected = ui.somenteAguardando, onClick = vm::alternarFiltroAguardando,
                label = { Text("Aguardando resposta") }, modifier = Modifier.heightIn(min = 48.dp)
            )
            IconButton(onClick = vm::atualizarLista, enabled = !ui.carregandoLista) {
                Icon(Icons.Default.Refresh, contentDescription = "Atualizar conversas")
            }
        }
        if (ui.carregandoLista) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Carregando conversas…")
            }
        }
        ui.erroLista?.let { BlocoErro(it, "Tentar novamente", vm::atualizarLista) }

        val visiveis = ui.visiveis
        if (ui.conversas != null && visiveis.isEmpty() && ui.erroLista == null && !ui.carregandoLista) {
            Text(
                if (ui.somenteAguardando) "Nenhuma conversa aguardando resposta." else "Nenhuma conversa no momento.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(visiveis, key = { it.id }) { c -> LinhaConversa(c, c.id == ui.selecionadaId) { vm.abrir(c.id) } }
        }
    }
}

@Composable
private fun LinhaConversa(c: Conversa, selecionada: Boolean, onClick: () -> Unit) {
    val situacao = if (c.aguardandoResposta) "Aguardando resposta" else "Resolvida"
    Column(
        Modifier.fillMaxWidth()
            .background(if (selecionada) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
            .clickable(role = Role.Button, onClickLabel = "Abrir conversa com ${c.nome}", onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(c.nome, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${c.canal.rotulo} · $situacao", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        c.ultimaMensagem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    HorizontalDivider()
}

@Composable
private fun DetalheConversaView(ui: AtendimentoUi, vm: AtendimentoViewModel, mostrarVoltar: Boolean, modifier: Modifier) {
    // imePadding: o campo de mensagem nunca fica escondido pelo teclado.
    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mostrarVoltar) IconButton(onClick = vm::voltar) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar para a lista de conversas")
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(ui.selecionada?.nome ?: "Conversa", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ui.selecionada?.let { Text(it.canal.rotulo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = vm::recarregarDetalhe, enabled = !ui.carregandoDetalhe) {
                Icon(Icons.Default.Refresh, contentDescription = "Atualizar conversa")
            }
        }
        HorizontalDivider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = ui.detalhe
            when {
                ui.carregandoDetalhe && d == null -> Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text("Carregando conversa…")
                }
                ui.erroDetalhe != null && d == null -> BlocoErro(ui.erroDetalhe, "Tentar novamente", vm::recarregarDetalhe)
                d != null -> {
                    val estado = rememberLazyListState()
                    LaunchedEffect(d.mensagens.size) { if (d.mensagens.isNotEmpty()) estado.scrollToItem(d.mensagens.lastIndex + 1) }
                    LazyColumn(Modifier.fillMaxSize(), state = estado, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            ContextoCliente(d.cliente.nome, d.cliente.cpf, d.cliente.contato, d.pedidos)
                        }
                        if (d.mensagens.isEmpty()) item { Text("Sem mensagens nesta conversa.") }
                        items(d.mensagens.size) { i -> Balao(d.mensagens[i]) }
                    }
                }
            }
        }

        ui.erroEnvio?.let { BlocoErro(it, null, {}) }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.rascunho, onValueChange = vm::editarRascunho, enabled = !ui.enviando,
                label = { Text("Mensagem") }, maxLines = 4, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = vm::enviar, enabled = ui.rascunho.isNotBlank() && !ui.enviando) {
                if (ui.enviando) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar mensagem")
            }
        }
    }
}

@Composable
private fun ContextoCliente(nome: String?, cpf: String?, contato: String?, pedidos: List<String>) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("Cliente", style = MaterialTheme.typography.titleSmall)
        Text("Nome: ${nome ?: "—"}")
        Text("CPF: ${cpf ?: "—"}")
        Text("Contato: ${contato ?: "—"}")
        Text(
            if (pedidos.isEmpty()) "Nenhum pedido vinculado" else "Pedidos recentes: " + pedidos.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private val FORMATO_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun formatarHora(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(FORMATO_HORA) }.getOrNull()
}

@Composable
private fun Balao(m: Mensagem) {
    val doCliente = m.autor == AutorMensagem.CLIENTE
    val autor = when (m.autor) { AutorMensagem.CLIENTE -> "Cliente"; AutorMensagem.LYRA -> "Lyra"; AutorMensagem.AGENTE -> "Atendente" }
    val fundo = if (doCliente) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val texto = if (doCliente) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (doCliente) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 420.dp).background(fundo, RoundedCornerShape(12.dp)).padding(10.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(listOfNotNull(autor, formatarHora(m.horaIso)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = texto)
            Text(m.texto, color = texto, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BlocoErro(mensagem: String, rotuloAcao: String?, acao: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Warning, contentDescription = "Erro", tint = MaterialTheme.colorScheme.error)
            Text(mensagem, color = MaterialTheme.colorScheme.error)
        }
        if (rotuloAcao != null) Button(onClick = acao, modifier = Modifier.heightIn(min = 48.dp)) { Text(rotuloAcao) }
    }
}
