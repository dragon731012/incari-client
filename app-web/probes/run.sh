#!/usr/bin/env bash
# Compiles one probe (app-web/probes/<Name>.java) with TeaVM against the browser build's class
# library and runs it in headless Chromium until it prints "probe passed" (see README.md,
# Experiments). Takes seconds, not the full build's ten minutes.
#
#   app-web/probes/run.sh SuspendingTernaryProbe
#   app-web/probes/run.sh WebGpuCallProbe -Pbrowser.gpu=webgpu -Pteavm.fast   (more Gradle options)
set -euo pipefail

name="${1:?usage: app-web/probes/run.sh <ProbeName>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
classes="$root/app-web/build/probes/$name"

rm -rf "$classes"
# Probes may use the Netty the browser build keeps (the teavm task puts it on TeaVM's class path);
# javac finds the pinned version in Gradle's cache, which any build of the repo has filled.
netty="$(sed -n 's/^netty = "\(.*\)"/\1/p' "$root/gradle/libs.versions.toml")"
netty_jars=""
for module in netty-common netty-buffer netty-transport netty-codec-base; do
    jar="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name "$module-$netty.jar" | head -n 1)"
    netty_jars="$netty_jars:$jar"
done
# Probes may call TeaVM's interop and JSO APIs, as the page's own code does.
teavm="$(sed -n 's/^teavm = "\(.*\)"/\1/p' "$root/gradle/libs.versions.toml")"
for module in teavm-interop teavm-jso teavm-jso-apis; do
    jar="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name "$module-$teavm.jar" | head -n 1)"
    netty_jars="$netty_jars:$jar"
done
# Probes may use the Via the browser build compiles in.
for library in viaversion viabackwards; do
    version="$(sed -n "s/^$library = \"\(.*\)\"/\1/p" "$root/gradle/libs.versions.toml")"
    jar="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name "$library-common-$version.jar" | head -n 1)"
    netty_jars="$netty_jars:$jar"
done
# Probes may also use Incari's platform classes (StorageProbe installs the file system).
cd "$root"
./gradlew -q :platform-web:classes
# Probes compile for Java 25, as the game does; a newer javac must be told.
javac --release 25 -cp "${netty_jars#:}:$root/platform-web/build/classes/java/main" -d "$classes" \
    "$root/app-web/probes/$name.java"
./gradlew :app-web:browserCheck -Pteavm.main="$name" -Pteavm.classpath="$classes" \
    "-Pbrowser.expect=probe (passed|failed.*)" -Pbrowser.count=1 -Pbrowser.seconds=60 "${@:2}" \
    | tee "$root/app-web/build/probes/$name.log"
grep -q "probe passed" "$root/app-web/build/probes/$name.log"
