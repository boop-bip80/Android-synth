#!/usr/bin/env python3
"""Fail-closed API35 runner sharding: AndroidX discovery vs exact Gradle XML union.

AndroidJUnitRunner -e log true lists actual APK methods without executing them.
Two fresh emulators discover the SAME full (non-STAGE/non-COMPACT350/non-GALLERY) manifest, each
executes one disjoint shard, and the required aggregate job verifies the union.
The two opt-in process-death methods may be assumed ONLY in these shards; a
separate mandatory fresh emulator executes them as real tests.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

STAGE = "com.orbital.synth.ui.ImmersiveLiveFrameCaptureTest"
COMPACT350 = "com.orbital.synth.ui.Compact350SoundInstrumentedTest"
# Non-blocking real-animation screenshot generator; runs only in the VISUALS emulator.
GALLERY = "com.orbital.synth.ui.AuroraGalleryCaptureTest"
STEP = "com.orbital.synth.ui.StepProcessRestartInstrumentedTest"
STEP_METHODS = {
    STEP + "#prepareStepPatchAndProveFocusPlayStateBeforeProcessDeath",
    STEP + "#verifyAfterForcedDeathColdRestartAndDeleteOnlyOurPatch",
}
# First tested baseline had 119 general @Test methods; exact AndroidX
# discovery, rather than a stale hardcoded count, is the coverage oracle.
# This floor is a guard, while exact completeness comes from AndroidX discovery.
MIN_GENERAL_METHODS = 119
MANIFEST = "general-shard-manifest.tsv"
RESULTS = "general-shard-results.tsv"


def die(message: str) -> None:
    raise SystemExit("Android sharded CI coverage failure: " + message)


def check_id(identifier: str) -> None:
    if "#" not in identifier:
        die("malformed test id " + repr(identifier))
    klass, name = identifier.split("#", 1)
    if not klass.startswith("com.orbital.synth.") or not name:
        die("unexpected test id " + repr(identifier))
    if klass in (STAGE, COMPACT350, GALLERY):
        die("exclusive STAGE, COMPACT350 or non-blocking GALLERY method leaked into general runner")


def save_lines(path: Path, ids: set[str], statuses: dict[str, str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    sha = os.getenv("GITHUB_SHA", "unknown")
    rows = ["#source_commit=" + sha]
    if statuses is None:
        rows += sorted(ids)
    else:
        rows += [key + "\t" + statuses[key] for key in sorted(ids)]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def load_lines(path: Path, with_status: bool = False) -> tuple[str, dict[str, str]]:
    if not path.is_file() or path.stat().st_size == 0:
        die("missing nonempty shard evidence " + str(path))
    source: str | None = None
    entries: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#source_commit="):
            source = line.partition("=")[2]
            continue
        if line.startswith("#"):
            continue
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) != (2 if with_status else 1):
            die("malformed shard evidence line " + repr(line))
        name = parts[0]
        check_id(name)
        if name in entries:
            die("duplicate test method within one evidence file: " + name)
        entries[name] = parts[1] if with_status else "manifest"
    if not source or source == "unknown":
        die("missing exact source commit provenance in " + str(path))
    if not entries:
        die("no actual test methods found in " + str(path))
    return source, entries


def runner_manifest(runner_output: Path, destination: Path) -> None:
    if not runner_output.is_file():
        die("AndroidJUnitRunner log-only output missing")
    output = runner_output.read_text(encoding="utf-8", errors="replace")
    if not re.search(r"INSTRUMENTATION_CODE:\s*-1\b", output):
        die("AndroidJUnitRunner log-only enumeration did not finish successfully")
    current_class = ""
    current_test = ""
    found: set[str] = set()
    for line in output.splitlines():
        if "INSTRUMENTATION_STATUS: class=" in line:
            current_class = line.split("INSTRUMENTATION_STATUS: class=", 1)[1].strip()
        elif "INSTRUMENTATION_STATUS: test=" in line:
            current_test = line.split("INSTRUMENTATION_STATUS: test=", 1)[1].strip()
        elif "INSTRUMENTATION_STATUS_CODE:" in line:
            if current_class and current_test:
                identifier = current_class + "#" + current_test
                check_id(identifier)
                found.add(identifier)
    if len(found) < MIN_GENERAL_METHODS:
        die("AndroidX log-only discovered too few non-STAGE methods: " + str(len(found)))
    if not STEP_METHODS.issubset(found):
        die("AndroidX manifest lost the two opt-in real-process-restart methods")
    save_lines(destination, found)
    print("PASS AndroidX real APK method discovery", len(found), "source", os.getenv("GITHUB_SHA"))


def executed_results(xml_directory: Path, destination: Path) -> None:
    paths = sorted(xml_directory.rglob("*.xml")) if xml_directory.is_dir() else []
    if not paths:
        die("Gradle produced no real connectedDebugAndroidTest XML")
    found: dict[str, str] = {}
    for path in paths:
        tree = ElementTree.parse(path)
        for case in tree.findall(".//testcase"):
            klass = case.get("classname", "")
            name = case.get("name", "")
            identifier = klass + "#" + name
            check_id(identifier)
            if identifier in found:
                die("duplicate connected test method in one shard: " + identifier)
            status = (
                "failure" if case.find("failure") is not None
                else "error" if case.find("error") is not None
                else "skipped" if case.find("skipped") is not None
                else "passed"
            )
            if status in ("failure", "error"):
                die("Gradle XML records failing test " + identifier)
            if status == "skipped" and identifier not in STEP_METHODS:
                die("unexpected skipped test on mandatory general shard: " + identifier)
            found[identifier] = status
    if len(found) < 20:
        die("suspiciously small or empty connected instrumentation shard: " + str(len(found)))
    save_lines(destination, set(found), found)
    print("PASS actual connected runner XML shard cases:", len(found))


def verify_union(directory: Path) -> None:
    source_commits: set[str] = set()
    manifests: list[set[str]] = []
    results: list[dict[str, str]] = []
    for index in (0, 1):
        shard = directory / ("orbital-general-" + str(index) + "-coverage")
        source_m, manifest = load_lines(shard / MANIFEST)
        source_r, result = load_lines(shard / RESULTS, with_status=True)
        source_commits.update((source_m, source_r))
        manifests.append(set(manifest))
        results.append(result)
    expected_commit = os.getenv("GITHUB_SHA")
    if len(source_commits) != 1 or (expected_commit and source_commits != {expected_commit}):
        die("shards were not executed from one exact source commit: " + repr(source_commits))
    if manifests[0] != manifests[1]:
        left = manifests[0] - manifests[1]
        right = manifests[1] - manifests[0]
        die("AndroidX unsharded discovery differs across emulators: " +
            "only0=" + repr(sorted(left)[:8]) + " only1=" + repr(sorted(right)[:8]))
    both = set(results[0]) & set(results[1])
    if both:
        die("same @Test method executed by both shards: " + repr(sorted(both)[:8]))
    union = set(results[0]) | set(results[1])
    missing = manifests[0] - union
    extra = union - manifests[0]
    if missing or extra:
        die("not exact AndroidX manifest union: missing=" +
            repr(sorted(missing)[:12]) + " extra=" + repr(sorted(extra)[:12]))
    skipped = {key for shard in results for key, status in shard.items() if status == "skipped"}
    if skipped != STEP_METHODS:
        die("only separately required process-death phases may assume in shards: " +
            repr(sorted(skipped)))
    print("PASS exact disjoint AndroidJUnitRunner 2-shard full method union:",
          "manifest", len(manifests[0]), "shard0", len(results[0]),
          "shard1", len(results[1]), "opt_in_restart", len(skipped),
          "source", next(iter(source_commits)))


def main() -> None:
    if len(sys.argv) != 4 and len(sys.argv) != 3:
        die("usage: ci-verify-android-shards.py manifest|results|union paths...")
    mode = sys.argv[1]
    if mode == "manifest" and len(sys.argv) == 4:
        runner_manifest(Path(sys.argv[2]), Path(sys.argv[3]))
    elif mode == "results" and len(sys.argv) == 4:
        executed_results(Path(sys.argv[2]), Path(sys.argv[3]))
    elif mode == "union" and len(sys.argv) == 3:
        verify_union(Path(sys.argv[2]))
    else:
        die("unsupported verifier mode or argument count: " + repr(sys.argv))


if __name__ == "__main__":
    main()
