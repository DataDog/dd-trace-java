#!/usr/bin/env python3
"""Convert CI Visibility suite aggregation rows to instrumentation scheduler scores."""

import argparse
import json
import math
import re
from collections import defaultdict
from pathlib import Path


def convert(measurements, settings):
    meta = measurements.get("meta", {})
    if meta.get("status") != "done" or meta.get("warnings"):
        raise ValueError("Scores require a completed aggregation without warnings")
    paths = set(re.findall(r'"(:dd-java-agent:instrumentation:[^"]+)"', settings))
    by_name = defaultdict(list)
    for path in sorted(paths):
        by_name[path.rsplit(":", 1)[-1]].append(path)

    tasks = defaultdict(dict)
    ignored = set()
    pipelines = set()
    for row in measurements["rows"]:
        prefix = row["module"].split("_TEST-", 1)[0]
        names = [name for name in by_name if prefix.startswith(name + "_")]
        if not names:
            ignored.add(row["module"])
            continue
        name = max(names, key=len)
        if len(by_name[name]) != 1:
            raise ValueError(f"Ambiguous project name: {name}")
        task_name = prefix[len(name) + 1:]
        if "latest" in task_name.lower() or task_name == "traceAgentTest":
            continue
        task = by_name[name][0] + ":" + task_name
        class_name = row["className"]
        duration = float(row["meanSeconds"])
        if not class_name or not math.isfinite(duration) or duration < 0:
            raise ValueError(f"Invalid score: {task}:{class_name}")
        if class_name in tasks[task]:
            raise ValueError(f"Duplicate score: {task}:{class_name}")
        tasks[task][class_name] = round(duration, 3)
        pipelines.add(int(row["pipelines"]))

    if not tasks:
        raise ValueError("No instrumentation scores found")
    metadata = {
        "from": measurements["from"],
        "to": measurements["to"],
        "query": measurements["query"],
        "aggregation": "mean suite duration in seconds per observed execution",
        "pipelines_per_suite": sorted(pipelines),
        "reference_jvm": 11,
    }
    return {
        "_metadata": metadata,
        "tasks": {task: dict(sorted(classes.items())) for task, classes in sorted(tasks.items())},
    }, ignored


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Exported CI Visibility aggregation")
    parser.add_argument("--output", type=Path, default=root / "buildSrc/src/main/resources/datadog/gradle/plugin/ci/instrumentation-test-durations.json")
    args = parser.parse_args()
    measurements = json.loads(args.input.read_text())
    scores, ignored = convert(measurements, (root / "settings.gradle.kts").read_text())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(scores, indent=2) + "\n")
    print(f"Wrote {len(scores['tasks'])} tasks; ignored {len(ignored)} reports outside instrumentation projects")


if __name__ == "__main__":
    main()
