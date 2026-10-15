# Permissões Android

| Permissão | Justificativa |
|---|---|
| `android.permission.INTERNET` | Única permissão da Etapa 1: comunicação HTTPS com Ritmo e Harmonia. |

Configurações de segurança: `usesCleartextTraffic=false` (somente HTTPS), `allowBackup=false` (cookies e domínio não vão para backup na nuvem),
cookies de sessão e domínio em `EncryptedSharedPreferences` (Android Keystore). `ACCESS_NETWORK_STATE`, câmera (leitor de código de barras)
e acesso a arquivos (anexos) serão adicionados **somente** quando as funcionalidades que os exigem forem implementadas.
