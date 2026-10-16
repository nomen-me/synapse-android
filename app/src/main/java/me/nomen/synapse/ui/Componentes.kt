package me.nomen.synapse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class TomAviso { ERRO, SUCESSO, INFO }

/** Aviso com ícone + texto (o estado nunca depende só de cor) e anúncio automático pelo TalkBack. */
@Composable
fun Aviso(texto: String, tom: TomAviso, modifier: Modifier = Modifier, rotuloAcao: String? = null, acao: () -> Unit = {}) {
    val cor = when (tom) { TomAviso.ERRO -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
    val icone = when (tom) { TomAviso.ERRO -> Icons.Default.Warning; TomAviso.SUCESSO -> Icons.Default.Check; TomAviso.INFO -> Icons.Default.Info }
    val desc = when (tom) { TomAviso.ERRO -> "Erro"; TomAviso.SUCESSO -> "Sucesso"; TomAviso.INFO -> "Informação" }
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
        ) {
            Icon(icone, contentDescription = desc, tint = cor)
            Text(texto, color = cor)
        }
        if (rotuloAcao != null) Button(onClick = acao, modifier = Modifier.heightIn(min = 48.dp)) { Text(rotuloAcao) }
    }
}

@Composable
fun CarregandoLinha(texto: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(texto)
    }
}

/** Seletor com busca (listas de até centenas de itens: clientes, fornecedores, produtos). */
@Composable
fun SeletorDialog(titulo: String, opcoes: List<Pair<String, String>>, onEscolher: (String) -> Unit, onFechar: () -> Unit) {
    var busca by rememberSaveable { mutableStateOf("") }
    val filtradas = remember(busca, opcoes) {
        if (busca.isBlank()) opcoes else opcoes.filter { (id, nome) -> nome.contains(busca, true) || id.contains(busca, true) }
    }
    AlertDialog(
        onDismissRequest = onFechar, title = { Text(titulo) },
        text = {
            Column {
                OutlinedTextField(busca, { busca = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (filtradas.isEmpty()) Text("Nada encontrado.", Modifier.padding(top = 12.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtradas, key = { it.first }) { (id, nome) ->
                        Text(
                            nome, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onEscolher(id) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onFechar) { Text("Fechar") } }
    )
}
