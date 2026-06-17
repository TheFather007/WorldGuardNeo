# WorldGuardNeo — region-engine tests

Standalone JVM tests for the region engine. They run against the **compiled main classes** with no
Minecraft runtime: the engine (region geometry, flag resolution, JSON/per-region storage codec) has
no hard Minecraft dependencies on its happy paths, so a plain `java` invocation exercises it.

> These live on a **dedicated branch** (`claude/region-tests-sysdmj`) and are intentionally **not**
> shipped in the mod jar — they are a development/CI aid only.

## Run

```bash
./gradlew build -x test     # compile the mod's main classes
bash tests/run.sh           # compile + run every suite; non-zero exit on any failure
```

`run.sh` finds `gson` and `fastutil` in your Gradle cache automatically.

## Suites

| Suite | Covers |
| --- | --- |
| `FlagLogicTest` | Per-flag resolution: default/allow/deny × owner/member/stranger/null, groups, priority, parents, geometry, global fallback. |
| `FlagScenarioTest` | Deeper matrices: build-access per flag, every region group, multi-tier priority, parent chains, concave polygons, overlapping regions, value parsing. |
| `FlagResolutionEdgeTest` | Cross-priority group shadowing, inherited build-access, group-scoped value flags, oversized-region indexing, ownership queries. |
| `FlagContractTest` | Per-flag contract: type, default, permission node, value hint, registry round-trip, total flag count (85). |
| `StorageRoundTripTest` | Whole-world JSON codec: every flag type, groups, parents, geometry survive save → JSON → load. |
| `EngineExtrasTest` | **v1.3 additions:** per-region storage codec (incremental DB path), `RegionManager.crossesBoundary`, `PolygonalRegion.contains` floor-consistency (the +X/+Z edge fix), `SpatialIndex` extreme-coordinate handling. |
| `GeometryTest` | Cuboid/Polygon/Global `contains` on all faces, negative coords, concave (L-shape) & triangle polygons, degenerate-polygon rejection, volume/overflow, `Vec3` math. |
| `SpatialIndexTest` | Bucket add/remove symmetry (no stale entries), point & area queries, oversized routing (huge span + extreme coord), chunk-border spanning, negative chunks, rebuild/clear, key uniqueness. |
| `ResolutionMatrixTest` | Exhaustive group×actor matrix, priority tiers, DENY-wins, group shadowing, parent inheritance (incl. inherited-flag group), build-access "private by default", `resolveValue`, `crossesBoundary`, overlap/ownership queries. |
| `ParsingTest` | Every flag type's `parse`/`fromJson` (valid/invalid/empty/whitespace/case/synonyms/bounds), `RegionGroup.parse`, flag-name validation, `parseAndApply`. |
| `RegionMechanicsTest` | `ProtectedRegion`: id validation, membership, lazy collections, parent-cycle detection, `flagEpoch` bumps, `copyFlagsFrom`, flag-group semantics, immutable raw views. |
| `PerRegionCodecTest` | Per-region codec round-trip for every flag type + groups + owners/members + priority + polygon + valid parent links + global row. |
| `LocalizationTest` | `format()` placeholder edge cases (unknown/`%%`/trailing-`%`/numeric/missing-key) via a temp lang file. |
| `FuzzCodecTest` | **Property-based:** 200 randomized regions (cuboid+polygon, all flag types, random groups/owners/priorities/acyclic parents) round-tripped through BOTH the whole-world and per-region codecs, asserting every property survives identically (~8,800 assertions). |
| `FlagSerializationTest` | `toJson`/`fromJson` for every flag type: round-trip, null handling, junk-JSON tolerance (returns null instead of throwing). |
| `FlagRegistryTest` | Registry mechanics & invariants: lookup, duplicate-registration guard, name/permission/descriptionKey format for every flag, value-hint, equals/hashCode by name, the exactly-3-default-deny invariant. |
| `GeometryCrossCheckTest` | **Differential PIP:** validates `PolygonalRegion.contains` (crossing-number) against an independent winding-number implementation over dense grids of many random rectangles/L-shapes (~7,900 assertions; even vertices sampled at odd coords → no boundary ambiguity). |
| `ParentChainTest` | Flag inheritance along parent chains: near-ancestor inheritance, the 32-hop resolution bound, group filters on an ancestor, cycle prevention at depth. |
| `GetApplicableOrderingTest` | `getApplicable` ordering (priority desc, then id asc), list-vs-point resolution equivalence, `hasAnyAt` consistency, ownership-at-point queries. |
| `PerFlagReport` | Per-flag battery; writes a detailed human-readable report to `tests/FLAG_REPORT.txt`. |
| `coverage.sh` | Static guard: every registered flag is referenced in an enforcement/command file (catches a flag that silently lost its handler). |

Total: **~20,200 assertions** across 21 suites + the static coverage guard.
