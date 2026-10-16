#!/usr/bin/env bash
# Compila, roda os testes JVM e gera o APK debug dentro de um container; coleta TUDO em ./build-evidence/ para anexar ao relatório.
# Requisitos: Docker + internet (Maven/Google). Uso (na raiz do projeto): ./build-env/build.sh
set -uo pipefail
cd "$(dirname "$0")/.."
EV=build-evidence; rm -rf "$EV"; mkdir -p "$EV"
{ echo "data: $(date -u '+%Y-%m-%d %H:%M UTC')"; echo "host: $(uname -srm)"; docker --version; git rev-parse HEAD 2>/dev/null | sed 's/^/commit: /'; } > "$EV/ambiente.txt" 2>&1
( docker build -t synapse-android-build build-env && docker run --rm -v "$PWD":/work synapse-android-build ) 2>&1 | tee "$EV/build.log"
STATUS=${PIPESTATUS[0]}
[ -d app/build/reports/tests ] && cp -r app/build/reports/tests "$EV/relatorio-testes-html" 2>/dev/null
[ -d app/build/test-results ] && cp -r app/build/test-results "$EV/test-results-xml" 2>/dev/null
APK=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
if [ -n "${APK:-}" ]; then cp "$APK" "$EV/"; ( cd "$EV" && sha256sum "$(basename "$APK")" > SHA256SUMS ); fi
{ echo "STATUS_DO_BUILD=$STATUS"; echo "APK=${APK:-NAO_GERADO}"; } > "$EV/resultado.txt"
cat "$EV/resultado.txt"; echo "Evidências em: $EV/ (build.log, relatórios de teste, APK, SHA256SUMS)"
exit "$STATUS"
