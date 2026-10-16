#!/usr/bin/env bash
# Type-check (kotlinc) de AppContainer, SessionViewModel e dos ViewModels de tela contra STUBS mínimos de AndroidX/Android.
# Pega erros de tipo/suspend/assinatura no código do app; NÃO valida a API real do AndroidX/Compose, nem as telas Compose.
set -euo pipefail
cd "$(dirname "$0")/.."
W="${DEPS_DIR:-/tmp/ks}"; KC="${KOTLINC:-/tmp/kotlinc}"; LIB="$KC/lib"; OUT=build/vmcheck; rm -rf "$OUT"; mkdir -p "$OUT"
DEPS="$LIB/kotlin-stdlib.jar:$LIB/kotlinx-coroutines-core-jvm.jar:$W/out:$W/okio-out:$W/okhttp-out"
M=app/src/main/java/me/nomen/synapse
MAIN=$(find $M/data $M/domain $M/pdv -name '*.kt' ! -name SecureStore.kt ! -name SqlitePdvStore.kt)
VMS="$M/AppContainer.kt $M/ui/SessionViewModel.kt $(find $M/ui -name '*ViewModel.kt' ! -name SessionViewModel.kt)"
"$KC/bin/kotlinc" -nowarn -language-version 2.0 -Xplugin="$LIB/kotlinx-serialization-compiler-plugin.jar" -cp "$DEPS" -d "$OUT" $MAIN $VMS scripts/stubs/*.kt
echo "TYPE-CHECK OK (ViewModels + AppContainer, contra stubs)"
