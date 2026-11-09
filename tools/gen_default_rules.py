#!/usr/bin/env python3
"""Embed rules/default-rules.json into a Java class as base64.

Usage: gen_default_rules.py <rules.json> <out.java>
"""
import base64
import json
import os
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: gen_default_rules.py <rules.json> <out.java>", file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    raw = open(src, "rb").read()
    json.loads(raw.decode("utf-8"))  # fail early on invalid JSON
    b64 = base64.b64encode(raw).decode("ascii")
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write("package com.eta.atb;\n\n")
        f.write("final class DefaultRules {\n")
        f.write("    private DefaultRules() {}\n\n")
        f.write("    static final String B64 =\n")
        # keep lines short for readability; Java concatenates at compile time
        for i in range(0, len(b64), 96):
            f.write('        "%s" +\n' % b64[i:i + 96])
        f.write('        "";\n')
        f.write("}\n")
    print(f"wrote {dst} ({len(b64)} b64 chars)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
