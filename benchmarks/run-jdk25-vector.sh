#!/usr/bin/env bash
# Run Vector API benchmarks with JDK 25.
#
# For each operation this runs both the scalar (current Strata) and Vector API
# implementations side-by-side, showing the speedup achievable with Step 1
# of the JDK25_ENHANCEMENT_PLAN.md.
#
# Compiles with -Pjdk25 (release=25, --add-modules jdk.incubator.vector).
# Only VectorApiDoubleArrayBenchmark is new here; the scalar benchmarks are
# also included for a direct in-run comparison.
#
# Output: benchmarks/results-jdk25-vector.json

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JDK25="$HOME/.sdkman/candidates/java/25.0.3-tem"

if [ ! -d "$JDK25" ]; then
  echo "ERROR: JDK 25 not found at $JDK25"
  exit 1
fi

cd "$SCRIPT_DIR"

echo "=========================================="
echo " Building benchmarks with -Pjdk25 ..."
echo " (release=25, incubator Vector API)"
echo "=========================================="
JAVA_HOME="$JDK25" mvn clean package -DskipTests -q -Pjdk25

JDK25_VERSION=$("$JDK25/bin/java" -version 2>&1 | head -1)
echo ""
echo "=========================================="
echo " Running with: $JDK25_VERSION (Vector API)"
echo "=========================================="
echo ""
echo "NOTE: On Apple Silicon SPECIES_PREFERRED = 128-bit (2 doubles)."
echo "      On Intel AVX2 it is 256-bit (4 doubles)."
echo "      On Intel AVX-512 it is 512-bit (8 doubles)."
echo ""

"$JDK25/bin/java" \
  --add-modules jdk.incubator.vector \
  -jar target/benchmarks.jar \
  "VectorApi" \
  -rf json -rff results-jdk25-vector.json \
  "$@"

echo ""
echo "Results written to: $SCRIPT_DIR/results-jdk25-vector.json"
