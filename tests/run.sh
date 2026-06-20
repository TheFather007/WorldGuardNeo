#!/usr/bin/env bash
# Runs the standalone WorldGuardNeo test suites against the compiled classes.
# Usage: ./gradlew build -x test && bash tests/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/build/classes/java/main"
[ -d "$CLASSES" ] || { echo "Build first: ./gradlew build -x test"; exit 1; }
# Locate runtime deps from the Gradle cache. Use GRADLE_USER_HOME when set (CI sets it), else
# ~/.gradle. Each pipeline ends in `|| true` so an empty result (no match, or SIGPIPE from `head`
# closing the pipe early) can't trip `set -e -o pipefail` — a genuinely missing dep instead surfaces
# as a clear javac error below, never a silent abort. (The old hardcoded /root/.gradle path made
# `find` exit non-zero on non-root CI runners, aborting the whole script with no output.)
GR="${GRADLE_USER_HOME:-$HOME/.gradle}"
findjar() { find "$GR" "$@" 2>/dev/null | grep -v sources | head -1 || true; }
FASTUTIL=$(findjar -name 'fastutil-*.jar')
GSON=$(findjar -name 'gson-*.jar')
# Extra deps used by the storage/config suites (real layers, not just the in-memory codec):
LOGGING=$(findjar -path '*com.mojang/logging*' -name '*.jar')
SLF4J=$(findjar -path '*org.slf4j/slf4j-api*' -name '*.jar')
TOML=$(findjar -path '*night-config/toml*' -name '*.jar')
NCCORE=$(findjar -path '*night-config/core*' -name '*.jar')
CP="$CLASSES:$FASTUTIL:$GSON:$LOGGING:$SLF4J:$TOML:$NCCORE"
echo "deps: fastutil=$(basename "${FASTUTIL:-MISSING}") gson=$(basename "${GSON:-MISSING}") logging=$(basename "${LOGGING:-MISSING}") slf4j=$(basename "${SLF4J:-MISSING}") toml=$(basename "${TOML:-MISSING}") nc-core=$(basename "${NCCORE:-MISSING}")"
OUT=$(mktemp -d)
javac -cp "$CP" -d "$OUT" "$ROOT"/tests/*.java
rc=0
for T in FlagLogicTest StorageRoundTripTest FlagScenarioTest FlagResolutionEdgeTest FlagContractTest \
         EngineExtrasTest GeometryTest SpatialIndexTest ResolutionMatrixTest ParsingTest \
         RegionMechanicsTest PerRegionCodecTest LocalizationTest FuzzCodecTest \
         FlagSerializationTest FlagRegistryTest GeometryCrossCheckTest ParentChainTest \
         GetApplicableOrderingTest SpatialIndexFuzzTest ResolutionFuzzTest \
         JsonStorageTest ConfigTest NewFlagsTest; do
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
