#!/usr/bin/env python3
"""Run the required named inventory on the chosen IDE task, removing old reports.

BIOME_GRADLE_RUNNER may name a local wrapper (e.g. the cloud process-slot helper).
CI uses the repository wrapper directly. Additional arguments are passed to Gradle.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", choices=("test", "testCurrentIde"), default="test")
    args, extra = parser.parse_known_args()
    spec = importlib.util.spec_from_file_location("required_tests", Path(__file__).with_name("check-required-tests.py"))
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    try:
        required_tests = guard.load_inventory()
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    command = ["./gradlew", "clean" + args.task[0].upper() + args.task[1:], args.task, "--no-build-cache", "--console=plain"]
    for class_name in sorted(required_tests):
        command.extend(["--tests", class_name])
    command.extend(extra)
    if os.environ.get("BIOME_GRADLE_RUNNER"):
        command.insert(0, os.environ["BIOME_GRADLE_RUNNER"])
    return subprocess.call(command)


if __name__ == "__main__":
    sys.exit(main())
