# Arquivos criados (projeto novo; nenhum arquivo do repositório `desenvolvimento` foi alterado)

Raiz: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/gradle-wrapper.properties`, `.gitignore`
`app/`: `build.gradle.kts`, `proguard-rules.pro`, `src/main/AndroidManifest.xml`, `res/values/{strings,themes}.xml`
`app/src/main/java/me/nomen/synapse/`: `MainActivity.kt`, `AppContainer.kt`;
  `data/` Erros, Http, SynapseEnv, SecureStore, PersistentCookieJar, RitmoClient, HarmoniaClient, AuthRepository, AtendimentoRepository;
  `domain/Perfis.kt`; `ui/` SessionViewModel, SynapseRoot, theme/Theme, login/LoginScreens, home/{HomeScreen,EmPreparoScreen}, atendimento/{AtendimentoViewModel,AtendimentoScreen}
`app/src/test/...`: `DominioEPerfisTest`, `RitmoClientTest`, `AtendimentoRepositoryTest`
`docs/`: LEIA-ME, MATRIZ_FUNCIONALIDADES, PERMISSOES_ANDROID, RELATORIO_TESTES, ARQUIVOS
Pendente: `gradle-wrapper.jar`/`gradlew` (não gerável sem rede), ícone do app, APK/AAB.

## Etapa 2 (novos)
`data/`: ConfigRepository, PedidosRepository (inclui PedidosStore), DashboardCalculos; `domain/Formatacao.kt` (e `Perfis.acaoPermitida`);
`ui/Componentes.kt`, `ui/pedidos/{PedidosViewModel,PedidosScreen}`, `ui/dashboard/{DashboardViewModel,DashboardScreen}`;
`app/src/test/.../PedidosTest.kt`; `.github/workflows/android.yml`; `build-env/{Dockerfile,build.sh}`.
Alterados: AppContainer, SessionViewModel, HomeScreen, Http.kt (helper `num`), Perfis.

## Etapa 3 (novos)
`pdv/`: PinCrypto (+PinLockout), PdvModelos, PdvStore (SqlitePdvStore), PdvConfigRepository, PdvSincronizador;
`app/src/test/.../PdvTest.kt`. Alterados: AppContainer, SessionViewModel (troca de loja bloqueada com PDV pendente), RitmoClient (sincronizarCaixa, definirPin, ritmoAlcancavel).
