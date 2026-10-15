package me.nomen.synapse.ui.pdv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.nomen.synapse.domain.Perfis
import me.nomen.synapse.domain.Usuario
import me.nomen.synapse.domain.formatarMoeda
import me.nomen.synapse.pdv.ItemCarrinho
import me.nomen.synapse.pdv.OperacaoFila
import me.nomen.synapse.pdv.ProdutoPdv
import me.nomen.synapse.pdv.StatusOp
import me.nomen.synapse.pdv.TipoOp
import me.nomen.synapse.ui.Aviso
import me.nomen.synapse.ui.CarregandoLinha
import me.nomen.synapse.ui.TomAviso

private fun qtd(q: Double) = if (q % 1.0 == 0.0) q.toLong().toString() else "%.3f".format(q)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdvScreen(usuario: Usuario, vm: PdvViewModel = viewModel(factory = PdvViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(usuario.email) { vm.iniciar(usuario) }
    var carrinhoAberto by rememberSaveable { mutableStateOf(false) }
    var fecharCaixaConfirm by rememberSaveable { mutableStateOf(false) }
    var descontoAberto by rememberSaveable { mutableStateOf(false) }
    val largo = LocalConfiguration.current.screenWidthDp >= 840

    Column(Modifier.fillMaxSize()) {
        BarraEstado(ui, vm, usuario)
        ui.aviso?.let { Aviso(it, if (ui.avisoErro) TomAviso.ERRO else TomAviso.SUCESSO, rotuloAcao = "Fechar", acao = vm::fecharAviso) }
        when {
            ui.carregando -> CarregandoLinha("Carregando PDV…")
            ui.caixa == null -> GateCaixa(vm)
            largo -> Row(Modifier.fillMaxSize()) {
                Catalogo(ui, vm, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(Modifier.fillMaxHeight())
                CarrinhoView(ui, vm, onDesconto = { descontoAberto = true }, onFecharCaixa = { fecharCaixaConfirm = true }, modifier = Modifier.width(380.dp).fillMaxHeight())
            }
            else -> Column(Modifier.fillMaxSize()) {
                Catalogo(ui, vm, Modifier.weight(1f).fillMaxWidth())
                Button(onClick = { carrinhoAberto = true }, modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 56.dp)) {
                    Text("Carrinho (${qtd(ui.carrinho.quantidadeDeItens)}) — ${formatarMoeda(ui.carrinho.total())}")
                }
            }
        }
    }

    if (carrinhoAberto && !largo) ModalBottomSheet(onDismissRequest = { carrinhoAberto = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CarrinhoView(ui, vm, onDesconto = { descontoAberto = true }, onFecharCaixa = { carrinhoAberto = false; fecharCaixaConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp))
    }
    if (fecharCaixaConfirm) AlertDialog(
        onDismissRequest = { fecharCaixaConfirm = false }, title = { Text("Fechar o caixa?") },
        text = { Text("Novas vendas só começam depois de abrir um novo caixa. O fechamento é enviado ao Ritmo na sincronização.") },
        confirmButton = { Button(onClick = { fecharCaixaConfirm = false; vm.fecharCaixa() }) { Text("Fechar caixa") } },
        dismissButton = { TextButton(onClick = { fecharCaixaConfirm = false }) { Text("Voltar") } }
    )
    if (descontoAberto) DialogDesconto(onConfirmar = { t, v -> vm.descontoGeral(t, v); descontoAberto = false }, onFechar = { descontoAberto = false })
    ui.checkout?.let { DialogCheckout(ui, it, vm) }
    ui.concluida?.let { op ->
        AlertDialog(
            onDismissRequest = vm::fecharConclusao, title = { Text("Venda registrada") },
            text = { Text("Total ${formatarMoeda(op.payload["total"]?.toString()?.toDoubleOrNull() ?: 0.0)}. " + (if (ui.online == true) "Sincronizando com o Ritmo agora." else "Sem internet — fica salva neste terminal e sincroniza sozinha.") + " Só é confirmada de verdade quando o Ritmo responder: acompanhe pela fila.") },
            confirmButton = { Button(onClick = vm::fecharConclusao) { Text("Nova venda") } }
        )
    }
    if (ui.filaAberta) FilaSheet(ui, vm)
    if (ui.pinAberto) DialogPin(onConfirmar = vm::definirPin, onFechar = vm::fecharPin)
}

@Composable
private fun BarraEstado(ui: PdvUi, vm: PdvViewModel, usuario: Usuario) {
    // Estado de conexão sempre em texto (nunca só cor).
    val conexao = when (ui.online) { true -> "Online"; false -> "Offline — vendas ficam salvas neste terminal"; null -> "Verificando conexão…" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conexao, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = vm::alternarFila, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Fila: ${ui.pendentes} pendente(s)" + if (ui.rejeitadas > 0) ", ${ui.rejeitadas} rejeitada(s)" else "")
            }
        }
        if (usuario.offline) Text("Modo offline (PIN) — a sincronização exige entrar online.", style = MaterialTheme.typography.bodySmall)
        if (ui.ultimoSync?.sessaoExpirada == true) Text("Sessão do Ritmo expirada: entre online para sincronizar a fila.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (ui.divergencias > 0) Text("${ui.divergencias} produto(s) tiveram o estoque ajustado ao sincronizar com o Ritmo.", style = MaterialTheme.typography.bodySmall)
        if (!usuario.offline && Perfis.OFFLINE.isNotEmpty()) TextButton(onClick = vm::abrirPin, modifier = Modifier.heightIn(min = 48.dp)) { Text("Definir meu PIN offline") }
    }
}

@Composable
private fun GateCaixa(vm: PdvViewModel) {
    var troco by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Caixa fechado", style = MaterialTheme.typography.headlineSmall)
        Text("Informe o troco inicial para abrir o caixa. A abertura é enviada ao Ritmo na sincronização (também offline).")
        OutlinedTextField(troco, { troco = it }, label = { Text("Troco inicial (R$)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.abrirCaixa(troco) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Abrir caixa") }
    }
}

@Composable
private fun Catalogo(ui: PdvUi, vm: PdvViewModel, modifier: Modifier) {
    Column(modifier) {
        OutlinedTextField(ui.busca, vm::buscar, label = { Text("Buscar produto por nome ou código") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
        if (ui.produtos.isEmpty()) Text("Catálogo vazio neste terminal. Conecte à internet para sincronizar os produtos do Ritmo.", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ui.visiveis, key = { it.itemCode }) { p -> LinhaProduto(p, ui) { vm.adicionar(p) } }
        }
    }
}

@Composable
private fun LinhaProduto(p: ProdutoPdv, ui: PdvUi, onAdd: () -> Unit) {
    val noCarrinho = ui.carrinho.itens.filter { it.itemCode == p.itemCode }.sumOf { it.qtd }
    val esgotado = p.estoque != null && noCarrinho >= p.estoque
    Column(
        Modifier.fillMaxWidth().clickable(enabled = !esgotado, role = Role.Button, onClickLabel = "Adicionar ${p.nome} ao carrinho", onClick = onAdd)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.nome, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(formatarMoeda(p.preco), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            (if (p.estoque == null) "Estoque desconhecido" else if (esgotado) "Esgotado" else "Estoque: ${qtd(p.estoque)}") + " · ${p.itemCode}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    HorizontalDivider()
}

@Composable
private fun CarrinhoView(ui: PdvUi, vm: PdvViewModel, onDesconto: () -> Unit, onFecharCaixa: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Carrinho", style = MaterialTheme.typography.titleLarge)
        if (ui.carrinho.vazio) Text("Busque ou toque em um produto para começar")
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.carrinho.itens, key = { it.chave }) { i -> LinhaCarrinho(i, vm) }
        }
        ui.carrinho.descontoGeral?.let { Text("Desconto geral: " + if (it.tipo == "percentual") "${qtd(it.valor)}%" else formatarMoeda(it.valor)) }
        Text("Total: ${formatarMoeda(ui.carrinho.total())}", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDesconto, enabled = !ui.carrinho.vazio, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Desconto") }
            OutlinedButton(onClick = onFecharCaixa, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Fechar caixa") }
        }
        Button(onClick = vm::abrirCheckout, enabled = !ui.carrinho.vazio, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Finalizar venda") }
    }
}

@Composable
private fun LinhaCarrinho(i: ItemCarrinho, vm: PdvViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(i.frase ?: i.nome, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { vm.alterarQtd(i.chave, -1.0) }, enabled = !i.pesavel, modifier = Modifier.heightIn(min = 48.dp)) { Text("−") }
                Text(qtd(i.qtd) + if (i.pesavel) " kg" else "", Modifier.padding(horizontal = 8.dp))
                OutlinedButton(onClick = { vm.alterarQtd(i.chave, 1.0) }, enabled = !i.pesavel, modifier = Modifier.heightIn(min = 48.dp)) { Text("+") }
                Text(formatarMoeda(i.valorComAjuste), Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { vm.remover(i.chave) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remover") }
            }
        }
    }
}

@Composable
private fun DialogDesconto(onConfirmar: (String?, String) -> Unit, onFechar: () -> Unit) {
    var tipo by rememberSaveable { mutableStateOf("percentual") }
    var valor by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onFechar, title = { Text("Desconto geral") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tipo == "percentual", { tipo = "percentual" }, label = { Text("Percentual (%)") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(tipo == "valor", { tipo = "valor" }, label = { Text("Valor (R$)") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                OutlinedTextField(valor, { valor = it }, label = { Text("Valor do desconto") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Deixe em branco e confirme para remover o desconto.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onConfirmar(tipo, valor) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onFechar) { Text("Cancelar") } }
    )
}

@Composable
private fun DialogCheckout(ui: PdvUi, ck: Checkout, vm: PdvViewModel) {
    val total = ui.carrinho.total()
    val recebido = ck.recebido.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = { if (!ck.enviando) vm.fecharCheckout() }, title = { Text("Pagamento — ${formatarMoeda(total)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pagamento conferido manualmente pelo operador (a cobrança Pix integrada ainda não está disponível no app).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("dinheiro" to "Dinheiro", "cartao" to "Cartão", "pix" to "Pix").forEach { (k, r) ->
                        FilterChip(ck.metodo == k, { vm.checkoutMetodo(k) }, label = { Text(r) }, enabled = !ck.enviando, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                if (ck.metodo == "dinheiro") {
                    OutlinedTextField(ck.recebido, vm::checkoutRecebido, label = { Text("Valor recebido (R$)") }, singleLine = true, enabled = !ck.enviando, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (recebido != null && recebido >= total) Text("Troco: ${formatarMoeda(recebido - total)}", style = MaterialTheme.typography.titleMedium)
                }
                ck.erro?.let { Aviso(it, TomAviso.ERRO) }
            }
        },
        confirmButton = { Button(onClick = vm::confirmarVenda, enabled = !ck.enviando, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (ck.enviando) "Registrando…" else "Confirmar venda") } },
        dismissButton = { TextButton(onClick = vm::fecharCheckout, enabled = !ck.enviando) { Text("Voltar") } }
    )
}

@Composable
private fun DialogPin(onConfirmar: (String, String) -> Unit, onFechar: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var conf by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onFechar, title = { Text("Definir PIN offline") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("6 dígitos. Serve para entrar neste caixa sem internet.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(conf, { if (it.length <= 6 && it.all(Char::isDigit)) conf = it }, label = { Text("Confirmar PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        },
        confirmButton = { Button(onClick = { onConfirmar(pin, conf) }) { Text("Salvar PIN") } },
        dismissButton = { TextButton(onClick = onFechar) { Text("Cancelar") } }
    )
}

private fun rotuloTipo(t: String) = when (t) {
    TipoOp.ABERTURA -> "Abertura de caixa"; TipoOp.FECHAMENTO -> "Fechamento de caixa"; TipoOp.VENDA -> "Venda"
    TipoOp.PEDIDO_VENDA -> "Pedido de venda"; TipoOp.PRODUCAO_STATUS -> "Status de produção"; else -> t
}

private fun rotuloStatus(s: String) = when (s) {
    StatusOp.PENDENTE -> "Pendente"; StatusOp.ENVIANDO -> "Enviando"; StatusOp.CONFIRMADO -> "Confirmada no Ritmo"; else -> "Rejeitada pelo Ritmo"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilaSheet(ui: PdvUi, vm: PdvViewModel) {
    ModalBottomSheet(onDismissRequest = vm::alternarFila, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fila de sincronização", style = MaterialTheme.typography.titleLarge)
            Button(onClick = vm::sincronizarAgora, enabled = !ui.sincronizando && ui.online == true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (ui.sincronizando) "Sincronizando…" else "Sincronizar agora") }
            if (ui.online != true) Text("Sem conexão com o Ritmo: a fila sincroniza sozinha quando a internet voltar.", style = MaterialTheme.typography.bodySmall)
            if (ui.fila.isEmpty()) Text("Nenhuma operação registrada neste terminal.")
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ui.fila.sortedByDescending { it.criadoEm }, key = { it.opId }) { op -> LinhaFila(op, vm) }
            }
        }
    }
}

@Composable
private fun LinhaFila(op: OperacaoFila, vm: PdvViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${rotuloTipo(op.tipo)} — ${rotuloStatus(op.status)}", style = MaterialTheme.typography.titleSmall)
            Text(op.criadoEm.take(19).replace('T', ' ') + (if (op.tentativas > 0) " · ${op.tentativas} tentativa(s)" else ""), style = MaterialTheme.typography.bodySmall)
            op.ultimoErro?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (op.status == StatusOp.REJEITADO) OutlinedButton(onClick = { vm.reenviar(op.opId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reenviar") }
        }
    }
}
