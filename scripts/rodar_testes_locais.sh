#!/usr/bin/env bash
# Compila (kotlinc 2.0.20) a camada de dados/domínio/PDV e EXECUTA todos os testes JVM do projeto com JUnit.
# Não compila: UI Compose, SecureStore, SqlitePdvStore, AppContainer, MainActivity (dependem do Android SDK).
# Pré-requisito: ./scripts/construir_dependencias_locais.sh (uma vez) e `apt install junit4 libhamcrest-java`.
set -euo pipefail
cd "$(dirname "$0")/.."
W="${DEPS_DIR:-/tmp/ks}"; KC="${KOTLINC:-/tmp/kotlinc}"; OUT=build/localtest; rm -rf "$OUT"; mkdir -p "$OUT"
J=/usr/share/java; LIB="$KC/lib"
DEPS="$LIB/kotlin-stdlib.jar:$LIB/kotlinx-coroutines-core-jvm.jar:$W/out:$W/okio-out:$W/okhttp-out:$W/mock-out:$J/junit4.jar:$J/hamcrest-core.jar:$LIB/kotlin-test.jar:$LIB/kotlin-test-junit.jar"
M=app/src/main/java/me/nomen/synapse
MAIN=$(find $M/data $M/domain $M/pdv -name '*.kt' ! -name SecureStore.kt ! -name SqlitePdvStore.kt)
TESTS=$(find app/src/test -name '*.kt')
"$KC/bin/kotlinc" -nowarn -language-version 2.0 -Xplugin="$KC/lib/kotlinx-serialization-compiler-plugin.jar" -cp "$DEPS" -d "$OUT" $MAIN $TESTS
CLASSES=$(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort | tr '\n' ' ')
echo "Classes de teste: $CLASSES"
java -cp "$OUT:$DEPS" org.junit.runner.JUnitCore $CLASSES
