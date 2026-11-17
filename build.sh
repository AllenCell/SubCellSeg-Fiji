#!/usr/bin/env bash
# Build SubCellSeg_Fiji-<version>.jar without Maven, using JAVA_HOME or the JDK bundled with Fiji.
#   ./build.sh [/path/to/Fiji.app] [--install]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIJI="${1:-}"
INSTALL=0
for a in "$@"; do [[ "$a" == "--install" ]] && INSTALL=1; done
[[ "$FIJI" == "--install" || -z "$FIJI" ]] && FIJI="${FIJI_HOME:-/Applications/Fiji.app}"
[[ -d "$FIJI/jars" ]] || { echo "Fiji not found at '$FIJI'. Pass the path to Fiji.app as first argument."; exit 1; }

JAVAC=""
for c in "${JAVA_HOME:-/nonexistent}/bin/javac" "${JAVA_HOME:-/nonexistent}/bin/javac.exe"; do
  [[ -f "$c" ]] && { JAVAC="$c"; break; }
done
if [[ -z "$JAVAC" ]]; then
  JAVAC="$(find "$FIJI/java" -type f \( -name javac -o -name javac.exe \) 2>/dev/null | head -n1 || true)"
fi
if [[ -z "$JAVAC" ]]; then
  JAVAC="$(command -v javac || true)"
fi
[[ -n "$JAVAC" ]] || { echo "No javac found. Install a JDK and set JAVA_HOME."; exit 1; }
JAR="$(dirname "$JAVAC")/jar"; [[ -f "$JAR" ]] || JAR="$JAR.exe"
# Git Bash on Windows: javac/jar are Windows programs, so hand them Windows paths.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";"; winpath() { cygpath -w "$1"; } ;;
  *) SEP=":"; winpath() { printf '%s' "$1"; } ;;
esac
echo "Using javac: $JAVAC"

VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | sed -n 2p)"
BUILD="$ROOT/build"; CLASSES="$BUILD/classes"
rm -rf "$BUILD"; mkdir -p "$CLASSES"

CP="$(winpath "$FIJI/jars")/*${SEP}$(winpath "$FIJI/plugins")/*"
find "$ROOT/src/main/java" -name '*.java' | while read -r f; do winpath "$f"; echo; done > "$BUILD/sources.txt"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$CP" -d "$(winpath "$CLASSES")" @"$(winpath "$BUILD/sources.txt")"

cp -r "$ROOT/src/main/resources/." "$CLASSES/"
[[ -f "$CLASSES/META-INF/json/org.scijava.plugin.Plugin" ]] || { echo "SciJava annotation index missing"; exit 1; }

JARPATH="$BUILD/SubCellSeg_Fiji-$VERSION.jar"
"$JAR" cf "$(winpath "$JARPATH")" -C "$(winpath "$CLASSES")" .
echo "Built $JARPATH"

if [[ $INSTALL -eq 1 ]]; then
  rm -f "$FIJI"/plugins/SubCellSeg_Fiji-*.jar
  cp "$JARPATH" "$FIJI/plugins/"
  echo "Installed into $FIJI/plugins (restart Fiji)"
fi
