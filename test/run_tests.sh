#!/usr/bin/env bash
# Build, install and test the SubCellSeg Fiji plugin against a Fiji installation.
# Works on macOS, Linux and Windows (Git Bash), with the current Fiji launcher and the classic one.
#
#   test/run_tests.sh ~/Fiji                                    # build, install, Java-only test, sample test
#                                                              #   (the folder that holds jars/ and plugins/)
#   test/run_tests.sh ~/Fiji.app --python-env ~/SubCellSeg/.venv  # use a specific Python environment
#   test/run_tests.sh ~/Fiji.app --setup [SOURCE]              # also install SubCellSeg via the Setup command
#                                                              #   (SOURCE = archive URL or local source folder)
#   test/run_tests.sh ~/Fiji.app --skip-build                  # test the jar that is already installed
#   test/run_tests.sh ~/Fiji.app --setup --install-dir DIR     # Setup with a custom "Install location"
#
# Exit code 0 when every test printed its OK marker.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Git Bash on Windows: Fiji is a Windows program, so give it D:/... paths rather than /d/... ones.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ROOT="$(cygpath -m "$ROOT")" ;; esac
FIJI="${1:-}"
[[ -n "$FIJI" && -d "$FIJI" ]] || { echo "usage: $0 <Fiji.app> [--python-env DIR] [--setup [SOURCE]] [--install-dir DIR] [--skip-build]"; exit 2; }
shift

PYENV=""; SETUP=0; SOURCE="https://github.com/AllenCell/SubCellSeg/archive/refs/heads/main.zip"; BUILD=1; INSTALL_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --python-env) PYENV="$2"; shift 2 ;;
    --setup) SETUP=1; shift; if [[ $# -gt 0 && "$1" != --* ]]; then SOURCE="$1"; shift; fi ;;
    --skip-build) BUILD=0; shift ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done

# --- find the launcher: new Jaunch-based Fiji first, then the classic ImageJ-* launchers
LAUNCHER=""
case "$(uname -s)" in
  # fiji-windows-x64.exe is a GUI-mode program (no console output), so prefer the classic launcher on Windows.
  MINGW*|MSYS*|CYGWIN*) CANDIDATES=("$FIJI/ImageJ-win64.exe" "$FIJI/fiji-windows-x64.exe") ;;
  # Current macOS zips unpack to Fiji/ with a "fiji" script that finds Fiji/Fiji.app/Contents/MacOS/fiji-macos-*.
  Darwin) CANDIDATES=("$FIJI/fiji" "$FIJI/Contents/MacOS/fiji" "$FIJI/Contents/MacOS/ImageJ-macosx") ;;
  *) CANDIDATES=("$FIJI/fiji" "$FIJI/ImageJ-linux64") ;;
esac
for c in "${CANDIDATES[@]}"; do
  if [[ -f "$c" ]]; then LAUNCHER="$c"; break; fi
done
if [[ -z "$LAUNCHER" ]]; then
  LAUNCHER="$(ls "$FIJI"/fiji-* "$FIJI"/Contents/MacOS/fiji-* 2>/dev/null | head -n1 || true)"
fi
[[ -n "$LAUNCHER" ]] || { echo "No Fiji launcher found in $FIJI"; exit 2; }
CONSOLE=""
[[ "$LAUNCHER" == *ImageJ-* ]] && CONSOLE="--console"   # classic launcher needs --console to print
# (a plain string, not an array: macOS ships bash 3.2, where an empty array trips "set -u")
echo "Fiji launcher: $LAUNCHER"

PASS=(); FAIL=()
run_fiji() {  # run_fiji <marker> <script> [params]
  local marker="$1" script="$2" params="${3:-}"
  echo; echo "=== $script ${params}"
  local out
  if [[ -n "$params" ]]; then
    out="$("$LAUNCHER" --headless $CONSOLE --run "$script" "$params" 2>&1)"
  else
    out="$("$LAUNCHER" --headless $CONSOLE --run "$script" 2>&1)"
  fi
  echo "$out" | grep -v -E "^\s*$" | tail -n 40
  if grep -q "$marker" <<<"$out"; then PASS+=("$marker"); else FAIL+=("$marker"); fi
}

if [[ $BUILD -eq 1 ]]; then
  echo "=== build + install"
  bash "$ROOT/build.sh" "$FIJI" --install || { echo "BUILD FAILED"; exit 1; }   # bash: the exec bit may be lost on checkout
fi

cd "$ROOT"
run_fiji SMALL_OBJECTS_OK test/run_small_objects.groovy

if [[ $SETUP -eq 1 ]]; then
  run_fiji SETUP_OK test/run_setup.groovy "source='$SOURCE',download_model=true,install_dir='$INSTALL_DIR'"
fi

EXPECTED="$ROOT/test/data/SubCellSeg_sample_SON_expected_mask.tif"
run_fiji SAMPLE_OK test/run_sample.groovy "python_env='$PYENV',expected='$EXPECTED',outdir='$ROOT/test/out_sample'"

if [[ -n "$INSTALL_DIR" ]]; then
  # The Setup command remembered the test install location in the ImageJ preferences; forget it again.
  "$LAUNCHER" --headless $CONSOLE --run test/clear_install_dir.groovy >/dev/null 2>&1
fi

echo; echo "=== summary"
echo "passed: ${PASS[*]:-none}"
echo "failed: ${FAIL[*]:-none}"
[[ ${#FAIL[@]} -eq 0 ]]
