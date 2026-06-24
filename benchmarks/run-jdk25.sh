#!/usr/bin/env bash
# Run scalar benchmarks with JDK 25 — shows JIT improvement over JDK 11
# without any code changes.
#
# Compare results-jdk11.json vs results-jdk25-scalar.json to quantify
# the JVM JIT improvement alone (same bytecode, different runtime).
#
# Prerequisites: run `mvn install -DskipTests` in the project root first.
#
# Output: benchmarks/results-jdk25-scalar.json

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JDK25="$HOME/.sdkman/candidates/java/25.0.3-tem"

if [ ! -d "$JDK25" ]; then
  echo "ERROR: JDK 25 not found at $JDK25"
  echo "Install it with: sdk install java 25.0.3-tem"
  exit 1
fi

cd "$SCRIPT_DIR"

echo "=========================================="
echo " Building benchmarks (release=11) ..."
echo "=========================================="
mvn clean package -DskipTests -q

JDK25_VERSION=$("$JDK25/bin/java" -version 2>&1 | head -1)
echo ""
echo "=========================================="
echo " Running with: $JDK25_VERSION (scalar code)"
echo "=========================================="
echo ""

"$JDK25/bin/java" -jar target/benchmarks.jar \
  -rf json -rff results-jdk25-scalar.json \
  "$@"

echo ""
echo "Results written to: $SCRIPT_DIR/results-jdk25-scalar.json"
