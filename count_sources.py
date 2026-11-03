#!/usr/bin/env python3
"""List every Kotlin and C source file under a tree with its line count.

Usage:
    python count_sources.py [root]

The output is ordered by line count, largest first. Build and tooling
directories are skipped so the list is about source, not generated copies.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

SOURCE_SUFFIXES = {".kt", ".c"}
SKIP_DIRECTORIES = {
    ".git",
    ".gradle",
    ".idea",
    "build",
    "out",
    "node_modules",
}


def line_count(path: Path) -> int:
    """Number of lines in *path*, counting a final line without a newline."""
    with path.open("rb") as source:
        return sum(1 for _ in source)


def source_files(root: Path):
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in SOURCE_SUFFIXES:
            continue
        if any(part in SKIP_DIRECTORIES for part in path.relative_to(root).parts[:-1]):
            continue
        yield path


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="List Kotlin (.kt) and C (.c) files by line count, descending."
    )
    parser.add_argument(
        "root",
        nargs="?",
        default=".",
        help="Directory to scan (default: current directory).",
    )
    args = parser.parse_args(argv)

    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        parser.error(f"not a directory: {root}")

    rows = [(line_count(path), path) for path in source_files(root)]
    rows.sort(key=lambda row: (-row[0], str(row[1]).lower()))

    if not rows:
        print(f"No .kt or .c files found under {root}")
        return 0

    width = max(len(str(path)) for _, path in rows)
    total = sum(count for count, _ in rows)
    for count, path in rows:
        print(f"{count:>7}  {str(path):<{width}}")

    print(f"{total:>7}  {len(rows)} file(s) total")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
