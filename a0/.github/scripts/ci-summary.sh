#!/usr/bin/env bash
# Writes the GitHub Actions job summary for a VORTEX X build:
#   - "APK built" (file name + size) or "APK was not built"
#   - JVM unit-test counts from the JUnit XML reports, plus failed test names
#   - an "Errors" box with compiler / resource / Gradle errors trimmed to file:line
# It also emits up to 10 ::error annotations so errors show on the run page.
#
# usage: ci-summary.sh <project_dir> <logs_dir>
# env:   ASSEMBLE, UNIT, ANDROIDTEST, LINT = step outcomes (success | failure | skipped | cancelled)
#        GITHUB_STEP_SUMMARY (set by Actions; falls back to stdout)
set -u

PROJECT_DIR="${1:?project dir}"
LOGS_DIR="${2:?logs dir}"
OUT="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

python3 - "$PROJECT_DIR" "$LOGS_DIR" "$OUT" <<'PY'
import glob, os, re, sys
import xml.etree.ElementTree as ET

project, logs, out = sys.argv[1], sys.argv[2], sys.argv[3]
project = os.path.abspath(project)
outcome = {k: (os.environ.get(k) or "skipped") for k in ("ASSEMBLE", "UNIT", "ANDROIDTEST", "LINT", "SIGNING")}
icon = {"success": "✅", "failure": "❌", "cancelled": "⛔", "skipped": "⏭️"}
lines = []
w = lines.append

def rel(path):
    path = path.replace("file://", "")
    for prefix in (project + "/", os.environ.get("GITHUB_WORKSPACE", "\0") + "/"):
        if path.startswith(prefix):
            path = path[len(prefix):]
    return path

w("## VORTEX X — " + os.environ.get("VALIDATION_CONTEXT", "CI") + " result\n")
w("| Step | Result |")
w("|---|---|")
for key, label in (
    ("ASSEMBLE", "Build debug APK (`assembleDebug`)"),
    ("UNIT", "JVM unit tests (`testDebugUnitTest`)"),
    ("ANDROIDTEST", "Compile instrumentation tests (`assembleDebugAndroidTest`)"),
    ("LINT", "Lint (report only, never fails the run)"),
    ("SIGNING", "Phase 1 package and signing compatibility"),
):
    o = outcome[key]
    w(f"| {label} | {icon.get(o, '❔')} {o} |")
w("")

# ---- APK ---------------------------------------------------------------
apks = sorted(glob.glob(os.path.join(project, "app/build/outputs/apk/debug/*.apk")))
w("### APK")
w("Stage A0 is not phone-verified by CI. A1 stays blocked until the owner completes the device checklist.")
w("Only a build with all required gates passing is uploaded as **vortex-x-a0-phone-candidate-apk**.")
w("Commit: `" + os.environ.get("GITHUB_SHA", "local run") + "`")
if outcome["ASSEMBLE"] == "success" and apks:
    for apk in apks:
        size = os.path.getsize(apk) / (1024 * 1024)
        w(f"✅ **APK built** — `{os.path.basename(apk)}` ({size:.1f} MB). Signing, test and phone gates are reported separately.")
else:
    w("❌ **APK was not built.** See the Errors box below.")
w("")

# ---- Unit tests --------------------------------------------------------
w("### Unit tests")
xmls = glob.glob(os.path.join(project, "app/build/test-results/testDebugUnitTest/*.xml"))
failed_tests = []
if xmls:
    t = f = e = s = 0
    for x in xmls:
        try:
            root = ET.parse(x).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for su in suites:
            t += int(su.get("tests", 0)); f += int(su.get("failures", 0))
            e += int(su.get("errors", 0)); s += int(su.get("skipped", 0))
            for case in su.findall("testcase"):
                bad = case.find("failure")
                if bad is None:
                    bad = case.find("error")
                if bad is not None:
                    msg = (bad.get("message") or bad.text or "").strip().splitlines()
                    failed_tests.append(f"{case.get('classname', '?').split('.')[-1]}.{case.get('name', '?')} — {msg[0][:180] if msg else 'no message'}")
    passed = t - f - e - s
    w(f"Tests: **{t}** · Passed: **{passed}** · Failed: **{f + e}** · Skipped: **{s}**")
    for ft in failed_tests[:20]:
        w(f"- ❌ {ft}")
else:
    w("Unit tests did not run (no JUnit reports).")
w("")

# ---- Errors ------------------------------------------------------------
errors, annotations, seen = [], [], set()

def add(text, path=None, line=None, col=None):
    text = text.strip()
    if not text or text in seen:
        return
    seen.add(text)
    errors.append(text)
    if path and line and len(annotations) < 10:
        annotations.append((path, line, col, text))

kotlin = re.compile(r"^e: (?:\[ksp\] )?(?:file://)?(/[^:\s]+\.(?:kt|kts|java)):(\d+)(?::(\d+))?:? (.*)$")
javac = re.compile(r"^(/[^:\s]+\.(?:java|kt)):(\d+): error: (.*)$")
aapt = re.compile(r"^(?:ERROR:\s*)?(/[^:\s]+\.xml):(\d+):\s*(?:AAPT: )?error: (.*)$")
for log in sorted(glob.glob(os.path.join(logs, "*.log"))):
    try:
        content = open(log, encoding="utf-8", errors="replace").read().splitlines()
    except OSError:
        continue
    what_went_wrong, grab = [], 0
    for raw in content:
        ln = raw.rstrip()
        m = kotlin.match(ln)
        if m:
            p = rel(m.group(1)); add(f"{p}:{m.group(2)}{':' + m.group(3) if m.group(3) else ''} {m.group(4)}", p, m.group(2), m.group(3)); continue
        m = javac.match(ln) or aapt.match(ln)
        if m:
            p = rel(m.group(1)); add(f"{p}:{m.group(2)} {m.group(3)}", p, m.group(2)); continue
        if ln.startswith("e: ") and "warning" not in ln.lower():
            add(rel(ln[3:])); continue
        if ln.startswith("* What went wrong:"):
            grab = 8; continue
        if grab:
            if ln.startswith("* Try:") or ln.startswith("* Exception is:"):
                grab = 0; continue
            if ln.strip():
                what_went_wrong.append(rel(ln.strip()))
            grab -= 1
    if what_went_wrong:
        add(f"[{os.path.basename(log)}] Gradle: " + " | ".join(what_went_wrong[:6])[:600])

w("### Allocation measurements and signing")
for log_name in ("jvm-gate.log", "5-signing-gate.log"):
    path = os.path.join(logs, log_name)
    if os.path.exists(path):
        w("```text")
        w(open(path, encoding="utf-8").read().strip())
        w("```")
    else:
        w(f"{log_name}: NOT MEASURED / not run.")
w("### Compiler / Gradle errors")
if errors:
    w("```text")
    for e in errors[:40]:
        w(e[:400])
    if len(errors) > 40:
        w(f"... {len(errors) - 40} more — see the full logs artifact")
    w("```")
elif failed_tests:
    w("No compiler errors. Failing tests are listed above.")
else:
    w("None.")
w("")
w(os.environ.get("REPORT_LOCATION", "Full Gradle logs and test/lint reports: artifact **vortex-x-logs-and-reports**."))

with open(out, "a", encoding="utf-8") as fh:
    fh.write("\n".join(lines) + "\n")

for p, line, col, text in annotations:
    msg = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    loc = f"file={p},line={line}" + (f",col={col}" if col else "")
    print(f"::error {loc}::{msg}")
PY
