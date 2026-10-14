package me.nomen.synapse.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
private fun ErroInline(texto: String) {
    // Ícone + texto (estado não depende só de cor); liveRegion faz o TalkBack anunciar o erro.
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.Warning, contentDescription = "Erro", tint = MaterialTheme.colorScheme.error)
        Text(texto, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColunaCentralizada(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
fun DominioScreen(erro: String?, onConfirmar: (String) -> Unit) {
    var dominio by rememberSaveable { mutableStateOf("") }
    ColunaCentralizada {
        Text("Synapse", style = MaterialTheme.typography.headlineLarge)
        Text("Informe o endereço da sua loja. O app usa o mesmo servidor do Painel.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = dominio, onValueChange = { dominio = it }, singleLine = true,
            label = { Text("Endereço da loja") }, placeholder = { Text("minhaloja.com.br") },
            supportingText = { Text("Somente o domínio, sem http:// (conexão sempre segura).") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConfirmar(dominio) }),
            modifier = Modifier.fillMaxWidth()
        )
        if (erro != null) ErroInline(erro)
        Button(onClick = { onConfirmar(dominio) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continuar") }
    }
}

@Composable
fun LoginScreen(dominio: String, erro: String?, entrando: Boolean, onEntrar: (String, String) -> Unit, onTrocarLoja: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var senha by rememberSaveable { mutableStateOf("") }
    var mostrarSenha by rememberSaveable { mutableStateOf(false) }
    val foco = LocalFocusManager.current

    ColunaCentralizada {
        Text("Entrar", style = MaterialTheme.typography.headlineLarge)
        Text("Loja: $dominio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = email, onValueChange = { email = it }, singleLine = true, enabled = !entrando,
            label = { Text("E-mail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { foco.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = senha, onValueChange = { senha = it }, singleLine = true, enabled = !entrando,
            label = { Text("Senha") },
            visualTransformation = if (mostrarSenha) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { foco.clearFocus(); onEntrar(email, senha) }),
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { mostrarSenha = !mostrarSenha }) { Text(if (mostrarSenha) "Ocultar senha" else "Mostrar senha") }
        if (erro != null) ErroInline(erro)
        Button(
            onClick = { foco.clearFocus(); onEntrar(email, senha) }, enabled = !entrando,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            if (entrando) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text("Entrando…")
            } else Text("Entrar")
        }
        TextButton(onClick = onTrocarLoja, enabled = !entrando) { Text("Trocar de loja") }
    }
}
