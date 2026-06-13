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
for T in FlagLogicTest StorageRoundTripTest FlagScenarioTest; do
  printf '%-22s ' "$T:"
  java -cp "$OUT:$CP" "$T" | tail -1 || rc=1
done
exit $rc
