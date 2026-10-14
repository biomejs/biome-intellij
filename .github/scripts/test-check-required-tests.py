#!/usr/bin/env python3
"""Exercise the regression gate with standalone Gradle JUnit XML samples."""

from pathlib import Path
import subprocess
import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


REQUIRED_TESTS = {
    "sample.FirstTest": ["testOne", "testTwo"],
    "sample.SecondTest": ["testThree"],
}
GUARD = Path(__file__).with_name("check-required-tests.py")


class RequiredTestsGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.reports = Path(self.temporary_directory.name) / "reports"
        self.reports.mkdir()
        self.inventory = Path(self.temporary_directory.name) / "inventory"
        self.inventory.mkdir()
        (self.inventory / "sample.json").write_text(json.dumps(REQUIRED_TESTS))
        for class_name, methods in REQUIRED_TESTS.items():
            suite = ET.Element(
                "testsuite", name=class_name, tests=str(len(methods)),
                failures="0", errors="0", skipped="0",
            )
            for method in methods:
                ET.SubElement(suite, "testcase", classname=class_name, name=method)
            self.write_suite(suite)
        self.class_name = next(iter(REQUIRED_TESTS))
        self.report = self.reports / f"TEST-{self.class_name}.xml"

    def write_suite(self, suite):
        path = self.reports / f"TEST-{suite.attrib['name']}.xml"
        ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)

    def run_guard(self, expected_error=None, reports=None):
        result = subprocess.run(
            [sys.executable, str(GUARD), str(reports or self.reports), "--inventory-dir", str(self.inventory)],
            capture_output=True, text=True, timeout=10,
        )
        if expected_error is None:
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("3 required tests across 2 classes", result.stdout)
        else:
            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn(expected_error, result.stderr)

    def test_valid_reports_pass(self):
        self.run_guard()

    def test_missing_directory_fails(self):
        self.run_guard("No JUnit reports", self.reports / "missing")

    def test_empty_report_directory_fails(self):
        empty = self.reports / "empty"
        empty.mkdir()
        self.run_guard("No JUnit reports", empty)

    def test_removed_report_fails(self):
        self.report.unlink()
        self.run_guard("Missing required class")

    def test_missing_method_fails(self):
        suite = ET.parse(self.report).getroot()
        suite.remove(suite.find("testcase"))
        suite.set("tests", str(len(suite.findall("testcase"))))
        self.write_suite(suite)
        self.run_guard("Missing required method")

    def test_zero_test_report_fails(self):
        suite = ET.parse(self.report).getroot()
        for case in suite.findall("testcase"):
            suite.remove(case)
        suite.set("tests", "0")
        self.write_suite(suite)
        self.run_guard("No executed tests")

    def test_skipped_failed_and_errored_cases_fail(self):
        for outcome, summary in (("skipped", "skipped"), ("failure", "failures"), ("error", "errors")):
            with self.subTest(outcome=outcome):
                suite = ET.parse(self.report).getroot()
                case = suite.find("testcase")
                ET.SubElement(case, outcome, message="sample outcome")
                suite.set(summary, "1")
                self.write_suite(suite)
                self.run_guard(f"contains {outcome}")
                case.remove(case.find(outcome))
                suite.set(summary, "0")
                self.write_suite(suite)

    def test_outcome_without_summary_count_fails(self):
        suite = ET.parse(self.report).getroot()
        ET.SubElement(suite.find("testcase"), "skipped")
        self.write_suite(suite)
        self.run_guard("contains skipped")

    def test_nonzero_summary_without_outcome_fails(self):
        for summary in ("skipped", "failures", "errors"):
            with self.subTest(summary=summary):
                suite = ET.parse(self.report).getroot()
                suite.set(summary, "1")
                self.write_suite(suite)
                self.run_guard(f"{summary}=1")
                suite.set(summary, "0")
                self.write_suite(suite)

    def test_extra_skipped_method_in_required_class_fails(self):
        suite = ET.parse(self.report).getroot()
        case = ET.SubElement(suite, "testcase", classname=self.class_name, name="testAnotherMethod")
        ET.SubElement(case, "skipped")
        suite.set("tests", str(len(suite.findall("testcase"))))
        suite.set("skipped", "1")
        self.write_suite(suite)
        self.run_guard("testAnotherMethod contains skipped")

    def test_malformed_xml_fails(self):
        self.report.write_text("<testsuite>", encoding="utf-8")
        self.run_guard("Cannot parse")

    def test_invalid_counts_fail(self):
        for value in ("bad", "-1", "1.5"):
            with self.subTest(value=value):
                suite = ET.parse(self.report).getroot()
                suite.set("tests", value)
                self.write_suite(suite)
                self.run_guard("Invalid tests count")

    def test_missing_count_fails(self):
        suite = ET.parse(self.report).getroot()
        del suite.attrib["tests"]
        self.write_suite(suite)
        self.run_guard("Invalid tests count")

    def test_report_count_mismatch_fails(self):
        suite = ET.parse(self.report).getroot()
        suite.set("tests", "999")
        self.write_suite(suite)
        self.run_guard("does not match")

    def test_wrong_testcase_class_fails(self):
        suite = ET.parse(self.report).getroot()
        suite.find("testcase").set("classname", "unrelated.Class")
        self.write_suite(suite)
        self.run_guard("Unexpected testcase class")

    def test_duplicate_method_fails(self):
        suite = ET.parse(self.report).getroot()
        method = REQUIRED_TESTS[self.class_name][0]
        ET.SubElement(suite, "testcase", classname=self.class_name, name=method)
        suite.set("tests", str(len(suite.findall("testcase"))))
        self.write_suite(suite)
        self.run_guard("Duplicate method")

    def test_duplicate_class_report_fails(self):
        (self.reports / "TEST-duplicate.xml").write_bytes(self.report.read_bytes())
        self.run_guard("Duplicate report")

    def test_unexpected_xml_root_fails(self):
        self.report.write_text("<not-a-test-report/>", encoding="utf-8")
        self.run_guard("Expected testsuite")


    def test_missing_inventory_fails(self):
        (self.inventory / "sample.json").unlink()
        self.run_guard("No test inventories")

    def test_invalid_inventory_fails(self):
        for content in ("{", "[]", "{}", '{"sample.Test": []}', '{"sample.Test": "testOne"}', '{"sample.Test": [""]}', '{"sample.Test": ["testOne", "testOne"]}', '{"": ["testOne"]}', '{"sample.Test": [1]}'):
            with self.subTest(content=content):
                (self.inventory / "sample.json").write_text(content)
                self.run_guard("Invalid test inventory")

    def test_separate_inventories_are_combined(self):
        (self.inventory / "sample.json").write_text(json.dumps({"sample.FirstTest": ["testOne"]}))
        (self.inventory / "another.json").write_text(json.dumps({"sample.FirstTest": ["testTwo"], "sample.SecondTest": ["testThree"]}))
        self.run_guard()

    def test_duplicate_inventory_entries_fail(self):
        (self.inventory / "another.json").write_text(json.dumps({"sample.FirstTest": ["testOne"]}))
        self.run_guard("Duplicate required test")

    def test_duplicate_json_keys_fail(self):
        (self.inventory / "sample.json").write_text('{"sample.Test": ["a"], "sample.Test": ["b"]}')
        self.run_guard("Duplicate JSON key")

if __name__ == "__main__":
    unittest.main()
