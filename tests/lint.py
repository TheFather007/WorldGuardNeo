#!/usr/bin/env python3
"""
Static lint gate for WorldGuardNeo — codifies the manual audit scans so regressions in the
non-executable layers (imports, localization, mixin registration) fail CI instead of slipping
past the ~520k-assertion JVM suite and the 166 GameTests (which only cover runtime logic).

Checks:
  1. No unused imports in src/main/java.
  2. Full lang-key parity across every locale vs en_us.
  3. Every "msg.*"/"item.*" key referenced in Java exists in en_us (no raw key shown to players).
  4. Every registered flag has a flag.<name>.desc entry in en_us (so /rg flags has help text).
  5. Every mixin class under mixin/ is registered in worldguardneo.mixins.json (else it silently
     never loads and its protection is gone).
  6. No example.com placeholder URLs ship in resources.

Usage: python3 tests/lint.py   (run from anywhere; resolves the repo root from this file).
Exits non-zero on the first category that fails; prints a per-check summary.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "worldguardneo", "lang")

failures = []


def java_files():
    for dirpath, _, names in os.walk(JAVA):
        for n in names:
            if n.endswith(".java"):
                yield os.path.join(dirpath, n)


def check_unused_imports():
    bad = []
    for f in java_files():
        txt = open(f, encoding="utf-8").read()
        imports, body = [], []
        for ln in txt.splitlines():
            m = re.match(r"\s*import\s+(?:static\s+)?([\w.]+)\s*;", ln)
            (imports.append((m.group(1), ln.strip())) if m else body.append(ln))
        bt = "\n".join(body)
        for fq, raw in imports:
            if fq.endswith(".*"):
                continue
            simple = fq.split(".")[-1]
            if not re.search(r"(?<![\w.])" + re.escape(simple) + r"(?![\w])", bt):
                bad.append(f"{os.path.relpath(f, ROOT)}: {raw}")
    return bad


def load_lang(name):
    return json.load(open(os.path.join(LANG, name), encoding="utf-8"))


def check_lang_parity():
    en = set(load_lang("en_us.json"))
    bad = []
    for fn in sorted(os.listdir(LANG)):
        if not fn.endswith(".json") or fn == "en_us.json":
            continue
        k = set(load_lang(fn))
        missing, extra = en - k, k - en
        if missing:
            bad.append(f"{fn}: missing {sorted(missing)}")
        if extra:
            bad.append(f"{fn}: extra {sorted(extra)}")
    return bad


def check_referenced_keys():
    en = set(load_lang("en_us.json"))
    lit = re.compile(r'"((?:msg|item)\.[a-z0-9._-]+)"')
    referenced = set()
    for f in java_files():
        for m in lit.finditer(open(f, encoding="utf-8").read()):
            referenced.add(m.group(1))
    return sorted(k for k in referenced if k not in en)


def check_flag_descs():
    src = open(os.path.join(JAVA, "dev/thefather007/worldguardneo/flags/Flags.java"), encoding="utf-8").read()
    names = set(re.findall(
        r'new (?:StateFlag|StringFlag|IntegerFlag|DoubleFlag|BooleanFlag|SetFlag)\("([a-z0-9-]+)"', src))
    en = load_lang("en_us.json")
    desc = {k[len("flag."):-len(".desc")] for k in en if k.startswith("flag.") and k.endswith(".desc")}
    return sorted(names - desc)


def check_mixin_registration():
    mixin_dir = os.path.join(JAVA, "dev/thefather007/worldguardneo/mixin")
    classes = {n[:-5] for n in os.listdir(mixin_dir) if n.endswith(".java")}
    cfg = json.load(open(os.path.join(ROOT, "src/main/resources/worldguardneo.mixins.json"), encoding="utf-8"))
    listed = set(cfg.get("mixins", [])) | set(cfg.get("server", [])) | set(cfg.get("client", []))
    return sorted(classes - listed)


def check_no_placeholder_urls():
    bad = []
    res = os.path.join(ROOT, "src", "main", "resources")
    for dirpath, _, names in os.walk(res):
        for n in names:
            p = os.path.join(dirpath, n)
            try:
                if "example.com" in open(p, encoding="utf-8").read():
                    bad.append(os.path.relpath(p, ROOT))
            except (UnicodeDecodeError, OSError):
                pass
    return bad


CHECKS = [
    ("unused imports", check_unused_imports),
    ("lang parity", check_lang_parity),
    ("referenced lang keys exist", check_referenced_keys),
    ("flag description coverage", check_flag_descs),
    ("mixin registration", check_mixin_registration),
    ("no placeholder URLs", check_no_placeholder_urls),
]


def main():
    for label, fn in CHECKS:
        problems = fn()
        if problems:
            failures.append(label)
            print(f"FAIL  {label} ({len(problems)}):")
            for p in problems:
                print(f"        {p}")
        else:
            print(f"ok    {label}")
    if failures:
        print(f"\n==== lint: {len(failures)} check(s) FAILED: {', '.join(failures)} ====")
        sys.exit(1)
    print("\n==== lint: all checks passed ====")


if __name__ == "__main__":
    main()
