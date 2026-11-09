#!/usr/bin/env python3
"""Check ATB rules against an APK's dex method tables (static verification).

Usage:
  verify_rules.py <apk> [rules.json]

Requires apkanalyzer (Android SDK cmdline-tools). Set APKANALYZER or
ANDROID_HOME if it is not in the default location.

This checks class/method names and parameter type lists. It cannot check
static/void modifiers or runtime behavior; those are verified by logcat on a
device.
"""
import json
import os
import re
import shutil
import subprocess
import sys

METHOD_RE = re.compile(r"^M d\s+\S+\s+\S+\s+\S+\s+(\S+)\s+(?:(\S+)\s+)?(\S+?)\((.*?)\)$")
CLASS_RE = re.compile(r"^C d\s+\S+\s+\S+\s+\S+\s+(\S+)$")


def find_apkanalyzer():
    env = os.environ.get("APKANALYZER")
    if env:
        return env
    home = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    candidates = [
        os.path.join(home, "cmdline-tools", "latest", "bin", "apkanalyzer"),
        shutil.which("apkanalyzer"),
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None


def load_methods(apk):
    apkanalyzer = find_apkanalyzer()
    if not apkanalyzer:
        print("apkanalyzer not found; set APKANALYZER=/path/to/apkanalyzer", file=sys.stderr)
        raise SystemExit(2)
    out = subprocess.check_output([apkanalyzer, "dex", "packages", apk], text=True)
    classes = {}
    current = None
    methods = []
    for line in out.splitlines():
        m = CLASS_RE.match(line)
        if m:
            current = m.group(1)
            methods = []
            classes[current] = methods
            continue
        m = METHOD_RE.match(line)
        if m and current:
            cls, ret, name, params = m.group(1), m.group(2) or "", m.group(3), m.group(4)
            if cls != current:
                current = cls
                methods = classes.setdefault(current, [])
            ps = [p for p in (x.strip() for x in params.split(",")) if p]
            methods.append((name, ps, ret))
    return classes


def validate_type(name):
    if not name or "{{" in name or "}}" in name:
        return False
    return bool(re.match(r"^[A-Za-z_$][A-Za-z0-9_$.]*(?:\[\])?$", name))


def method_matches(methods, spec):
    name = spec["name"]
    params = spec.get("params")
    hits = [m for m in methods if m[0] == name]
    if params is None:
        return hits
    return [m for m in hits if m[1] == params]


def apk_package(apk):
    apkanalyzer = find_apkanalyzer()
    if not apkanalyzer:
        return ""
    try:
        return subprocess.check_output(
            [apkanalyzer, "manifest", "application-id", apk], text=True).strip()
    except Exception:
        return ""


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    apk = sys.argv[1]
    rules_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "..", "rules", "default-rules.json")
    rules = json.load(open(rules_path, encoding="utf-8"))
    classes = load_methods(apk)

    apps = rules.get("apps") or {}
    apk_pkg = apk_package(apk)
    if apk_pkg and apk_pkg in apps:
        print(f"[filter] checking {apk_pkg} only")
        apps = {apk_pkg: apps[apk_pkg]}
    elif apk_pkg:
        print(f"[filter] no rule for {apk_pkg}; checking all apps")

    failures = []
    warnings = []
    for pkg, app in apps.items():
        print(f"\n[{pkg}] enabled={app.get('enabled', True)}")
        for r in app.get("silence", []):
            cls = r["class"]
            optional = bool(r.get("optional"))
            if cls not in classes:
                msg = f"silence class missing: {cls}"
                (warnings if optional else failures).append(msg)
                print(("  WARN " if optional else "  FAIL ") + msg)
                continue
            if r.get("staticVoid"):
                print(f"  PASS silence {cls} (class present; static/void not checkable statically)")
                continue
            for spec in r.get("methods", []):
                if not validate_type_ok(spec, failures, pkg):
                    continue
                hits = method_matches(classes[cls], spec)
                if hits:
                    nonvoid = [h for h in hits if h[2] != "void" and spec.get("returnValue") is None]
                    if nonvoid:
                        msg = f"silence target is not void and has no returnValue: {cls}#{spec['name']}"
                        (warnings if optional else failures).append(msg)
                        print(("  WARN " if optional else "  FAIL ") + msg)
                    else:
                        extra = "" if spec.get("returnValue") is None else f" -> {spec['returnValue']}"
                        print(f"  PASS silence {cls}#{spec['name']}{extra}")
                else:
                    msg = f"silence method missing: {cls}#{spec['name']} {spec.get('params', '<any>')}"
                    (warnings if optional else failures).append(msg)
                    print(("  WARN " if optional else "  FAIL ") + msg)

        for r in app.get("sinks", []):
            cls = r["class"]
            optional = bool(r.get("optional"))
            if cls not in classes:
                msg = f"sink class missing: {cls}"
                (warnings if optional else failures).append(msg)
                print(("  WARN " if optional else "  FAIL ") + msg)
                continue
            for spec in r.get("methods", []):
                if not validate_type_ok(spec, failures, pkg):
                    continue
                hits = method_matches(classes[cls], spec)
                if hits:
                    for (_, ps, ret) in hits:
                        if ret != "void":
                            msg = f"sink target is not void: {cls}#{spec['name']}"
                            (warnings if optional else failures).append(msg)
                            print(("  WARN " if optional else "  FAIL ") + msg)
                            continue
                        idx_ok = len(ps) > r.get("eventIndex", 1) and ps[r.get("eventIndex", 1)] == "java.lang.String"
                        app_ok = r.get("appIdIndex", 0) < 0 or (
                            len(ps) > r.get("appIdIndex", 0) and ps[r.get("appIdIndex", 0)] == "java.lang.String")
                        if idx_ok and app_ok:
                            print(f"  PASS sink {cls}#{spec['name']}({','.join(ps)})")
                        else:
                            msg = f"sink signature has non-String appId/event: {cls}#{spec['name']}({','.join(ps)})"
                            (warnings if optional else failures).append(msg)
                            print(("  WARN " if optional else "  FAIL ") + msg)
                else:
                    msg = f"sink method missing: {cls}#{spec['name']} {spec.get('params', '<any>')}"
                    (warnings if optional else failures).append(msg)
                    print(("  WARN " if optional else "  FAIL ") + msg)

    print("\n== summary ==")
    print(f"classes={len(classes)} failures={len(failures)} warnings={len(warnings)}")
    for w in warnings:
        print("  WARN " + w)
    for f in failures:
        print("  FAIL " + f)
    return 1 if failures else 0


def validate_type_ok(spec, failures, pkg):
    params = spec.get("params")
    if params is None:
        return True
    for p in params:
        if not validate_type(p):
            msg = f"invalid parameter type {p!r} in {pkg} rule {spec.get('name')}"
            failures.append(msg)
            print("  FAIL " + msg)
            return False
    return True


if __name__ == "__main__":
    raise SystemExit(main())
