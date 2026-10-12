#!/usr/bin/env python3
"""Summarize JFR CPU/allocation evidence restricted to a recorded measurement interval."""

import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import subprocess

from test_report import save_report


def method_names(event):
    """Return stack method names without printing unrelated JVM/environment metadata."""
    frames = (event["values"].get("stackTrace") or {}).get("frames", [])
    return [frame["method"]["type"]["name"].replace("/", ".") + "." + frame["method"]["name"] for frame in frames]


def main():
    """Extract samples and write a report without treating short samples as a capacity guarantee."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jfr", required=True)
    parser.add_argument("--recording", type=Path, required=True)
    parser.add_argument("--workload-report", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    options = parser.parse_args()
    workload = json.loads(options.workload_report.read_text())
    before = datetime.fromisoformat(workload["before"]["sample_time_utc"])
    after = datetime.fromisoformat(workload["after"]["sample_time_utc"])
    options.report_dir.mkdir(parents=True, exist_ok=True)
    with (options.report_dir / "15-jfr-summary.log").open("w") as output:
        subprocess.run([options.jfr, "summary", str(options.recording)], stdout=output, check=True)
    events_path = options.report_dir / "15-jfr-events.json"
    with events_path.open("w") as output:
        subprocess.run([options.jfr, "print", "--json", "--events",
                        "jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.JavaMonitorEnter", "--stack-depth", "24",
                        str(options.recording)], stdout=output, check=True)
    events = json.loads(events_path.read_text())["recording"]["events"]
    selected = [event for event in events if before <= datetime.fromisoformat(event["values"]["startTime"]) <= after]
    cpu = Counter()
    inclusive = Counter()
    allocation = Counter()
    allocation_sites = Counter()
    monitors = Counter()
    for event in selected:
        names = method_names(event)
        if event["type"] == "jdk.ExecutionSample":
            if names:
                cpu[names[0]] += 1
            inclusive.update(set(name for name in names if name.startswith("com.akakata.")))
        elif event["type"] == "jdk.ObjectAllocationSample":
            weight = event["values"]["weight"]
            allocation[event["values"]["objectClass"]["name"].replace("/", ".")] += weight
            if names:
                allocation_sites[names[0]] += weight
        elif event["type"] == "jdk.JavaMonitorEnter":
            monitors[event["values"]["monitorClass"]["name"].replace("/", ".")] += 1
    report = {"artifact_sha256": workload["artifact_sha256"], "recording": str(options.recording.resolve()),
              "measurement_start": before.isoformat(), "measurement_end": after.isoformat(),
              "interval_seconds": (after - before).total_seconds(),
              "whole_recording_events": dict(Counter(event["type"] for event in events)),
              "measurement_events": dict(Counter(event["type"] for event in selected)),
              "top_cpu_leaf_samples": cpu.most_common(12), "application_inclusive_cpu_samples": inclusive.most_common(12),
              "sampled_allocation_weight_by_class": allocation.most_common(12),
              "sampled_allocation_weight_by_leaf_site": allocation_sites.most_common(12),
              "sampled_lock_events_by_class": monitors.most_common(12),
              "limitations": ["One short synthetic echo workload; CPU samples are sparse.",
                              "Allocation weights are sampling estimates, not exact byte accounting.",
                              "Thread/resource recommendations also require unprofiled measurements.",
                              "No production business handlers or concurrent 8-12 server processes were exercised."]}
    save_report(options.report_dir, "15-jfr-profile", report)
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
