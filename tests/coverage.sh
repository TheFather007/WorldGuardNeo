#!/usr/bin/env bash
# Static coverage guard: every registered flag must be referenced in an enforcement/command
# file (not just declared). Expected declared-but-not-enforced flags are whitelisted; any NEW
# unreferenced flag fails the check, catching a flag that silently lost its handler.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" << 'PY'
import re, glob, sys, os
root = sys.argv[1]
src = open(os.path.join(root,'src/main/java/dev/thefather007/worldguardneo/flags/Flags.java')).read()
consts = {m.group(1): m.group(2) for m in re.finditer(r'(\w+)\s*=\s*new \w+Flag\("([a-z0-9-]+)"', src)}
files = [f for f in glob.glob(os.path.join(root,'src/main/java/**/*.java'), recursive=True)
         if '/flags/' not in f and '/api/' not in f]
blob = "\n".join(open(f).read() for f in files)
unref = sorted(consts[c] for c in consts if not re.search(r'\bFlags\.'+c+r'\b', blob))
# As of v1.3 EVERY registered flag is enforced (receive-chat via mixin, on-entry/on-exit in the
# tick handler; the old unenforceable allowed-enchants was removed), so the whitelist is empty.
WHITELIST = set()
unexpected = [n for n in unref if n not in WHITELIST]
print(f"flags: {len(consts)} | enforced: {len(consts)-len(unref)} | declared-only: {unref}")
if unexpected:
    print("FAIL: these flags are no longer referenced in any handler/command:", unexpected)
    sys.exit(1)
print("OK: every registered flag is referenced in an enforcement/command file.")
PY
