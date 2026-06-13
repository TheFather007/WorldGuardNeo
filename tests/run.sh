#!/usr/bin/env bash
# Runs the standalone WorldGuardNeo test suites against the compiled classes.
# Usage: ./gradlew build -x test && bash tests/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/build/classes/java/main"
[ -d "$CLASSES" ] || { echo "Build first: ./gradlew build -x test"; exit 1; }
FASTUTIL=$(find ~/.gradle -name 'fastutil-*.jar' 2>/dev/null | head -1)
GSON=$(find ~/.gradle -name 'gson-*.jar' 2>/dev/null | grep -v sources | head -1)
CP="$CLASSES:$FASTUTIL:$GSON"
OUT=$(mktemp -d)
javac -cp "$CP" -d "$OUT" "$ROOT"/tests/*.java
rc=0
for T in FlagLogicTest StorageRoundTripTest FlagScenarioTest FlagResolutionEdgeTest FlagContractTest; do
  printf '%-24s ' "$T:"
  java -cp "$OUT:$CP" "$T" | tail -1 || rc=1
done
# Per-flag battery → writes the detailed report file.
printf '%-24s ' "PerFlagReport:"
java -cp "$OUT:$CP" PerFlagReport "$ROOT/tests/FLAG_REPORT.txt" | tail -1 || rc=1
# Static per-flag enforcement-coverage guard.
printf '%-24s ' "coverage:"
bash "$ROOT/tests/coverage.sh" | tail -1 || rc=1
exit $rc
