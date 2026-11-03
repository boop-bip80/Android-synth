#!/usr/bin/env python3
"""Static checks for Android CI event grouping and exact HOST APK reuse.

The event table models the checked expression, not GitHub's scheduler. A real
closed pull request must still show the old run becoming cancelled before
close-event cancellation can be called verified. All-skipped jobs alone are
not evidence of that behavior.
"""
from pathlib import Path
import re

GROUP_LINE = "  group: android-ci-${{ github.workflow }}-${{ github.event_name == 'pull_request' && format('refs/pull/{0}/merge', github.event.number) || github.ref }}"
BUILD_GUARD = "    if: ${{ github.event_name != 'pull_request' || github.event.action != 'closed' }}"
WORKFLOW_NAME = "Android — build, JVM tests and emulator"
SUITES = ("general-0", "general-1", "compact", "stage", "visuals", "compact350", "restart")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def modeled_policy(event_name, ref, number=None, action=None):
    """Mirror only the documented expression's event contract."""
    if event_name == "pull_request":
        require(isinstance(number, int) and number > 0, "PR event needs its top-level number")
        ref = f"refs/pull/{number}/merge"
    return (
        f"android-ci-{WORKFLOW_NAME}-{ref}",
        event_name != "workflow_dispatch",
        event_name != "pull_request" or action != "closed",
    )


def verify(workflow, compact):
    require(f"name: {WORKFLOW_NAME}\n" in workflow, "Workflow name changed; audit group compatibility")
    require(GROUP_LINE in workflow.splitlines(), "PR grouping expression drifted from tested model")
    require(BUILD_GUARD in workflow.splitlines(), "Closed PR must skip build and its dependent jobs")
    require(
        "  cancel-in-progress: " + "$" + "{{ github.event_name != 'workflow_dispatch' }}" in workflow,
        "Keep latest-wins main/PR behavior and noncancelling dispatch",
    )
    require("  push:\n    branches: [main]\n" in workflow, "Keep main push validation enabled")
    require("  workflow_dispatch:\n" in workflow, "Keep manual validation available")
    trigger = re.search(r"(?m)^  pull_request:\n    types: \[([^\]]+)\]$", workflow)
    require(trigger is not None, "Explicit PR actions required for close-event grouping")
    require(
        tuple(value.strip() for value in trigger.group(1).split(",")) ==
        ("opened", "synchronize", "reopened", "closed"),
        "Preserve prior PR defaults and add only closed",
    )
    require("permissions:\n  contents: read\n" in workflow, "Do not expand token permissions")
    require(not re.search(r"(?m)^\s+actions:\s*write\s*$", workflow), "No cleanup token elevation")

    # Both closed cases must select the old PR group even when GitHub gives a
    # merged close event refs/heads/main. An unrelated PR has its own group.
    cases = (
        ("main push", "push", "refs/heads/main", None, None, "refs/heads/main", True, True),
        ("PR opened", "pull_request", "refs/pull/189/merge", 189, "opened", "refs/pull/189/merge", True, True),
        ("PR sync", "pull_request", "refs/pull/189/merge", 189, "synchronize", "refs/pull/189/merge", True, True),
        ("PR reopened", "pull_request", "refs/pull/189/merge", 189, "reopened", "refs/pull/189/merge", True, True),
        ("PR closed unmerged", "pull_request", "refs/pull/189/merge", 189, "closed", "refs/pull/189/merge", True, False),
        ("PR closed merged", "pull_request", "refs/heads/main", 189, "closed", "refs/pull/189/merge", True, False),
        ("other PR", "pull_request", "refs/pull/192/merge", 192, "opened", "refs/pull/192/merge", True, True),
        ("other PR merged", "pull_request", "refs/heads/main", 192, "closed", "refs/pull/192/merge", True, False),
        ("manual main", "workflow_dispatch", "refs/heads/main", None, None, "refs/heads/main", False, True),
        ("manual branch", "workflow_dispatch", "refs/heads/work", None, None, "refs/heads/work", False, True),
    )
    for label, event, ref, number, action, expected_ref, cancel, build in cases:
        actual = modeled_policy(event, ref, number, action)
        expected = (f"android-ci-{WORKFLOW_NAME}-{expected_ref}", cancel, build)
        require(actual == expected, f"{label}: {actual!r} != {expected!r}")

    jobs = workflow.split("\njobs:\n", 1)[1]
    require(
        re.findall(r"(?m)^  ([a-z_]+):\s*$", jobs) ==
        ["build", "emulator", "verify_sharded_coverage"],
        "Do not add or remove main validation jobs",
    )
    require(
        "        suite: [" + ", ".join(SUITES) + "]" in workflow,
        "All seven emulator suites must remain",
    )
    require("    needs: build\n" in jobs, "Emulators must wait for successful HOST")
    require("    needs: [build, emulator]\n" in jobs, "Union gate must depend on all emulator suites")
    require(1 + len(SUITES) + 1 == 9, "Preserve nine main gates")

    download = workflow.split("- name: Download validated app APK from host build\n", 1)[1]
    download = download.split("- name:", 1)[0]
    require("matrix.suite == 'compact'" in download, "COMPACT must download HOST APK")
    require("name: orbital-debug-apk\n" in download, "Use the existing HOST app artifact")
    require("path: app/build/outputs/apk/debug\n" in download, "Use the same installed APK path")
    require("run-id:" not in download, "Artifact must come from this run, not another source")
    require("artifacts/compact-apk-provenance.txt" in workflow, "Upload compact APK digest evidence")
    require(
        not re.search(r"(?m)^[ \t]*(?:timeout[ \t]+\S+[ \t]+)?(?:\./)?gradlew?(?:[ \t]|$)", compact),
        "COMPACT must not rebuild after QEMU starts",
    )
    require('if [[ ! -s "$app_apk" ]]; then' in compact, "Missing HOST artifact must fail closed")
    require('unzip -tq "$app_apk"' in compact, "Corrupt APK must fail closed")
    require('sha256sum "$app_apk"' in compact, "Log the installed APK digest")
    require('timeout 120s adb install -r "$app_apk"' in compact, "Install the checked HOST APK")
    for proof in ("case1_sound_keyboard=PASS", "case2_fx_sound_keyboard=PASS", "min_px = 80 * density / 160"):
        require(proof in compact, f"Retain strict real-device compact proof: {proof}")
    return len(cases)


def main():
    here = Path(__file__).resolve().parent
    count = verify(
        (here / "android.yml").read_text(encoding="utf-8"),
        (here / "ci-compact-geometry.sh").read_text(encoding="utf-8"),
    )
    print(f"PASS: {count} event/group cases, nine main gates, same-run COMPACT HOST APK")
    print("Close-event cancellation still needs a real GitHub runtime observation.")


if __name__ == "__main__":
    main()
