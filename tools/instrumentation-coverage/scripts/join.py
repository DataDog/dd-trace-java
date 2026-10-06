#!/usr/bin/env python3
"""Bind documentation-backed flow steps to a bytecode graph and optional runtime evidence."""

import argparse
import base64
import collections
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_graph", type=Path)
    parser.add_argument("flow_manifest", type=Path)
    parser.add_argument("output_directory", type=Path)
    parser.add_argument("--coverage-root", type=Path)
    parser.add_argument("--profile-analysis", type=Path)
    parser.add_argument("--catalog-assessment", type=Path)
    parser.add_argument("--run-manifest", type=Path)
    parser.add_argument("--test-results-root", type=Path)
    parser.add_argument("--output-prefix", default="flow-analysis")
    return parser.parse_args()


def normalize_observed_method(method):
    descriptor = method.index("(")
    separator = method.rfind(".", 0, descriptor)
    if separator < 0:
        return method
    return method[:separator].replace(".", "/") + "#" + method[separator + 1 :]


def load_observations(root):
    observations = collections.defaultdict(
        lambda: {
            "root": 0,
            "context": 0,
            "scenarios": set(),
            "evidence_sources": set(),
            "tests": collections.defaultdict(
                lambda: {
                    "root": 0,
                    "context": 0,
                    "attribution": set(),
                    "attributionConfidence": set(),
                    "scenarioId": None,
                    "threads": {},
                    "source": None,
                    "name": None,
                }
            ),
        }
    )
    if not root or not root.exists():
        return observations, [], {}
    reports = sorted(root.rglob("report.json"))
    report_metadata = []
    method_inventory = {}
    for report_path in reports:
        report = json.loads(report_path.read_text())
        evidence_source = report_path.parent.name
        relative_parts = report_path.relative_to(root).parts
        target = relative_parts[0] if len(relative_parts) >= 4 else None
        report_metadata.append(
            {
                "path": str(report_path),
                "source": evidence_source,
                "target": target,
                "summary": report.get("summary", {}),
                "health": report.get("health", {}),
                "finalized": report.get("finalized", False),
                "execution": {
                    key: report.get("execution", {}).get(key)
                    for key in ("mode", "specName", "scenarioAttribution")
                },
            }
        )
        for method in report.get("methods", []):
            method_id = normalize_observed_method(method["method"])
            owner, member = method_id.split("#", 1)
            name = member[: member.index("(")]
            method_inventory.setdefault(
                method_id,
                {
                    "id": method_id,
                    "displayMethod": method["method"],
                    "owner": owner.replace("/", "."),
                    "name": name,
                    "tests": {},
                },
            )
            for observation in method.get("observations", []):
                count = observation.get("count", 0)
                scenario = observation.get("scenario", "<unknown>")
                scenario_id = observation.get("scenarioId", scenario)
                test_id = (target + "::" if target else "") + evidence_source + "::" + scenario_id
                test = observations[method_id]["tests"][test_id]
                test["source"] = evidence_source
                test["target"] = target
                test["name"] = scenario
                test["scenarioId"] = scenario_id
                test["attribution"].add(observation.get("attribution", "UNSPECIFIED"))
                test["attributionConfidence"].add(
                    observation.get("attributionConfidence", "UNSPECIFIED")
                )
                thread_id = observation.get("threadId")
                thread_name = observation.get("threadName", "<unknown>")
                thread_key = f"{thread_id}:{thread_name}"
                thread = test["threads"].setdefault(
                    thread_key,
                    {
                        "id": thread_id,
                        "name": thread_name,
                        "rootEntries": 0,
                        "contextEntries": 0,
                    },
                )
                if observation.get("state") == "NON_ROOT_CONTEXT":
                    observations[method_id]["context"] += count
                    test["context"] += count
                    thread["contextEntries"] += count
                else:
                    observations[method_id]["root"] += count
                    test["root"] += count
                    thread["rootEntries"] += count
                observations[method_id]["scenarios"].add(scenario)
                observations[method_id]["evidence_sources"].add(evidence_source)
    for method_id, method in method_inventory.items():
        method["tests"] = {
            test_id: {
                **test,
                "attribution": sorted(test["attribution"]),
                "attributionConfidence": sorted(test["attributionConfidence"]),
                "threads": sorted(
                    test["threads"].values(),
                    key=lambda thread: (thread["name"], thread["id"] or -1),
                ),
            }
            for test_id, test in observations[method_id]["tests"].items()
        }
    return observations, report_metadata, method_inventory


def load_test_results(root):
    if not root or not root.exists():
        return None
    totals = {"tests": 0, "passed": 0, "skipped": 0, "failures": 0, "errors": 0}
    reports = []
    for report_path in sorted(root.rglob("TEST-*.xml")):
        suite = ET.parse(report_path).getroot()
        counts = {
            key: int(suite.attrib.get(key, 0))
            for key in ("tests", "skipped", "failures", "errors")
        }
        counts["passed"] = (
            counts["tests"] - counts["skipped"] - counts["failures"] - counts["errors"]
        )
        for key in totals:
            totals[key] += counts[key]
        reports.append({"path": str(report_path), "suite": suite.attrib.get("name"), **counts})
    return {**totals, "reports": reports}


def load_profile(path):
    profile = collections.defaultdict(
        lambda: {"cpu": 0, "allocation": 0, "blocking": 0}
    )
    if not path or not path.exists():
        return profile, None
    analysis = json.loads(path.read_text())
    for signal in ("cpu", "allocation", "blocking"):
        for method in analysis.get("signals", {}).get(signal, {}).get("libraryMethods", []):
            method_id = normalize_observed_method(method["method"])
            profile[method_id][signal] += method.get("stackOccurrences", 0)
    metadata = {
        "path": str(path),
        "comparison": analysis.get("comparison", {}),
        "genericContextStateAvailable": analysis.get("genericContextStateAvailable", False),
        "contextNote": analysis.get("contextNote"),
    }
    return profile, metadata


def shortest_path(starts, targets, adjacency):
    targets = set(targets)
    queue = collections.deque(sorted(starts))
    previous = {node: None for node in starts}
    while queue:
        current = queue.popleft()
        if current in targets:
            path = []
            while current is not None:
                path.append(current)
                current = previous[current]
            return list(reversed(path))
        for adjacent in sorted(adjacency.get(current, ())):
            if adjacent not in previous:
                previous[adjacent] = current
                queue.append(adjacent)
    return []


def state_for(method_ids, observations, selected_methods, test_id=None):
    if test_id is None:
        evidence = [observations[method] for method in method_ids]
    else:
        evidence = [
            observations[method]["tests"].get(test_id, {"root": 0, "context": 0})
            for method in method_ids
        ]
    root = sum(item["root"] for item in evidence)
    context = sum(item["context"] for item in evidence)
    scenarios = sorted(
        {scenario for method in method_ids for scenario in observations[method]["scenarios"]}
    )
    evidence_sources = sorted(
        {
            source
            for method in method_ids
            for source in observations[method]["evidence_sources"]
        }
    )
    threads = {}
    if test_id is not None:
        for method in method_ids:
            for thread in observations[method]["tests"].get(test_id, {}).get(
                "threads", {}
            ).values():
                key = (thread["id"], thread["name"])
                combined = threads.setdefault(
                    key,
                    {
                        "id": thread["id"],
                        "name": thread["name"],
                        "rootEntries": 0,
                        "contextEntries": 0,
                    },
                )
                combined["rootEntries"] += thread["rootEntries"]
                combined["contextEntries"] += thread["contextEntries"]
    if root and context:
        state = "MIXED"
    elif context:
        state = "NON_ROOT_CONTEXT"
    elif root:
        state = "ROOT_CONTEXT"
    elif any(method in selected_methods for method in method_ids):
        state = "UNOBSERVED"
    else:
        state = "OUTSIDE_ENTRY_INVENTORY"
    return {
        "state": state,
        "rootEntries": root,
        "contextEntries": context,
        "scenarios": scenarios,
        "evidenceSources": evidence_sources,
        "threads": sorted(
            threads.values(), key=lambda thread: (thread["name"], thread["id"] or -1)
        ),
    }


def test_has_step(step, observations, test_id):
    return any(
        observations[method]["tests"].get(test_id, {}).get("root", 0)
        or observations[method]["tests"].get(test_id, {}).get("context", 0)
        for method in step["matches"]
    )


def tests_for_flow(flow, observations, selected_methods):
    identification = flow.get("identification", {})
    all_of = identification.get("allOf", flow.get("testEvidenceAnchors", []))
    any_of = identification.get("anyOf", [])
    none_of = identification.get("noneOf", [])
    by_id = {step["id"]: step for step in flow["steps"]}
    candidate_ids = set()
    for step_id in set(all_of + any_of + none_of):
        for method in by_id.get(step_id, {}).get("matches", []):
            candidate_ids.update(observations[method]["tests"])

    tests = {}
    for test_id in sorted(candidate_ids):
        if all_of and not all(test_has_step(by_id[step], observations, test_id) for step in all_of):
            continue
        if any_of and not any(test_has_step(by_id[step], observations, test_id) for step in any_of):
            continue
        if any(test_has_step(by_id[step], observations, test_id) for step in none_of):
            continue
        evidence_sample = next(
            (
                observations[method]["tests"][test_id]
                for step in flow["steps"]
                for method in step["matches"]
                if test_id in observations[method]["tests"]
            ),
            None,
        )
        if not evidence_sample or evidence_sample["name"] == "<unattributed>":
            continue
        tests[test_id] = {
            "id": test_id,
            "name": evidence_sample["name"],
            "suite": evidence_sample["source"],
            "target": evidence_sample.get("target"),
            "anchorSteps": set(
                step_id
                for step_id in set(all_of + any_of)
                if test_has_step(by_id[step_id], observations, test_id)
            ),
            "observedSteps": set(),
            "contextEntries": 0,
            "rootEntries": 0,
            "attribution": set(),
            "attributionConfidence": set(),
            "scenarioId": evidence_sample.get("scenarioId"),
            "methods": {},
        }
    expected_methods = flow["expectedMethods"]
    for test_id, test in tests.items():
        for step in flow["steps"]:
            evidence = state_for(step["matches"], observations, set(), test_id)
            if evidence["contextEntries"] or evidence["rootEntries"]:
                test["observedSteps"].add(step["id"])
                test["contextEntries"] += evidence["contextEntries"]
                test["rootEntries"] += evidence["rootEntries"]
                for method in step["matches"]:
                    method_test = observations[method]["tests"].get(test_id)
                    if method_test:
                        test["attribution"].update(method_test["attribution"])
                        test["attributionConfidence"].update(
                            method_test["attributionConfidence"]
                        )
        for method in expected_methods:
            evidence = state_for(
                [method["methodId"]], observations, selected_methods, test_id
            )
            test["methods"][method["methodId"]] = evidence
        test["anchorSteps"] = sorted(test["anchorSteps"])
        test["observedSteps"] = sorted(test["observedSteps"])
        test["attribution"] = sorted(test["attribution"])
        test["attributionConfidence"] = sorted(test["attributionConfidence"])
    return sorted(tests.values(), key=lambda test: (test["suite"], test["name"]))


def coverage_summary(methods):
    counts = collections.Counter(method["runtimeEvidence"]["state"] for method in methods)
    observable = sum(
        counts[state]
        for state in ("NON_ROOT_CONTEXT", "ROOT_CONTEXT", "MIXED", "UNOBSERVED")
    )
    observed = sum(
        counts[state] for state in ("NON_ROOT_CONTEXT", "ROOT_CONTEXT", "MIXED")
    )
    return {
        "expected": len(methods),
        "observable": observable,
        "observed": observed,
        "context": counts["NON_ROOT_CONTEXT"],
        "root": counts["ROOT_CONTEXT"],
        "mixed": counts["MIXED"],
        "unobserved": counts["UNOBSERVED"],
        "unknown": counts["OUTSIDE_ENTRY_INVENTORY"],
    }


def append_candidate_flows(result, assessment, observations, method_inventory):
    """Expose reviewed catalog candidates beside declared flows using entry evidence only."""
    selected_methods = set(method_inventory)
    for family in assessment["families"]:
        if family["classification"] != "candidate":
            continue
        steps, methods = [], []
        for index, binding in enumerate(family["entryMethodBindings"], start=1):
            method_id = binding["methodId"]
            step_id = f"candidate-entry-{index}"
            evidence = state_for([method_id], observations, selected_methods)
            binding["runtimeEvidence"] = evidence
            steps.append(
                {
                    "id": step_id,
                    "kind": "library-method",
                    "anchor": method_id,
                    "matches": [method_id],
                    "bindingStatus": binding["bindingStatus"],
                    "runtimeEvidence": evidence,
                }
            )
            member = method_id.split("#", 1)[-1]
            methods.append(
                {
                    "methodId": method_id,
                    "displayMethod": method_id,
                    "owner": method_id.split("#", 1)[0].replace("/", "."),
                    "name": member.split("(", 1)[0],
                    "stepIds": [step_id],
                    "bindingStatus": binding["bindingStatus"],
                    "runtimeEvidence": evidence,
                }
            )
        family["observedEntryMethods"] = sum(
            method["runtimeEvidence"]["state"]
            in ("NON_ROOT_CONTEXT", "ROOT_CONTEXT", "MIXED")
            for method in methods
        )
        flow = {
            "id": "candidate." + family["id"],
            "feature": "Candidate functionality",
            "variant": family["name"],
            "outcome": "Representative entry evidence only",
            "status": "candidate-needs-review",
            "catalogClassification": "candidate",
            "candidateRationale": family["rationale"],
            "sourceIds": family["sourceIds"],
            "identification": {"allOf": [], "anyOf": [step["id"] for step in steps],
                               "noneOf": []},
            "completion": {"allOf": [], "anyOf": [], "optional": []},
            "prerequisiteSteps": [],
            "steps": steps,
            "expectedMethods": methods,
            "transitions": [],
            "actions": [],
        }
        flow["tests"] = tests_for_flow(flow, observations, selected_methods)
        matching_ids = {test["id"] for test in flow["tests"]}
        for method in methods:
            method["runtimeEvidence"]["tests"] = {
                test_id: state_for(
                    [method["methodId"]], observations, selected_methods, test_id
                )
                for test_id in sorted(matching_ids)
            }
        flow["coverage"] = coverage_summary(methods)
        flow["runtimeFlowStatus"] = (
            "CANDIDATE_ENTRY_OBSERVED" if flow["tests"] else "CANDIDATE_ENTRY_NOT_OBSERVED"
        )
        result["flows"].append(flow)


def build_method_call_dag(
    root, defined_methods, adjacency, edge_kinds, edge_counts, edge_lines, max_depth=2
):
    """Build a bounded, forward-only projection of a method's declared library calls."""
    if root not in defined_methods:
        return {
            "root": root,
            "maxDepth": max_depth,
            "nodes": [],
            "edges": [],
            "truncated": False,
            "omittedNonForwardEdges": 0,
        }

    depth = {root: 0}
    queue = collections.deque([root])
    while queue:
        source = queue.popleft()
        if depth[source] >= max_depth:
            continue
        for target in sorted(adjacency.get(source, ())):
            if target not in defined_methods or target in depth:
                continue
            depth[target] = depth[source] + 1
            queue.append(target)

    node_ids = set(depth)
    edges = []
    omitted_non_forward = 0
    for source in sorted(node_ids):
        for target in sorted(set(adjacency.get(source, ())) & node_ids):
            if depth[target] <= depth[source]:
                omitted_non_forward += 1
                continue
            edges.append(
                {
                    "from": source,
                    "to": target,
                    "callSites": edge_counts[(source, target)],
                    "kinds": sorted(edge_kinds[(source, target)]),
                    "lines": sorted(line for line in edge_lines[(source, target)] if line >= 0),
                }
            )

    truncated = any(
        target in defined_methods and target not in node_ids
        for source in node_ids
        if depth[source] == max_depth
        for target in adjacency.get(source, ())
    )
    nodes = []
    for method_id in sorted(node_ids, key=lambda item: (depth[item], item)):
        method = defined_methods[method_id]
        nodes.append(
            {
                "id": method_id,
                "owner": method["owner"].replace("/", "."),
                "name": method["name"],
                "descriptor": method["descriptor"],
                "artifact": method["artifact"],
                "firstLine": method.get("firstLine", -1),
                "lastLine": method.get("lastLine", -1),
                "depth": depth[method_id],
            }
        )
    return {
        "root": root,
        "maxDepth": max_depth,
        "nodes": nodes,
        "edges": edges,
        "truncated": truncated,
        "omittedNonForwardEdges": omitted_non_forward,
    }


def methods_for_steps(flow, step_ids):
    wanted = set(step_ids)
    methods = []
    seen = set()
    for method in flow["expectedMethods"]:
        matching_steps = [step for step in method["stepIds"] if step in wanted]
        if not matching_steps or method["methodId"] in seen:
            continue
        seen.add(method["methodId"])
        methods.append(
            {
                "methodId": method["methodId"],
                "owner": method["owner"],
                "name": method["name"],
                "stepId": matching_steps[0],
                "bindingStatus": method["bindingStatus"],
                "currentState": method["runtimeEvidence"]["state"],
            }
        )
    for step in flow["steps"]:
        if step["id"] not in wanted:
            continue
        for method_id in step["matches"]:
            if method_id in seen:
                continue
            seen.add(method_id)
            owner, member = method_id.split("#", 1)
            methods.append(
                {
                    "methodId": method_id,
                    "owner": owner.replace("/", "."),
                    "name": member.split("(", 1)[0],
                    "stepId": step["id"],
                    "bindingStatus": step["bindingStatus"],
                    "currentState": step["runtimeEvidence"]["state"],
                }
            )
    return methods


def build_task_bundle(result, flow, manifest):
    identification = flow.get("identification", {})
    required_ids = identification.get("allOf", [])
    alternative_ids = identification.get("anyOf", [])
    excluded_ids = identification.get("noneOf", [])
    anchor_ids = required_ids + alternative_ids
    step_order = [step["id"] for step in flow["steps"]]
    positions = [step_order.index(step) for step in anchor_ids if step in step_order]
    first = min(positions) if positions else 0
    last = max(positions) if positions else -1
    prerequisite_ids = flow.get("prerequisiteSteps", [
        step for step in step_order[:first] if step not in excluded_ids
    ])
    completion = flow.get("completion", {"allOf": [
        step for step in step_order[last + 1 :] if step not in excluded_ids
    ]})
    completion_ids = completion.get("allOf", [])
    alternative_completion = methods_for_steps(flow, completion.get("anyOf", []))
    optional_completion = methods_for_steps(flow, completion.get("optional", []))
    required = methods_for_steps(flow, required_ids)
    alternatives = methods_for_steps(flow, alternative_ids)
    exclusions = methods_for_steps(flow, excluded_ids)
    prerequisites = methods_for_steps(flow, prerequisite_ids)
    completion = methods_for_steps(flow, completion_ids)
    task_id = flow["id"] + ".add-flow-test"
    filename = task_id.replace(".", "-") + ".json"
    healthy = bool(result["contextEvidenceReports"]) and all(
        report.get("finalized")
        and not report.get("health", {}).get("errors")
        and not report.get("health", {}).get("droppedObservations", 0)
        for report in result["contextEvidenceReports"]
    )
    acceptance = [
        "Existing test assertions continue to pass.",
        "The regenerated report associates one attributed test window with this flow.",
    ]
    if required:
        acceptance.append(
            "The same test window observes at least one bound method for each required identifying step."
        )
    if alternatives:
        acceptance.append(
            "The same test window observes at least one alternative identifying anchor."
        )
    if exclusions:
        acceptance.append(
            "The identifying test window does not observe any exclusion anchor."
        )
    if completion:
        acceptance.append(
            "The same test window observes at least one bound method for each required completion step."
        )
    if alternative_completion:
        acceptance.append("The same test window observes at least one alternative completion step.")
    acceptance.append(
        "The regenerated report records Context and root entry counts for the expected methods."
    )
    sources = [
        source
        for source in result.get("sources", [])
        if source.get("id") in flow.get("sourceIds", [])
    ]
    exercise = flow.get("exercise")
    if exercise:
        exercise_task = {**exercise, "basis": "curated"}
    else:
        exercise_task = {
            "status": "derived-draft",
            "summary": (
                f"Exercise the {flow['variant']} behavior and verify "
                f"{flow['outcome']}."
            ),
            "steps": [],
            "basis": "hypothesis",
        }
    return {
        "schemaVersion": 1,
        "taskId": task_id,
        "kind": "ADD_FLOW_TEST",
        "status": "proposed",
        "downloadFilename": filename,
        "target": {
            **manifest.get("taskContext", {}),
            "library": result["library"],
            "libraryVersion": result["version"],
            "flowId": flow["id"],
        },
        "objective": (
            f"Add a test that establishes the {flow['variant']} flow and verifies "
            f"its {flow['outcome']} outcome."
        ),
        "exercise": exercise_task,
        "flowContract": {
            "identification": identification,
            "completionPredicate": flow.get("completion"),
            "feature": flow["feature"],
            "variant": flow["variant"],
            "outcome": flow["outcome"],
            "sharedPrerequisites": prerequisites,
            "requiredAnchors": required,
            "alternativeAnchors": alternatives,
            "exclusionAnchors": exclusions,
            "completionCheckpoints": completion,
            "alternativeCompletionCheckpoints": alternative_completion,
            "optionalCompletionCheckpoints": optional_completion,
            "contextContract": flow.get("contextContract"),
            "basis": "curated",
        },
        "currentEvidence": {
            "matchingTests": len(flow["tests"]),
            "coverage": flow["coverage"],
            "collectionHealthy": healthy,
            "testExecution": result.get("testExecution"),
            "run": result.get("evidenceRun"),
            "basis": "observed",
        },
        "staticReachability": {
            "transitions": flow["transitions"],
            "basis": "static",
            "warning": "Static paths do not establish runtime call order or causal lineage.",
        },
        "implementationContext": manifest.get("taskContext", {}),
        "acceptanceCriteria": acceptance,
        "constraints": [
            "Do not add or change instrumentation solely because this flow currently has no matching test.",
            "Do not infer expected Context state from static reachability.",
            "Report root observations as evidence instead of silently correcting them.",
            "Keep the existing test suite assertions valid.",
        ],
        "claims": [
            {
                "basis": "observed",
                "claim": "No attributed test window currently satisfies the flow predicate.",
            },
            {
                "basis": "curated",
                "claim": "The flow contract defines the intended scenario; the exercise has its own provenance.",
            },
            {
                "basis": "static",
                "claim": "Transition paths come from declared bytecode calls when available.",
            },
        ],
        "sources": sources,
    }


def build_investigation_task(result, flow, manifest, kind, methods):
    task = build_task_bundle(result, flow, manifest)
    suffixes = {
        "EXPAND_ENTRY_INVENTORY": "expand-entry-inventory",
        "INVESTIGATE_REACHABILITY": "investigate-reachability",
        "INVESTIGATE_CONTEXT_GAP": "investigate-context-gap",
    }
    objectives = {
        "EXPAND_ENTRY_INVENTORY": "Expand exact entry observation for expected methods outside the current inventory.",
        "INVESTIGATE_REACHABILITY": "Determine why expected flow methods were not observed in the matching tests.",
        "INVESTIGATE_CONTEXT_GAP": "Establish whether root or mixed Context observations are expected for this flow.",
    }
    suffix = suffixes[kind]
    task["taskId"] = flow["id"] + "." + suffix
    task["kind"] = kind
    task["downloadFilename"] = task["taskId"].replace(".", "-") + ".json"
    task["objective"] = objectives[kind]
    task["investigationTargets"] = [
        {
            "methodId": method["methodId"],
            "state": method["runtimeEvidence"]["state"],
            "contextEntries": method["runtimeEvidence"].get("contextEntries", 0),
            "rootEntries": method["runtimeEvidence"].get("rootEntries", 0),
        }
        for method in methods
    ]
    task["acceptanceCriteria"] = [
        "Existing behavioral and trace assertions continue to pass.",
        "Fresh collection is healthy and attributes the matching scenarios with explicit confidence.",
    ]
    if kind == "EXPAND_ENTRY_INVENTORY":
        task["acceptanceCriteria"].append(
            "Every target is either classified by exact entry observations or documented as unobservable."
        )
    elif kind == "INVESTIGATE_REACHABILITY":
        task["acceptanceCriteria"].append(
            "The experiment establishes whether each target belongs to the scenario's actual branch."
        )
    else:
        task["acceptanceCriteria"].extend(
            [
                "A focused assertion states the expected Context behavior at the relevant lifecycle point.",
                "Any proposed instrumentation change is supported by a same-scenario failing baseline.",
            ]
        )
    task["constraints"] = [
        "Do not infer runtime order or causal lineage from the static graph.",
        "Do not treat root Context as a defect without an explicit scenario expectation.",
        "Collect and compare fresh evidence after changing tests, inventory, or instrumentation.",
    ]
    task["claims"] = [
        {
            "basis": "observed",
            "claim": "The listed method states were recorded for tests matching this flow.",
        },
        {
            "basis": "curated",
            "claim": "The versioned flow contract defines why these methods are expected.",
        },
    ]
    return task


def task_markdown(task):
    contract = task["flowContract"]
    lines = [
        f"# {task['objective']}",
        "",
        f"Task kind: `{task['kind']}` · Status: `{task['status']}`",
        "",
        "## Behavior to exercise",
        "",
        task["exercise"]["summary"],
        "",
    ]
    for index, step in enumerate(task["exercise"].get("steps", []), 1):
        lines.append(f"{index}. {step}")
    lines.extend(["", "## Required identifying anchors", ""])
    for method in contract["requiredAnchors"]:
        lines.append(f"- `{method['methodId']}`")
    if contract["alternativeAnchors"]:
        lines.extend(["", "## Alternative anchors", ""])
        for method in contract["alternativeAnchors"]:
            lines.append(f"- `{method['methodId']}`")
    if contract["exclusionAnchors"]:
        lines.extend(["", "## Exclusion anchors", ""])
        for method in contract["exclusionAnchors"]:
            lines.append(f"- `{method['methodId']}`")
    lines.extend(["", "## Completion checkpoints", ""])
    for method in contract["completionCheckpoints"]:
        lines.append(f"- `{method['methodId']}`")
    for key, label in (("alternativeCompletionCheckpoints", "Alternative completion (at least one step)"),
                       ("optionalCompletionCheckpoints", "Optional completion (not required)")):
        if contract.get(key):
            lines.extend(["", "## " + label, ""])
            for method in contract[key]:
                lines.append(f"- `{method['methodId']}`")
    lines.extend(["", "## Acceptance criteria", ""])
    for criterion in task["acceptanceCriteria"]:
        lines.append(f"- {criterion}")
    lines.extend(["", "## Constraints", ""])
    for constraint in task["constraints"]:
        lines.append(f"- {constraint}")
    context = task["implementationContext"]
    lines.extend(
        [
            "",
            "## Commands",
            "",
            f"- Test: `{context.get('testCommand', 'not specified')}`",
            f"- Collect fresh evidence: `{context.get('collectionCommand', 'not specified')}`",
            f"- Regenerate report: `{context.get('reportCommand', 'not specified')}`",
            "",
            "Static reachability is investigation context, not an observed runtime call path.",
        ]
    )
    return "\n".join(lines) + "\n"


def profile_for(method_ids, profile):
    evidence = {
        signal + "StackOccurrences": sum(profile[method][signal] for method in method_ids)
        for signal in ("cpu", "allocation", "blocking")
    }
    evidence["sampled"] = any(evidence.values())
    return evidence


def analyze(
    raw,
    manifest,
    observations,
    reports,
    method_inventory,
    profile,
    profile_metadata,
    test_execution,
):
    defined = {method["id"] for method in raw["definedMethods"]}
    external_methods = {
        method for method in raw.get("externalMethods", []) if not method.startswith("invokedynamic/")
    }
    all_nodes = defined | external_methods
    adjacency = collections.defaultdict(set)
    edge_kinds = collections.defaultdict(set)
    edge_counts = collections.Counter()
    edge_lines = collections.defaultdict(set)
    for edge in raw["calls"]:
        adjacency[edge["from"]].add(edge["to"])
        edge_kinds[(edge["from"], edge["to"])].add(edge["opcode"])
        edge_counts[(edge["from"], edge["to"])] += 1
        edge_lines[(edge["from"], edge["to"])].add(edge.get("line", -1))
    simplified_defined = {
        method["id"]: method
        for method in raw["definedMethods"]
        if not method.get("noiseReason")
    }

    result = {
        "schemaVersion": 2,
        "library": manifest["library"],
        "version": manifest["version"],
        "sources": manifest.get("sources", []),
        "coverageReports": [report["path"] for report in reports],
        "contextEvidenceReports": reports,
        "entryInventoryMethods": len(method_inventory),
        "methodInventory": sorted(method_inventory.values(), key=lambda method: method["id"]),
        "testExecution": test_execution,
        "runtimeEvidenceScope": (
            "flow methods use only test windows matched by the flow's declared observation predicate"
        ),
        "flows": [],
    }
    result["flowCatalog"] = manifest.get(
        "flowCatalog", {"status": "unspecified", "scope": "See knowledge manifest"}
    )
    if profile_metadata:
        result["profileAnalysis"] = profile_metadata
    for flow in manifest["flows"]:
        flow_result = {
            key: flow[key]
            for key in ("id", "feature", "variant", "outcome", "status", "sourceIds")
            if key in flow
        }
        flow_result["expectations"] = flow.get("expectations", [])
        if flow.get("contextContract"):
            flow_result["contextContract"] = flow["contextContract"]
        for field in ("completion", "prerequisiteSteps", "stagePresentation"):
            if field in flow:
                flow_result[field] = flow[field]
        if flow.get("exercise"):
            flow_result["exercise"] = flow["exercise"]
        flow_result["identification"] = flow.get(
            "identification", {"allOf": flow.get("testEvidenceAnchors", [])}
        )
        flow_result["runtimeFlowStatus"] = "NOT_ESTABLISHED"
        flow_result["steps"] = []
        for step in flow["steps"]:
            matches = sorted(node for node in all_nodes if step["anchor"] in node)
            step_result = dict(step)
            step_result["matches"] = matches
            step_result["bindingStatus"] = (
                "RESOLVED" if len(matches) == 1 else "UNRESOLVED" if not matches else "AMBIGUOUS"
            )
            step_result["runtimeEvidence"] = state_for(
                matches, observations, set(method_inventory)
            )
            if profile_metadata:
                step_result["profileEvidence"] = profile_for(matches, profile)
            flow_result["steps"].append(step_result)

        expected_step_ids = set(
            flow.get(
                "expectedMethodSteps",
                [
                    step["id"]
                    for step in flow_result["steps"]
                    if step.get("kind") == "library-method"
                    and step["id"]
                    not in flow_result.get("identification", {}).get("noneOf", [])
                ],
            )
        )
        expected_methods = []
        seen_methods = set()
        for step in flow_result["steps"]:
            if step["id"] not in expected_step_ids:
                continue
            if not step["matches"]:
                expected_methods.append(
                    {
                        "methodId": step["anchor"],
                        "displayMethod": step["anchor"],
                        "owner": step["anchor"].split("#", 1)[0].replace("/", "."),
                        "name": step["anchor"].split("#", 1)[-1].split("(", 1)[0],
                        "stepIds": [step["id"]],
                        "bindingStatus": "UNRESOLVED",
                        "runtimeEvidence": state_for([], observations, set(method_inventory)),
                    }
                )
                continue
            for method_id in step["matches"]:
                if method_id in seen_methods:
                    next(
                        method for method in expected_methods if method["methodId"] == method_id
                    )["stepIds"].append(step["id"])
                    continue
                seen_methods.add(method_id)
                member = method_id.split("#", 1)[-1]
                inventory_method = method_inventory.get(method_id, {})
                expected_methods.append(
                    {
                        "methodId": method_id,
                        "displayMethod": inventory_method.get("displayMethod", method_id),
                        "owner": inventory_method.get(
                            "owner", method_id.split("#", 1)[0].replace("/", ".")
                        ),
                        "name": inventory_method.get("name", member.split("(", 1)[0]),
                        "stepIds": [step["id"]],
                        "bindingStatus": step["bindingStatus"],
                        "runtimeEvidence": state_for(
                            [method_id], observations, set(method_inventory)
                        ),
                    }
                )
        flow_result["expectedMethods"] = expected_methods
        flow_result["tests"] = tests_for_flow(
            flow_result, observations, set(method_inventory)
        )
        flow_result["runtimeFlowStatus"] = (
            "MATCHING_TESTS_OBSERVED" if flow_result["tests"] else "NO_MATCHING_TEST"
        )
        matching_test_ids = {test["id"] for test in flow_result["tests"]}
        for method in expected_methods:
            method["runtimeEvidence"] = {
                **state_for(
                    [method["methodId"]], observations, set(method_inventory)
                ),
                "tests": {
                    test_id: state_for(
                        [method["methodId"]], observations, set(method_inventory), test_id
                    )
                    for test_id in sorted(matching_test_ids)
                },
            }
            if matching_test_ids:
                root = sum(
                    evidence["rootEntries"]
                    for evidence in method["runtimeEvidence"]["tests"].values()
                )
                context = sum(
                    evidence["contextEntries"]
                    for evidence in method["runtimeEvidence"]["tests"].values()
                )
                method["runtimeEvidence"]["rootEntries"] = root
                method["runtimeEvidence"]["contextEntries"] = context
                method["runtimeEvidence"]["state"] = (
                    "MIXED"
                    if root and context
                    else "NON_ROOT_CONTEXT"
                    if context
                    else "ROOT_CONTEXT"
                    if root
                    else "UNOBSERVED"
                    if method["methodId"] in method_inventory
                    else "OUTSIDE_ENTRY_INVENTORY"
                )
                combined_threads = {}
                for evidence in method["runtimeEvidence"]["tests"].values():
                    for thread in evidence["threads"]:
                        key = (thread["id"], thread["name"])
                        combined = combined_threads.setdefault(
                            key,
                            {
                                "id": thread["id"],
                                "name": thread["name"],
                                "rootEntries": 0,
                                "contextEntries": 0,
                            },
                        )
                        combined["rootEntries"] += thread["rootEntries"]
                        combined["contextEntries"] += thread["contextEntries"]
                method["runtimeEvidence"]["threads"] = sorted(
                    combined_threads.values(),
                    key=lambda thread: (thread["name"], thread["id"] or -1),
                )
            else:
                method["runtimeEvidence"]["rootEntries"] = 0
                method["runtimeEvidence"]["contextEntries"] = 0
                method["runtimeEvidence"]["state"] = (
                    "UNOBSERVED"
                    if method["methodId"] in method_inventory
                    else "OUTSIDE_ENTRY_INVENTORY"
                )
                method["runtimeEvidence"]["threads"] = []
        flow_result["coverage"] = coverage_summary(expected_methods)

        flow_result["transitions"] = []
        for before, after in zip(flow_result["steps"], flow_result["steps"][1:]):
            path = shortest_path(before["matches"], after["matches"], adjacency)
            transition = {
                "from": before["id"],
                "to": after["id"],
                "status": "STATIC_PATH" if path else "SEMANTIC_EDGE_REQUIRED",
                "path": path,
            }
            if len(path) == 2:
                transition["edgeKinds"] = sorted(edge_kinds[(path[0], path[1])])
            flow_result["transitions"].append(transition)
        result["flows"].append(flow_result)
    expected_roots = sorted(
        {
            method["methodId"]
            for flow in result["flows"]
            for method in flow["expectedMethods"]
        }
    )
    result["methodCallDags"] = {
        root: build_method_call_dag(
            root,
            simplified_defined,
            adjacency,
            edge_kinds,
            edge_counts,
            edge_lines,
        )
        for root in expected_roots
    }
    result["actions"] = []
    result["taskBundles"] = {}
    for flow in result["flows"]:
        flow["actions"] = []
        if not flow["tests"]:
            task = build_task_bundle(result, flow, manifest)
            label = "Add flow test"
        else:
            states = collections.defaultdict(list)
            for method in flow["expectedMethods"]:
                states[method["runtimeEvidence"]["state"]].append(method)
            if states["OUTSIDE_ENTRY_INVENTORY"]:
                kind, targets, label = (
                    "EXPAND_ENTRY_INVENTORY",
                    states["OUTSIDE_ENTRY_INVENTORY"],
                    "Expand observation",
                )
            elif states["UNOBSERVED"]:
                kind, targets, label = (
                    "INVESTIGATE_REACHABILITY",
                    states["UNOBSERVED"],
                    "Investigate reachability",
                )
            elif states["ROOT_CONTEXT"] or states["MIXED"]:
                kind, targets, label = (
                    "INVESTIGATE_CONTEXT_GAP",
                    states["ROOT_CONTEXT"] + states["MIXED"],
                    "Investigate Context",
                )
            else:
                continue
            task = build_investigation_task(result, flow, manifest, kind, targets)
        action = {
            "id": task["taskId"],
            "kind": task["kind"],
            "label": label,
            "flowId": flow["id"],
            "downloadFilename": task["downloadFilename"],
        }
        flow["actions"].append(action)
        result["actions"].append(action)
        result["taskBundles"][task["taskId"]] = task
    return result


def markdown(result):
    lines = [
        f"# {result['library']} {result['version']} flow bindings",
        "",
        "Documentation supplies the ordered semantic steps. Static paths show bytecode reachability;",
        "a missing static path identifies a callback, virtual dispatch, reflection, or other semantic",
        "edge to model. Runtime evidence is limited to the supplied context-coverage reports.",
        "Method observations do not establish that all steps belonged to the same scenario.",
        "",
    ]
    profiled = bool(result.get("profileAnalysis"))
    if profiled:
        lines.insert(
            6,
            "Profile counts are stack occurrences for the whole test task; zero means not sampled, not unexecuted.",
        )
    if result.get("testExecution"):
        tests = result["testExecution"]
        lines.extend(
            [
                f"Test task: {tests['tests']} reported, {tests['passed']} passed, "
                f"{tests['skipped']} skipped, {tests['failures']} failed, "
                f"{tests['errors']} errors.",
                "",
            ]
        )
    for flow in result["flows"]:
        lines.extend(
            [
                f"## {flow['id']}",
                "",
                f"Feature: **{flow['feature']}** · Variant: **{flow['variant']}** · Outcome: **{flow['outcome']}**",
                "",
                (
                    "Tests identified through distinctive anchors: "
                    + ", ".join(f"`{test['name']}`" for test in flow["tests"])
                    if flow["tests"]
                    else "Tests identified through distinctive anchors: **none**"
                ),
                "",
                (
                    "| Step | Binding | Runtime state | Context entries | Root entries | CPU | Alloc | Block |"
                    if profiled
                    else "| Step | Binding | Runtime state | Context entries | Root entries |"
                ),
                (
                    "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |"
                    if profiled
                    else "| --- | --- | --- | ---: | ---: |"
                ),
            ]
        )
        for step in flow["steps"]:
            evidence = step["runtimeEvidence"]
            row = (
                f"| `{step['id']}` | {step['bindingStatus']} ({len(step['matches'])}) | "
                f"{evidence['state']} | {evidence['contextEntries']} | {evidence['rootEntries']}"
            )
            if profiled:
                profile = step["profileEvidence"]
                row += (
                    f" | {profile['cpuStackOccurrences']} | "
                    f"{profile['allocationStackOccurrences']} | "
                    f"{profile['blockingStackOccurrences']}"
                )
            lines.append(row + " |")
        lines.extend(
            [
                "",
                "| Transition | Static result | Path length |",
                "| --- | --- | ---: |",
            ]
        )
        for transition in flow["transitions"]:
            length = max(0, len(transition["path"]) - 1) if transition["path"] else "—"
            lines.append(
                f"| `{transition['from']}` → `{transition['to']}` | {transition['status']} | {length} |"
            )
        lines.append("")
    return "\n".join(lines)


def report_template():
    return (Path(__file__).resolve().parents[2] / "instrumentation-coverage/viewer/index.html").read_text()


def html(result):
    template = report_template()
    encoded = base64.b64encode(json.dumps(result).encode()).decode()
    return template.replace("__JOINED_DATA__", encoded, 1)


def main():
    args = parse_args()
    raw = json.loads(args.raw_graph.read_text())
    manifest = json.loads(args.flow_manifest.read_text())
    observations, reports, method_inventory = load_observations(args.coverage_root)
    profile, profile_metadata = load_profile(args.profile_analysis)
    test_execution = load_test_results(args.test_results_root)
    result = analyze(
        raw,
        manifest,
        observations,
        reports,
        method_inventory,
        profile,
        profile_metadata,
        test_execution,
    )
    if args.catalog_assessment:
        validation = json.loads(args.catalog_assessment.read_text())
        if validation.get("catalogAssessment"):
            assessment = validation["catalogAssessment"]
            append_candidate_flows(result, assessment, observations, method_inventory)
            candidates = [
                family for family in assessment["families"]
                if family["classification"] == "candidate"
            ]
            assessment["summary"]["candidateWithObservedEntry"] = sum(
                bool(family["observedEntryMethods"]) for family in candidates
            )
            assessment["summary"]["candidateWithoutObservedEntry"] = sum(
                not family["observedEntryMethods"] for family in candidates
            )
            result["catalogAssessment"] = assessment
    if args.run_manifest:
        run = json.loads(args.run_manifest.read_text())
        result["evidenceRun"] = {key: run[key] for key in ("runId", "module", "testTask", "attribution")}
        for flow in result["flows"]:
            if not flow["tests"]:
                task = build_task_bundle(result, flow, manifest)
                result["taskBundles"][task["taskId"]] = task
    args.output_directory.mkdir(parents=True, exist_ok=True)
    report_json = json.dumps(result, indent=2) + "\n"
    (args.output_directory / f"{args.output_prefix}.json").write_text(report_json)
    (args.output_directory / "report.json").write_text(report_json)
    (args.output_directory / f"{args.output_prefix}.md").write_text(markdown(result) + "\n")
    (args.output_directory / f"{args.output_prefix}.html").write_text(html(result))
    tasks_directory = args.output_directory / "tasks"
    tasks_directory.mkdir(exist_ok=True)
    for task in result["taskBundles"].values():
        filename = task["downloadFilename"]
        (tasks_directory / filename).write_text(json.dumps(task, indent=2) + "\n")
        (tasks_directory / filename.replace(".json", ".md")).write_text(
            task_markdown(task)
        )
    source_schemas = Path(__file__).parent.parent / "schemas"
    output_schemas = args.output_directory / "schemas"
    output_schemas.mkdir(exist_ok=True)
    for schema in source_schemas.glob("*.schema.json"):
        (output_schemas / schema.name).write_text(schema.read_text())
    print(f"Flow analysis written to {args.output_directory.resolve()}")


if __name__ == "__main__":
    main()
