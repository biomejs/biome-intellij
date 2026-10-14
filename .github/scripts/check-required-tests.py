#!/usr/bin/env python3
"""Require every focused plugin regression to run and pass in Gradle JUnit XML.

Run after ``cleanTest test --no-build-cache`` so reports come from the current run.
"""

import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


DEFAULT_INVENTORY = Path(__file__).resolve().parent.parent / "required-tests"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key {key!r}")
        result[key] = value
    return result


def load_inventory(directory=DEFAULT_INVENTORY):
    files = sorted(directory.glob("*.json"))
    if not files:
        raise ValueError(f"No test inventories found in {directory}")
    required = {}
    for path in files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
            if not isinstance(data, dict) or not data:
                raise ValueError("expected a nonempty object")
            for class_name, methods in data.items():
                if not class_name.strip() or not isinstance(methods, list) or not methods:
                    raise ValueError("expected a class name and nonempty method list")
                if any(not isinstance(method, str) or not method.strip() for method in methods):
                    raise ValueError("method names must be nonempty strings")
                if len(methods) != len(set(methods)):
                    raise ValueError("duplicate method in inventory")
                previous = required.setdefault(class_name, set())
                overlap = previous.intersection(methods)
                if overlap:
                    raise ValueError(f"Duplicate required test {class_name}: {sorted(overlap)}")
                previous.update(methods)
        except (OSError, ValueError) as error:
            raise ValueError(f"Invalid test inventory {path}: {error}") from error
    return required


def check_reports(report_directory, required_tests):
    reports = sorted(report_directory.glob("TEST-*.xml"))
    if not reports:
        return [f"No JUnit reports found in {report_directory}"]

    problems = []
    seen_classes = set()
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            problems.append(f"Cannot parse {report}: {error}")
            continue
        if suite.tag != "testsuite":
            problems.append(f"Expected testsuite root in {report}")
            continue

        class_name = suite.get("name")
        if class_name not in required_tests:
            continue
        if class_name in seen_classes:
            problems.append(f"Duplicate report for {class_name}: {report}")
        seen_classes.add(class_name)

        counts = {}
        for attribute in ("tests", "failures", "errors", "skipped"):
            value = suite.get(attribute)
            try:
                count = int(value)
                if count < 0:
                    raise ValueError
            except (TypeError, ValueError):
                problems.append(f"Invalid {attribute} count {value!r} in {report}")
                continue
            counts[attribute] = count
            if attribute != "tests" and count:
                problems.append(f"{class_name} reports {attribute}={count}")

        cases = suite.findall("testcase")
        if counts.get("tests") != len(cases):
            problems.append(f"Test count does not match {len(cases)} testcases in {report}")
        executed = 0
        seen_methods = set()
        for case in cases:
            method = case.get("name")
            if case.get("classname") != class_name:
                problems.append(f"Unexpected testcase class for {class_name}.{method}")
                continue
            if method in seen_methods:
                problems.append(f"Duplicate method {class_name}.{method}")
            seen_methods.add(method)
            for outcome in ("failure", "error", "skipped"):
                if case.find(outcome) is not None:
                    problems.append(f"{class_name}.{method} contains {outcome}")
            if case.find("skipped") is None:
                executed += 1

        if not executed:
            problems.append(f"No executed tests in {class_name}")
        for method in sorted(required_tests[class_name] - seen_methods):
            problems.append(f"Missing required method {class_name}.{method}")

    for class_name in sorted(required_tests.keys() - seen_classes):
        problems.append(f"Missing required class {class_name}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    parser.add_argument("--inventory-dir", type=Path, default=DEFAULT_INVENTORY)
    args = parser.parse_args()
    try:
        required_tests = load_inventory(args.inventory_dir)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    problems = check_reports(args.report_directory, required_tests)
    if problems:
        print("Required plugin regression checks failed:", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    required_count = sum(len(methods) for methods in required_tests.values())
    print(f"Verified {required_count} required tests across {len(required_tests)} classes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
