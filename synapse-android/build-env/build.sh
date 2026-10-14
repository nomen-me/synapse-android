#!/usr/bin/env bash
# Uso (na raiz do projeto): ./build-env/build.sh   — gera o APK e roda os testes JVM dentro de um container.
set -euo pipefail
docker build -t synapse-android-build build-env
docker run --rm -v "$PWD":/work synapse-android-build
echo "APK: app/build/outputs/apk/debug/app-debug.apk   Relatórios: app/build/reports/tests/"
