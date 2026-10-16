package me.nomen.synapse.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.nomen.synapse.domain.Aba

/**
 * Área do painel ainda NÃO portada para o app. Não exibe dados fictícios: apenas informa que a
 * funcionalidade existe no painel web e será entregue em etapa posterior (ver docs/MATRIZ_FUNCIONALIDADES.md).
 */
@Composable
fun EmPreparoScreen(aba: Aba) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(aba.titulo, style = MaterialTheme.typography.headlineSmall)
        Text("Esta área ainda não foi implementada no aplicativo.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Ela existe no Painel web e será portada em uma próxima etapa. Até lá, use o Painel para esta função.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
