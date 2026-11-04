#!/usr/bin/env python3
"""Static checks for Android CI event grouping and exact HOST APK reuse.

The event table models the checked expression, not GitHub's scheduler. A real
closed pull request must still show the old run becoming cancelled before
close-event cancellation can be called verified. All-skipped jobs alone are
not evidence of that behavior. Versioning only the non-PR group isolates its
legacy lock namespace; it does not supply additional GitHub runner capacity.
"""
from pathlib import Path
import re

GROUP_LINE = "  group: android-ci-${{ github.workflow }}-${{ github.event_name == 'pull_request' && format('refs/pull/{0}/merge', github.event.number) || format('v2-{0}', github.ref) }}"
BUILD_GUARD = "    if: ${{ github.event_name != 'pull_request' || github.event.action != 'closed' }}"
EMULATOR_GUARD = "    if: ${{ github.event_name != 'pull_request' || github.event.pull_request.draft == false }}"
WORKFLOW_NAME = "Android — build, JVM tests and emulator"
SUITES = ("general-0", "general-1", "compact", "stage", "visuals", "compact350", "restart")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def modeled_policy(event_name, ref, number=None, action=None, draft=False):
    """Mirror only the documented expression's event contract."""
    if event_name == "pull_request":
        require(isinstance(number, int) and number > 0, "PR event needs its top-level number")
        ref = f"refs/pull/{number}/merge"
    else:
        ref = f"v2-{ref}"
    build = event_name != "pull_request" or action != "closed"
    return (
        f"android-ci-{WORKFLOW_NAME}-{ref}",
        event_name != "workflow_dispatch",
        build,
        build and (event_name != "pull_request" or not draft),
    )


def verify(workflow, compact):
    require(f"name: {WORKFLOW_NAME}\n" in workflow, "Workflow name changed; audit group compatibility")
    require(GROUP_LINE in workflow.splitlines(), "PR grouping expression drifted from tested model")
    require(BUILD_GUARD in workflow.splitlines(), "Closed PR must skip build and its dependent jobs")
    emulator_job = workflow.split("\n  emulator:\n", 1)[1].split("\n  verify_sharded_coverage:\n", 1)[0]
    require(EMULATOR_GUARD in emulator_job.splitlines(), "Draft PRs must not consume emulator runners")
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
        ("opened", "synchronize", "reopened", "ready_for_review", "converted_to_draft", "closed"),
        "Ready PRs must trigger full qualification; draft transitions must be handled",
    )
    require("permissions:\n  contents: read\n" in workflow, "Do not expand token permissions")
    require(not re.search(r"(?m)^\s+actions:\s*write\s*$", workflow), "No cleanup token elevation")

    # Both closed cases must select the old PR group even when GitHub gives a
    # merged close event refs/heads/main. An unrelated PR has its own group.
    cases = (
        # name, event, ref, PR number, action, draft, group suffix, cancel, build, emulator
        ("main push", "push", "refs/heads/main", None, None, False, "v2-refs/heads/main", True, True, True),
        ("PR opened draft", "pull_request", "refs/pull/189/merge", 189, "opened", True, "refs/pull/189/merge", True, True, False),
        ("PR sync draft", "pull_request", "refs/pull/189/merge", 189, "synchronize", True, "refs/pull/189/merge", True, True, False),
        ("PR reopened draft", "pull_request", "refs/pull/189/merge", 189, "reopened", True, "refs/pull/189/merge", True, True, False),
        ("PR made ready", "pull_request", "refs/pull/189/merge", 189, "ready_for_review", False, "refs/pull/189/merge", True, True, True),
        ("PR sync ready", "pull_request", "refs/pull/189/merge", 189, "synchronize", False, "refs/pull/189/merge", True, True, True),
        ("PR returned to draft", "pull_request", "refs/pull/189/merge", 189, "converted_to_draft", True, "refs/pull/189/merge", True, True, False),
        ("PR closed unmerged", "pull_request", "refs/pull/189/merge", 189, "closed", False, "refs/pull/189/merge", True, False, False),
        ("PR closed merged", "pull_request", "refs/heads/main", 189, "closed", False, "refs/pull/189/merge", True, False, False),
        ("other PR ready", "pull_request", "refs/pull/192/merge", 192, "opened", False, "refs/pull/192/merge", True, True, True),
        ("other PR merged", "pull_request", "refs/heads/main", 192, "closed", False, "refs/pull/192/merge", True, False, False),
        ("manual main", "workflow_dispatch", "refs/heads/main", None, None, False, "v2-refs/heads/main", False, True, True),
        ("manual branch", "workflow_dispatch", "refs/heads/work", None, None, False, "v2-refs/heads/work", False, True, True),
    )
    for label, event, ref, number, action, draft, expected_ref, cancel, build, emulator in cases:
        actual = modeled_policy(event, ref, number, action, draft)
        expected = (f"android-ci-{WORKFLOW_NAME}-{expected_ref}", cancel, build, emulator)
        require(actual == expected, f"{label}: {actual!r} != {expected!r}")


    # The fallback must isolate only the legacy non-PR lock. Rotating all
    # namespaces or using run_id would strand PR work or disable latest-wins.
    main_group = modeled_policy("push", "refs/heads/main")[0]
    require(
        main_group != f"android-ci-{WORKFLOW_NAME}-refs/heads/main",
        "Main must leave the legacy concurrency namespace",
    )
    require(
        main_group == modeled_policy("workflow_dispatch", "refs/heads/main")[0],
        "Manual main validation must share the new main push namespace",
    )
    for number in (189, 192, 197):
        legacy_pr_group = f"android-ci-{WORKFLOW_NAME}-refs/pull/{number}/merge"
        require(
            modeled_policy("pull_request", "refs/heads/main", number, "closed")[0] ==
            legacy_pr_group,
            "Keep each existing PR group stable across a merged close event",
        )

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
