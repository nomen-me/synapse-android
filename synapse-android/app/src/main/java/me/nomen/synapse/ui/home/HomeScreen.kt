package me.nomen.synapse.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.nomen.synapse.domain.Aba
import me.nomen.synapse.domain.Perfis
import me.nomen.synapse.domain.Usuario
import me.nomen.synapse.data.ClasseStatus
import me.nomen.synapse.ui.atendimento.AtendimentoScreen
import me.nomen.synapse.ui.consumo.ConsumoScreen
import me.nomen.synapse.ui.dashboard.DashboardScreen
import me.nomen.synapse.ui.pedidos.PedidosScreen

private fun icone(aba: Aba): ImageVector = when (aba) {
    Aba.DASHBOARD -> Icons.Default.Home
    Aba.PEDIDOS -> Icons.AutoMirrored.Filled.List
    Aba.PDV -> Icons.Default.ShoppingCart
    Aba.PRODUCAO -> Icons.Default.Build
    Aba.UNIDADES -> Icons.Default.Place
    Aba.ATENDIMENTO -> Icons.Default.Email
    Aba.CADASTROS -> Icons.Default.Person
    Aba.FISCAL -> Icons.Default.DateRange
    Aba.INTEGRACOES -> Icons.Default.Settings
    Aba.MARKETING -> Icons.Default.Notifications
    Aba.TEMA -> Icons.Default.Edit
    Aba.CONSUMO -> Icons.Default.Info
}

private const val MAX_ABAS_NA_BARRA = 4

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(usuario: Usuario, onSair: () -> Unit) {
    val abas = remember(usuario.perfil) { Perfis.abasPermitidas(usuario.perfil) }
    var idSelecionada by rememberSaveable { mutableStateOf(abas.first().id) }
    val selecionada = Aba.porId(idSelecionada)?.takeIf { it in abas } ?: abas.first()
    val largo = LocalConfiguration.current.screenWidthDp >= 600
    var filtroPedidos by remember { mutableStateOf<ClasseStatus?>(null) }
    var menuUsuario by remember { mutableStateOf(false) }
    var mostrarMais by rememberSaveable { mutableStateOf(false) }
    val tecladoVisivel = WindowInsets.isImeVisible

    val principais = if (abas.size > MAX_ABAS_NA_BARRA + 1) abas.take(MAX_ABAS_NA_BARRA) else abas
    val excedentes = abas - principais.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selecionada.titulo) },
                actions = {
                    Box {
                        IconButton(onClick = { menuUsuario = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu do usuário ${usuario.nome}")
                        }
                        DropdownMenu(expanded = menuUsuario, onDismissRequest = { menuUsuario = false }) {
                            DropdownMenuItem(text = { Column { Text(usuario.nome); Text(usuario.perfil, style = MaterialTheme.typography.labelMedium) } }, onClick = {}, enabled = false)
                            DropdownMenuItem(text = { Text("Sair") }, onClick = { menuUsuario = false; onSair() }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Com o teclado aberto a barra inferior sairia por cima do campo de texto; some enquanto ele estiver visível.
            if (!largo && !tecladoVisivel) {
                NavigationBar {
                    principais.forEach { aba ->
                        NavigationBarItem(
                            selected = aba == selecionada, onClick = { idSelecionada = aba.id },
                            icon = { Icon(icone(aba), contentDescription = null) }, label = { Text(aba.titulo, maxLines = 1) }
                        )
                    }
                    if (excedentes.isNotEmpty()) {
                        NavigationBarItem(
                            selected = selecionada in excedentes, onClick = { mostrarMais = true },
                            icon = { Icon(Icons.Default.MoreVert, contentDescription = null) }, label = { Text("Mais") }
                        )
                    }
                }
            }
        }
    ) { interno: PaddingValues ->
        Row(Modifier.fillMaxSize().padding(interno).consumeWindowInsets(interno)) {
            if (largo) {
                NavigationRail {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        abas.forEach { aba ->
                            NavigationRailItem(
                                selected = aba == selecionada, onClick = { idSelecionada = aba.id },
                                icon = { Icon(icone(aba), contentDescription = null) }, label = { Text(aba.titulo, maxLines = 1) }
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (selecionada) {
                    Aba.DASHBOARD -> DashboardScreen(
                        irParaPedidos = { f -> if (Aba.PEDIDOS in abas) { filtroPedidos = f; idSelecionada = Aba.PEDIDOS.id } },
                        irParaAtendimento = { if (Aba.ATENDIMENTO in abas) idSelecionada = Aba.ATENDIMENTO.id }
                    )
                    Aba.PEDIDOS -> PedidosScreen(usuario, filtroPedidos, aoConsumirFiltro = { filtroPedidos = null })
                    Aba.CONSUMO -> ConsumoScreen(usuario)
                    Aba.ATENDIMENTO -> AtendimentoScreen()
                    else -> EmPreparoScreen(selecionada)
                }
            }
        }
    }

    if (mostrarMais) {
        ModalBottomSheet(onDismissRequest = { mostrarMais = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                excedentes.forEach { aba ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { idSelecionada = aba.id; mostrarMais = false }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(icone(aba), contentDescription = null)
                        Text(aba.titulo, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
