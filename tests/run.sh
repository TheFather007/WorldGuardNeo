#!/usr/bin/env bash
# Runs the standalone WorldGuardNeo test suites against the compiled classes.
# Usage: ./gradlew build -x test && bash tests/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/build/classes/java/main"
[ -d "$CLASSES" ] || { echo "Build first: ./gradlew build -x test"; exit 1; }
FASTUTIL=$(find ~/.gradle -name 'fastutil-*.jar' 2>/dev/null | head -1)
GSON=$(find ~/.gradle -name 'gson-*.jar' 2>/dev/null | grep -v sources | head -1)
# Extra deps used by the storage/config suites (real layers, not just the in-memory codec):
LOGGING=$(find ~/.gradle /root/.gradle -path '*com.mojang/logging*' -name '*.jar' 2>/dev/null | grep -v sources | head -1)
SLF4J=$(find ~/.gradle /root/.gradle -path '*org.slf4j/slf4j-api*' -name '*.jar' 2>/dev/null | grep -v sources | head -1)
TOML=$(find ~/.gradle /root/.gradle -path '*night-config/toml*' -name '*.jar' 2>/dev/null | grep -v sources | head -1)
NCCORE=$(find ~/.gradle /root/.gradle -path '*night-config/core*' -name '*.jar' 2>/dev/null | grep -v sources | head -1)
CP="$CLASSES:$FASTUTIL:$GSON:$LOGGING:$SLF4J:$TOML:$NCCORE"
OUT=$(mktemp -d)
javac -cp "$CP" -d "$OUT" "$ROOT"/tests/*.java
rc=0
for T in FlagLogicTest StorageRoundTripTest FlagScenarioTest FlagResolutionEdgeTest FlagContractTest \
         EngineExtrasTest GeometryTest SpatialIndexTest ResolutionMatrixTest ParsingTest \
         RegionMechanicsTest PerRegionCodecTest LocalizationTest FuzzCodecTest \
         FlagSerializationTest FlagRegistryTest GeometryCrossCheckTest ParentChainTest \
         GetApplicableOrderingTest SpatialIndexFuzzTest ResolutionFuzzTest \
         JsonStorageTest ConfigTest; do
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
