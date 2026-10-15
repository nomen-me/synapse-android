#!/usr/bin/env bash
# Constrói, A PARTIR DO CÓDIGO-FONTE OFICIAL (GitHub), as bibliotecas JVM que o projeto usa, para poder compilar e
# executar os testes onde Maven/Google estão bloqueados. Não é o artefato publicado: é build local de tags oficiais.
#   kotlinx.serialization 1.7.3 (core+json) · Okio 3.6.0 · OkHttp 4.12.0 · MockWebServer 4.12.0
# Ajustes locais (somente na cópia baixada, nunca no projeto): stubs de anotações de IDE/animal-sniffer, versão do OkHttp,
# defaults de `expect class IOException` (Okio) e remoção das plataformas Android/Conscrypt/BouncyCastle/OpenJSSE (OkHttp).
set -euo pipefail
W="${DEPS_DIR:-/tmp/ks}"; KC="${KOTLINC:-/tmp/kotlinc}"; STD="$KC/lib/kotlin-stdlib.jar"; mkdir -p "$W"; cd "$W"
if [ ! -d "$KC" ]; then curl -fsSL -o /tmp/kc.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.20/kotlin-compiler-2.0.20.zip && unzip -q -o /tmp/kc.zip -d "$(dirname "$KC")"; fi
fetch() { [ -d "$2" ] || { curl -fsSL -o "$2.tgz" "$1" && tar xzf "$2.tgz"; }; }
fetch https://codeload.github.com/Kotlin/kotlinx.serialization/tar.gz/refs/tags/v1.7.3 kotlinx.serialization-1.7.3
fetch https://codeload.github.com/square/okio/tar.gz/refs/tags/parent-3.6.0 okio-parent-3.6.0
fetch https://codeload.github.com/square/okhttp/tar.gz/refs/tags/parent-4.12.0 okhttp-parent-4.12.0
mkdir -p stub stub2
cat > stub/Language.kt <<'K'
package org.intellij.lang.annotations
@Retention(AnnotationRetention.BINARY) @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class Language(val value: String, val prefix: String = "", val suffix: String = "")
K
cat > stub2/Animal.kt <<'K'
package org.codehaus.mojo.animal_sniffer
@Retention(AnnotationRetention.BINARY)
annotation class IgnoreJRERequirement
K

echo "== kotlinx.serialization"; ( cd kotlinx.serialization-1.7.3; rm -rf "$W/out"; mkdir -p "$W/out"
COMMON="$(find core/commonMain formats/json/commonMain -name '*.kt' | tr '\n' ',' | sed 's/,$//')"
"$KC/bin/kotlinc" -nowarn -Xmulti-platform -Xexpect-actual-classes -Xallow-kotlin-package -Xskip-prerelease-check -Xcommon-sources="$COMMON" \
 -Xopt-in=kotlin.RequiresOptIn -Xopt-in=kotlinx.serialization.InternalSerializationApi -Xopt-in=kotlinx.serialization.ExperimentalSerializationApi \
 -Xopt-in=kotlinx.serialization.json.internal.JsonFriendModuleApi -Xopt-in=kotlinx.serialization.internal.CoreFriendModuleApi \
 -Xplugin="$KC/lib/kotlinx-serialization-compiler-plugin.jar" -cp "$STD" -d "$W/out" \
 $(find core/commonMain core/jvmMain formats/json/commonMain formats/json/jvmMain -name '*.kt') "$W/stub/Language.kt" )

echo "== okio"; ( cd okio-parent-3.6.0; rm -rf "$W/okio-out"; mkdir -p "$W/okio-out"
python3 - <<'P'
import re
p='okio/src/commonMain/kotlin/okio/CommonPlatform.kt'; s=open(p).read()
s=re.sub(r'(expect (?:open )?class (?:IOException|EOFException|FileNotFoundException)\()([^)]*)\)', lambda m: m.group(1)+re.sub(r'\s*=\s*[^,)]+','',m.group(2))+')', s)
s=s.replace('  constructor(message: String? = null)','  constructor(message: String?)')
open(p,'w').write(s)
P
COMMON="$(find okio/src/commonMain okio/src/hashFunctions -name '*.kt' | tr '\n' ',' | sed 's/,$//')"
"$KC/bin/kotlinc" -nowarn -language-version 1.9 -Xmulti-platform -Xexpect-actual-classes -Xallow-kotlin-package -Xskip-prerelease-check -Xcommon-sources="$COMMON" \
 -Xopt-in=kotlin.RequiresOptIn -cp "$STD" -d "$W/okio-out" $(find okio/src/commonMain okio/src/jvmMain okio/src/hashFunctions -name '*.kt') )

echo "== okhttp"; ( cd okhttp-parent-4.12.0; rm -rf "$W/okhttp-out"; mkdir -p "$W/okhttp-out" "$W/okhttp-gen"
sed 's/\${project.version}/4.12.0/; s/\$projectVersion/4.12.0/' okhttp/src/main/java-templates/okhttp3/OkHttp.kt > "$W/okhttp-gen/OkHttp.kt"
python3 - <<'P'
import re
p='okhttp/src/main/kotlin/okhttp3/internal/platform/Platform.kt'; s=open(p).read()
s=s.replace("import okhttp3.internal.platform.android.AndroidLog\n","")
s=re.sub(r"private fun findAndroidPlatform\(\): Platform \{.*?\n    \}\n", "private fun findAndroidPlatform(): Platform = throw IllegalStateException(\"Android excluded from local JVM build\")\n", s, flags=re.S)
for n in ("conscrypt","bc","openJSSE"):
    s=re.sub(r"      if \(is\w+Preferred\) \{\n        val %s = .*?\n      \}\n\n" % n, "", s, flags=re.S, count=1)
open(p,'w').write(s)
P
SRCS="$(find okhttp/src/main/kotlin -name '*.kt' | grep -vE 'platform/(Conscrypt|BouncyCastle|OpenJSSE|Android10|Android)Platform|platform/android/')"
"$KC/bin/kotlinc" -nowarn -language-version 1.9 -Xskip-prerelease-check -Xallow-kotlin-package -cp "$STD:$W/okio-out" -d "$W/okhttp-out" $SRCS "$W/okhttp-gen/OkHttp.kt" "$W/stub2/Animal.kt"
echo "== mockwebserver"; rm -rf "$W/mock-out"; mkdir -p "$W/mock-out"
"$KC/bin/kotlinc" -nowarn -language-version 1.9 -Xskip-prerelease-check -Xallow-kotlin-package -cp "$STD:$W/okio-out:$W/okhttp-out:/usr/share/java/junit4.jar" -d "$W/mock-out" $(find mockwebserver/src/main -name '*.kt') "$W/stub2/Animal.kt" )
echo "OK: dependências locais em $W"
