package me.nomen.synapse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.nomen.synapse.ui.home.HomeScreen
import me.nomen.synapse.ui.login.DominioScreen
import me.nomen.synapse.ui.login.LoginScreen

@Composable
fun SynapseRoot(sessao: SessionViewModel = viewModel(factory = SessionViewModel.Factory)) {
    val estado by sessao.estado.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val e = estado) {
            SessionState.Verificando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Verificando sessão" })
                    Text("Verificando sessão…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is SessionState.SemDominio -> DominioScreen(erro = e.erro, onConfirmar = sessao::configurarDominio)
            is SessionState.Deslogado -> LoginScreen(
                dominio = e.dominio, erro = e.erro, entrando = e.entrando,
                onEntrar = sessao::login, onTrocarLoja = sessao::trocarDominio
            )
            is SessionState.Logado -> HomeScreen(usuario = e.usuario, onSair = sessao::logout)
        }
    }
}
