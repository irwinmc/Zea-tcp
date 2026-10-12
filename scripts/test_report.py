#!/usr/bin/env python3
"""Run a verification command and persist its log, outcome and per-JUnit-case reports."""

import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET


def save_report(directory, name, record):
    """Write machine-readable evidence and a short readable report atomically."""
    directory.mkdir(parents=True, exist_ok=True)
    for suffix, content in [("json", json.dumps(record, indent=2) + "\n"),
                            ("txt", "\n".join(f"{key}: {value}" for key, value in record.items()) + "\n")]:
        destination = directory / f"{name}.{suffix}"
        temporary = destination.with_suffix(destination.suffix + ".tmp")
        temporary.write_text(content, encoding="utf-8")
        temporary.replace(destination)


def main():
    """Execute the requested command and preserve failures as well as passing results."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    command = options.command[1:] if options.command[:1] == ["--"] else options.command
    if not command or Path(options.name).name != options.name:
        parser.error("A command and a plain report name are required")
    options.report_dir.mkdir(parents=True, exist_ok=True)
    log_path = options.report_dir / (options.name + ".log")
    started = time.time()
    failure = None
    print("START " + options.name, flush=True)
    with log_path.open("w", encoding="utf-8") as log:
        try:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=options.timeout)
            exit_code = result.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            failure = str(error)
            exit_code = 124
    cases = []
    for source in sorted(Path("target/surefire-reports").glob("TEST-*.xml")):
        if source.stat().st_mtime < started:
            continue
        for index, case in enumerate(ET.parse(source).getroot().iter("testcase")):
            errors = [child for child in case if child.tag in ("error", "failure")]
            record = {"class": case.get("classname"), "test": case.get("name"),
                      "seconds": float(case.get("time", "0")),
                      "status": "failed" if errors else "skipped" if case.find("skipped") is not None else "passed",
                      "errors": [child.text for child in errors], "suite_evidence": str(source.resolve())}
            name = source.stem.removeprefix("TEST-") + "-" + str(index)
            save_report(options.report_dir / (options.name + "-cases"), name, record)
            cases.append(record)
    record = {"command": command, "exit_code": exit_code, "seconds": round(time.time() - started, 3),
              "status": "passed" if exit_code == 0 else "failed", "runner_failure": failure,
              "log": str(log_path.resolve()), "junit_cases": len(cases),
              "passed": sum(case["status"] == "passed" for case in cases),
              "failed": sum(case["status"] == "failed" for case in cases),
              "skipped": sum(case["status"] == "skipped" for case in cases)}
    save_report(options.report_dir, options.name, record)
    print(json.dumps(record), flush=True)
    if exit_code:
        print("\n".join(log_path.read_text(errors="replace").splitlines()[-35:]), flush=True)
    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()
