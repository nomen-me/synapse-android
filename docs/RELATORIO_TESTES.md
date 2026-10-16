# Relatório de testes — resultados observados

Última execução: ver `RESULTADO_TESTES_LOCAIS.txt` (saída bruta). Reproduzir: `./scripts/construir_dependencias_locais.sh` (uma vez) e `./scripts/rodar_testes_locais.sh`.

## 1. EXECUTADO e APROVADO (observado)
**92 testes JVM, 0 falhas** (compilados e executados também com o compilador K2, o mesmo do build Gradle) (JUnit 4.13.2, JDK 21, kotlinc 2.0.20): `AtendimentoRepositoryTest`, `CarrinhoTest`, `ConsumoTest`, `CookieJarTest`, `DominioEPerfisTest`, `LoginOfflineTest`, `PdvFluxoCompletoTest`, `PdvSincronizadorTest` (inclui `PinCryptoTest`), `PedidosTest`, `RitmoClientTest`.
Cobrem: login/CSRF/retry de GET e não-retry de POST, sessão inválida (401/403), contratos do Atendimento, Pedidos (lista, cancelamento via Lyra, criação), cálculos do Dashboard, Consumo (percentuais, 501 como pendência), perfis/permissões, PIN (vetores PBKDF2-SHA256 + bloqueio 5×/60 s) e o sincronizador do PDV (abertura→venda→fechamento em ordem, idempotência, rede/sessão/rejeição/duplicado, acréscimo sem conta, pedido de venda).
**Fluxos do PDV cobertos:** dia completo offline→online (abertura, 2 vendas, fechamento, ordem e referências), clique duplo no finalizar (sem duplicar venda nem baixar estoque duas vezes), queda do app no meio do envio (reenvio com o mesmo `synapse_op_id`, sem criar outra venda), rejeição + reenvio manual, rede caindo no meio da fila, pedido de venda, catálogo (Item/Bin, paginação de 50, divergência de estoque, falha no Bin, offline), login offline por PIN (bloqueio 5×/60 s, formato inválido não conta, sem PIN, sem restrição), cookie jar (persistência, expiração, Secure/host, dado corrompido).

Os testes usam um servidor HTTP simulado (MockWebServer) — **não** o Ritmo/Harmonia reais.

Defeitos encontrados e corrigidos pela execução: (1) o `Dispatcher` do servidor simulado dos testes lançava exceção em requisições sem URL (DISCONNECT_AT_START) e derrubava a thread do servidor — erro do teste; (2) o verificador `verificar_ritmo.py` codificava a URL duas vezes e não achava `Custom Field` (corrigido, reexecutado contra Ritmo simulado). Defeito anterior: um teste do sincronizador não simulava a queda de rede (o `Dispatcher` customizado precisava sobrescrever `peek()`); era erro do teste, não do código de produção.

## 1b. Outras verificações executadas
- `scripts/checar_viewmodels.sh`: type-check (K2) de `AppContainer`, `SessionViewModel` e de todos os ViewModels de tela contra **stubs** mínimos de AndroidX — **passou** (e pegou um erro real antes: classes de estado sem `data class`). Não valida a API real do AndroidX.
- `node docs/propostas/testar_monta_resposta.js`: 4 cenários do código proposto para o nó "Monta resposta" do workflow de consumo — **passou** (simulação; não é o n8n).

## 2. COMPILADO (type-check real, kotlinc)
`data/` (inclui `PersistentCookieJar`, com o plugin kotlinx-serialization), `domain/`, `pdv/` (exceto `SqlitePdvStore`) — todo o código de produção dessas camadas compila contra: kotlinx.serialization 1.7.3, Okio 3.6.0, OkHttp 4.12.0, MockWebServer 4.12.0.
**Ressalva:** essas bibliotecas foram construídas a partir do código-fonte oficial (tags do GitHub) com ajustes locais (stubs de anotações, exclusão das plataformas Android/Conscrypt/BouncyCastle/OpenJSSE do OkHttp), pois o Maven está bloqueado. Não são os artefatos publicados. O build Gradle oficial ainda precisa confirmar.

## 3. NÃO compilado / NÃO executado
- UI Jetpack Compose (incluindo as telas e o ViewModel do PDV, escritos nesta rodada) e ViewModels, `SecureStore` (inclui a nova recuperação quando o Keystore falha), `SqlitePdvStore` (WAL), `AppContainer`, `MainActivity`, configuração Gradle: exigem Android SDK/AGP/Compose (indisponíveis aqui). Só passaram por análise de sintaxe.
- **APK: não existe.** Testes instrumentados, de UI, TalkBack, fonte ampliada, tablet, teclado: não executados.
- Testes na infraestrutura Synapse real (Ritmo, Harmonia, n8n): **nenhum**.

## 4. Bloqueio externo (único que impede APK e testes de UI)
Evidência: `curl -I` a `dl.google.com`, `maven.google.com`, `repo.maven.apache.org` e `services.gradle.org` → código 000 (sem conexão) neste ambiente; sem Android SDK instalado.
Ação mínima: executar `./build-env/build.sh` numa máquina com Docker e internet (ou rodar o workflow `android.yml` num repositório privado) e devolver o log; o APK debug sai em `app/build/outputs/apk/debug/`.

## 5. A validar na infraestrutura real
Ver `AUDITORIA.md` seção 2 e 4 (scripts do Ritmo, `data.name`, workflows n8n importados e ativos, `restricao_configurada`).
