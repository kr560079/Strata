#!/usr/bin/env bash
# Run scalar benchmarks with JDK 11 — establishes the baseline.
#
# Prerequisites: run `mvn install -DskipTests` in the project root first.
#
# Output: benchmarks/results-jdk11.json  (machine-readable)
#         stdout                          (human-readable)
#
# Usage:
#   ./run-jdk11.sh               — run all scalar benchmarks
#   ./run-jdk11.sh "DoubleArray" — run only DoubleArrayBenchmark
#   ./run-jdk11.sh -h            — show JMH help

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JDK11="$HOME/.sdkman/candidates/java/11.0.31-tem"

if [ ! -d "$JDK11" ]; then
  echo "ERROR: JDK 11 not found at $JDK11"
  echo "Install it with: sdk install java 11.0.31-tem"
  exit 1
fi

cd "$SCRIPT_DIR"

echo "=========================================="
echo " Building benchmarks (release=11) ..."
echo "=========================================="
mvn clean package -DskipTests -q

JDK11_VERSION=$("$JDK11/bin/java" -version 2>&1 | head -1)
echo ""
echo "=========================================="
echo " Running with: $JDK11_VERSION"
echo "=========================================="
echo ""

"$JDK11/bin/java" -jar target/benchmarks.jar \
  -rf json -rff results-jdk11.json \
  "$@"

echo ""
echo "Results written to: $SCRIPT_DIR/results-jdk11.json"
