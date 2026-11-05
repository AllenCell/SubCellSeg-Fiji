#!/usr/bin/env bash
# Build MegaSeg_Fiji-<version>.jar without Maven, using JAVA_HOME or the JDK bundled with Fiji.
#   ./build.sh [/path/to/Fiji.app] [--install]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIJI="${1:-}"
INSTALL=0
for a in "$@"; do [[ "$a" == "--install" ]] && INSTALL=1; done
[[ "$FIJI" == "--install" || -z "$FIJI" ]] && FIJI="${FIJI_HOME:-/Applications/Fiji.app}"
[[ -d "$FIJI/jars" ]] || { echo "Fiji not found at '$FIJI'. Pass the path to Fiji.app as first argument."; exit 1; }

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
else
  JAVAC="$(find "$FIJI/java" -type f -name javac 2>/dev/null | head -n1 || true)"
fi
[[ -n "$JAVAC" ]] || { echo "No javac found. Install a JDK and set JAVA_HOME."; exit 1; }
JAR="$(dirname "$JAVAC")/jar"
echo "Using javac: $JAVAC"

VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | sed -n 2p)"
BUILD="$ROOT/build"; CLASSES="$BUILD/classes"
rm -rf "$BUILD"; mkdir -p "$CLASSES"

CP="$FIJI/jars/*:$FIJI/plugins/*"
find "$ROOT/src/main/java" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$CP" -d "$CLASSES" @"$BUILD/sources.txt"

cp -r "$ROOT/src/main/resources/." "$CLASSES/"
[[ -f "$CLASSES/META-INF/json/org.scijava.plugin.Plugin" ]] || { echo "SciJava annotation index missing"; exit 1; }

JARPATH="$BUILD/MegaSeg_Fiji-$VERSION.jar"
"$JAR" cf "$JARPATH" -C "$CLASSES" .
echo "Built $JARPATH"

if [[ $INSTALL -eq 1 ]]; then
  rm -f "$FIJI"/plugins/MegaSeg_Fiji-*.jar
  cp "$JARPATH" "$FIJI/plugins/"
  echo "Installed into $FIJI/plugins (restart Fiji)"
fi
