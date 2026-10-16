package me.nomen.synapse.ui.pedidos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.nomen.synapse.data.ClasseStatus
import me.nomen.synapse.data.Pedido
import me.nomen.synapse.data.TipoPedido
import me.nomen.synapse.domain.Perfis
import me.nomen.synapse.domain.Usuario
import me.nomen.synapse.domain.formatarMoeda
import me.nomen.synapse.ui.Aviso
import me.nomen.synapse.ui.CarregandoLinha
import me.nomen.synapse.ui.SeletorDialog
import me.nomen.synapse.ui.TomAviso

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedidosScreen(usuario: Usuario, filtroInicial: ClasseStatus?, aoConsumirFiltro: () -> Unit, vm: PedidosViewModel = viewModel(factory = PedidosViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dados by vm.dados.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.garantir() }
    LaunchedEffect(filtroInicial) { if (filtroInicial != null) { vm.aplicarFiltroInicial(filtroInicial); aoConsumirFiltro() } }

    val todos = (if (ui.tipo == TipoPedido.VENDA) dados.vendas else dados.compras)
    val lista = remember(todos, ui.busca, ui.filtro) {
        todos.orEmpty().filter { p ->
            (ui.filtro == null || p.status.classe == ui.filtro) &&
                (ui.busca.isBlank() || (p.parte + p.id).contains(ui.busca, ignoreCase = true))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TipoPedido.entries.forEach { t ->
                FilterChip(selected = ui.tipo == t, onClick = { vm.mudarTipo(t) }, label = { Text(t.rotulo) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = vm::recarregar, enabled = !dados.carregando) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar pedidos") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ui.busca, vm::buscar, label = { Text("Buscar por ${ui.tipo.rotuloParte.lowercase()} ou número") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = vm::abrirNovo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Novo") }
        }
        ui.filtro?.let { f ->
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filtrado por: ${f.rotulo}", Modifier.weight(1f))
                TextButton(onClick = vm::limparFiltro) { Text("Limpar filtro") }
            }
        }
        ui.aviso?.let { Aviso(it, if (ui.avisoErro) TomAviso.ERRO else TomAviso.SUCESSO, rotuloAcao = "Fechar", acao = vm::fecharAviso) }
        dados.erro?.let { Aviso(it, TomAviso.ERRO, rotuloAcao = "Tentar novamente", acao = vm::recarregar) }
        if (dados.carregando) CarregandoLinha("Carregando pedidos do Ritmo…")

        if (todos != null && lista.isEmpty() && !dados.carregando && dados.erro == null) {
            Text("Nenhum pedido encontrado.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lista, key = { it.tipo.name + it.id }) { p -> CartaoPedido(p) { vm.abrir(p) } }
        }
    }

    ui.selecionado?.let { DetalhePedidoSheet(usuario, ui, vm) }
    ui.novo?.let { NovoPedidoSheet(it, vm) }
}

@Composable
private fun CartaoPedido(p: Pedido, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Ver detalhes do pedido ${p.id}", onClick = onClick)) {
        Column(Modifier.padding(14.dp).heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.id, style = MaterialTheme.typography.titleMedium)
                Text(formatarMoeda(p.valor), style = MaterialTheme.typography.titleMedium)
            }
            Text(p.parte, style = MaterialTheme.typography.bodyLarge)
            // O status é sempre texto (nunca só cor).
            Text("Status: ${p.status.rotulo}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetalhePedidoSheet(usuario: Usuario, ui: PedidosUi, vm: PedidosViewModel) {
    val p = ui.selecionado ?: return
    val podeCancelar = p.status.classe != ClasseStatus.CANCELADO && p.docstatus == 1 && Perfis.acaoPermitida(usuario.perfil, "cancelar_venda")
    ModalBottomSheet(onDismissRequest = vm::fechar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${p.id} — ${p.parte}", style = MaterialTheme.typography.titleLarge)
            Text("Status: ${p.status.rotulo}", style = MaterialTheme.typography.labelLarge)
            Text("Total: ${formatarMoeda(p.valor)}")
            HorizontalDivider()
            Text("Itens", style = MaterialTheme.typography.titleSmall)
            if (ui.carregandoDetalhe) CarregandoLinha("Carregando itens…")
            ui.erroDetalhe?.let { Aviso(it, TomAviso.ERRO) }
            ui.detalhe?.let { d ->
                if (d.itens.isEmpty()) Text("Sem itens")
                d.itens.forEach { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${formatarQtd(it.qtd)}x ${it.produto}", Modifier.weight(1f)); Text(formatarMoeda(it.valor)) } }
                Text("Endereço", style = MaterialTheme.typography.titleSmall); Text(d.endereco)
            }

            if (p.tipo == TipoPedido.VENDA) {
                OutlinedButton(onClick = vm::consultarEnvio, enabled = !ui.consultandoEnvio, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Situação do envio") }
                if (ui.consultandoEnvio) CarregandoLinha("Consultando situação do envio…")
                ui.erroEnvio?.let { Aviso(it, TomAviso.INFO) }
                ui.envio?.let { e ->
                    Text("Transportadora: ${e.transportadora ?: "Ainda não definida"}")
                    Text("Código de rastreio: ${e.codigo ?: "Ainda não gerado"}")
                    Text("Status: ${if (e.entregue) "Entregue" else "Em transporte"}")
                }
            }
            if (podeCancelar) {
                OutlinedButton(
                    onClick = vm::pedirConfirmacaoCancelamento, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Cancelar pedido") }
            }
            TextButton(onClick = vm::fechar, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Fechar") }
        }
    }
    if (ui.confirmandoCancelamento) {
        AlertDialog(
            onDismissRequest = { if (!ui.enviandoCancelamento) vm.desistirCancelamento() },
            title = { Text("Cancelar pedido") },
            text = { Text("Deseja solicitar o cancelamento do pedido ${p.id} — ${p.parte}? O app não cancela diretamente: a solicitação é enviada à Lyra, que conduz estorno, estoque e financeiro pela sequência já existente no Ritmo.") },
            confirmButton = { Button(onClick = vm::solicitarCancelamento, enabled = !ui.enviandoCancelamento) { Text(if (ui.enviandoCancelamento) "Enviando…" else "Sim, solicitar à Lyra") } },
            dismissButton = { TextButton(onClick = vm::desistirCancelamento, enabled = !ui.enviandoCancelamento) { Text("Voltar") } }
        )
    }
}

private fun formatarQtd(q: Double): String = if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovoPedidoSheet(n: NovoPedidoUi, vm: PedidosViewModel) {
    var seletor by rememberSaveable { mutableStateOf<String?>(null) } // "parte" | "produto"
    ModalBottomSheet(onDismissRequest = { if (!n.salvando) vm.fecharNovo() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Novo pedido", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TipoPedido.entries.forEach { t -> FilterChip(selected = n.tipo == t, onClick = { vm.novoMudarTipo(t) }, label = { Text(t.rotulo) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            n.erroCarga?.let { Aviso(it, TomAviso.ERRO) }
            if (n.partes == null && n.erroCarga == null) CarregandoLinha("Carregando clientes, fornecedores e produtos…")

            OutlinedButton(onClick = { seletor = "parte" }, enabled = n.partes != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(n.parte?.nome ?: "Selecionar ${n.tipo.rotuloParte.lowercase()}")
            }
            if (n.tipo == TipoPedido.COMPRA) {
                OutlinedTextField(n.dataPrevista, vm::novoData, label = { Text("Data prevista de entrega (AAAA-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            Text("Itens", style = MaterialTheme.typography.titleSmall)
            if (n.itens.isEmpty()) Text("Nenhum item adicionado ainda")
            n.itens.forEachIndexed { i, it ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatarQtd(it.qtd)}x ${it.nome} — ${formatarMoeda(it.qtd * it.rate)}", Modifier.weight(1f))
                    TextButton(onClick = { vm.novoRemoverItem(i) }) { Text("Remover") }
                }
            }
            OutlinedButton(onClick = { seletor = "produto" }, enabled = n.produtos != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(n.produtoSel?.nome ?: "Selecionar produto")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n.qtd, vm::novoQtd, label = { Text("Quantidade") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(n.preco, vm::novoPreco, label = { Text("Preço unitário") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = vm::novoAdicionarItem, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Adicionar item") }

            n.erro?.let { Aviso(it, TomAviso.ERRO) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = vm::fecharNovo, enabled = !n.salvando, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancelar") }
                Button(onClick = vm::novoSalvar, enabled = !n.salvando, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(if (n.salvando) "Salvando…" else "Criar pedido") }
            }
        }
    }
    when (seletor) {
        "parte" -> SeletorDialog(n.tipo.rotuloParte, n.partes.orEmpty().map { it.id to it.nome }, { id -> n.partes?.firstOrNull { it.id == id }?.let(vm::novoEscolherParte); seletor = null }, { seletor = null })
        "produto" -> SeletorDialog("Produto", n.produtos.orEmpty().map { it.codigo to "${it.nome} (${it.codigo})" }, { id -> n.produtos?.firstOrNull { it.codigo == id }?.let(vm::novoEscolherProduto); seletor = null }, { seletor = null })
    }
}
