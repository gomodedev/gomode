#!/usr/bin/env python3
"""Verify Android instrumented tests do not read stale UiAutomation accessibility caches."""

import os
import re
import subprocess
import sys

FORBIDDEN_UNTIL = re.compile(r"\bUntil\.(?:hasObject|findObject|findObjects|gone)\b")
RAW_LOOKUP = re.compile(r"(?<!\w)(?:hasObject|findObject|findObjects)\(")
CLEAR_CACHE = re.compile(r"\bclearCache\(\)")
FUNCTION_BOUNDARY = re.compile(r"\bfun\b|[}]")
# Allow up to three preceding code lines between clearCache() and the raw lookup in a helper.
LOOKBACK_LINES = 3


def android_test_files() -> list[str]:
    """Return tracked and untracked Kotlin files under Android instrumented test source sets."""
    raw = subprocess.check_output(("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"))
    files: list[str] = []
    for entry in raw.split(b"\0"):
        path = os.fsdecode(entry)
        if (
            path.startswith("android/")
            and "/src/androidTest/" in path
            and path.endswith(".kt")
            and os.path.lexists(path)
        ):
            files.append(path)
    return sorted(files)


def strip_line_comment(line: str) -> str:
    """Strip a // comment outside quoted strings."""
    in_string = False
    escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
            continue
        if char == "\\" and in_string:
            escaped = True
            continue
        if char == '"':
            in_string = not in_string
            continue
        if not in_string and char == "/" and index + 1 < len(line) and line[index + 1] == "/":
            return line[:index]
    return line


def lint_file(path: str) -> list[str]:
    """Return lint violations found in one Android instrumented test file."""
    with open(path, encoding="utf-8") as handle:
        lines = handle.readlines()

    violations: list[str] = []
    recent_code: list[str] = []
    in_block_comment = False

    for lineno, raw_line in enumerate(lines, start=1):
        line = raw_line
        if in_block_comment:
            end = line.find("*/")
            if end == -1:
                continue
            line = line[end + 2 :]
            in_block_comment = False
        while "/*" in line:
            start = line.find("/*")
            end = line.find("*/", start + 2)
            if end == -1:
                line = line[:start]
                in_block_comment = True
                break
            line = line[:start] + line[end + 2 :]

        code = strip_line_comment(line).strip()
        if not code:
            continue

        if FORBIDDEN_UNTIL.search(code):
            violations.append(f"{path}:{lineno}: cached Until.* lookup; use awaitFreshObject/awaitFreshGone instead")
        elif RAW_LOOKUP.search(code):
            if not any(CLEAR_CACHE.search(prev) for prev in recent_code[-LOOKBACK_LINES:]):
                violations.append(
                    f"{path}:{lineno}: uncached UiDevice lookup; use hasFreshObject/findFreshObject/awaitFreshObject "
                    "or call uiAutomation.clearCache() immediately before"
                )

        if FUNCTION_BOUNDARY.search(code):
            recent_code.clear()
        else:
            recent_code.append(code)

    return violations


def main() -> int:
    """Check all Android instrumented test files for cached UiAutomation lookups."""
    violations: list[str] = []
    for path in android_test_files():
        violations.extend(lint_file(path))

    if violations:
        print("Android instrumented test accessibility-cache lint failed:", file=sys.stderr)
        print(*(f"  {item}" for item in violations), sep="\n", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
