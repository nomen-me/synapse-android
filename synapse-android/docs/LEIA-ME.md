# Synapse Painel — aplicativo Android nativo (Kotlin + Jetpack Compose)

**Estado: ETAPA 3 de N — código-fonte escrito, NÃO COMPILADO, NÃO TESTADO em aparelho.**
Implementado: login/sessão, perfis, Atendimento, Dashboard, Pedidos, motor do PDV offline (PIN, autorização, fila, sincronização). Pendente: telas do PDV, NFC-e/cobrança, pacote de pendências, Produção, Unidades, Cadastros, Fiscal, Integrações, Marketing, Tema, Consumo.
Ver `RELATORIO_TESTES.md` (o que foi e o que não foi verificado) e `MATRIZ_FUNCIONALIDADES.md`.

Base: `synapse_painel_18-09t.html` (repositório `nomen-me/desenvolvimento`). O app é um cliente novo
dos serviços existentes (Ritmo/ERPNext e Harmonia/n8n). Nenhum backend, banco ou endpoint foi criado ou alterado.

## Requisitos
Android Studio Koala (2024.1.1) ou superior, JDK 17, Android SDK 34, acesso à internet para o Gradle.

## Compilar e instalar
1. Abra a pasta do projeto no Android Studio (ele sincroniza o Gradle). Sem Android Studio: `gradle wrapper --gradle-version 8.7`
   (o `gradle-wrapper.jar` não está no pacote) e depois `./gradlew`.
2. APK de depuração: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
   (instalar: `adb install -r app-debug.apk`).
3. Testes JVM: `./gradlew :app:testDebugUnitTest`.
4. Release assinado (fora do git, sob controle da Nomen): crie `keystore.properties` na raiz com
   `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (ou use as variáveis `SYNAPSE_KEYSTORE`,
   `SYNAPSE_KEYSTORE_PASSWORD`, `SYNAPSE_KEY_ALIAS`, `SYNAPSE_KEY_PASSWORD`) e rode
   `./gradlew :app:assembleRelease` (APK) ou `:app:bundleRelease` (AAB). Sem essas variáveis o release sai não assinado.

## Ambiente de build (compilação e testes)
O sandbox de desenvolvimento não alcança Maven/Google, então o build roda fora dele, sem alterar a infraestrutura Synapse:
- **GitHub Actions**: `.github/workflows/android.yml` (testes JVM, lint, APK debug, checagem de segredos no APK, teste instrumentado em emulador; publica APK e relatórios como artefatos). Exige que alguém autorizado coloque o projeto num repositório e dispare o workflow.
- **Docker** (qualquer máquina com Docker e internet): `./build-env/build.sh`.

## Configuração de ambiente
O app pergunta o **domínio da loja** na primeira abertura (ex.: `minhaloja.com.br`) e deriva
`https://ritmo.<domínio>` e `https://harmonia.<domínio>` — a mesma regra do painel web
(`resolverAmbienteSynapse`). Somente HTTPS; "Trocar de loja" limpa sessão e cookies.
Nenhuma chave, token ou senha existe no código ou no pacote.

## Atualizar o app
Incrementar `versionCode`/`versionName` em `app/build.gradle.kts`, recompilar e reinstalar sobre a versão anterior (mesma assinatura).

## Estrutura
- `data/` — clientes Ritmo e Harmonia (OkHttp), cookie jar criptografado, repositórios.
- `domain/` — perfis, abas e permissões (espelham `PERMISSOES_TABS` do painel).
- `ui/` — Compose: login, navegação adaptativa (celular: barra inferior + "Mais"; tablet: NavigationRail), Atendimento.
