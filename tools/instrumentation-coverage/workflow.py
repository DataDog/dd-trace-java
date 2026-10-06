#!/usr/bin/env python3
"""Deterministic instrumentation-coverage lifecycle and report workflow (stdlib only)."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

from stage_contract import validate_stages

TOOL = Path(__file__).resolve().parent
ROOT = TOOL.parents[1]
ENGINE = TOOL  # Compatibility for helper scripts importing the earlier name.
KNOWLEDGE_FILES = (
    "library.json", "observation.json", "flows.json", "catalog.json", "evidence.json",
    "knowledge-review.md", "catalog-inputs.json", "catalog-reconciliation.json")


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def execute(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def validate_knowledge(library, flows, observation):
    if (library.get("schemaVersion") != 1 or observation.get("schemaVersion") != 1
            or flows.get("schemaVersion") != 2):
        raise ValueError("Unsupported knowledge schema version")
    if (flows["library"], flows["version"]) != (
            library["library"], library["reviewedVersion"]):
        raise ValueError("Flow catalog and library identity differ")
    if not observation.get("classes") or not observation.get("requiredTransformed"):
        raise ValueError("Observation needs explicit classes and required transformed types")
    ids = [flow["id"] for flow in flows["flows"]]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate flow IDs")
    labels = [tuple(flow.get(key, "").strip() for key in ("feature", "variant", "outcome"))
              for flow in flows["flows"]]
    if any(not all(label) for label in labels):
        raise ValueError("Every flow needs feature, variant, and outcome display labels")
    duplicates = sorted({label for label in labels if labels.count(label) > 1})
    if duplicates:
        raise ValueError("Duplicate human-facing flow labels: " + repr(duplicates))
    source_ids = {source["id"] for source in flows.get("sources", [])}
    for flow in flows["flows"]:
        validate_stages(flow, source_ids)
        steps = [step["id"] for step in flow["steps"]]
        if len(steps) != len(set(steps)):
            raise ValueError("Duplicate step IDs: " + flow["id"])
        predicate = flow["identification"]
        if not (predicate.get("allOf") or predicate.get("anyOf")):
            raise ValueError("Flow needs identifying anchors: " + flow["id"])
        for key in ("allOf", "anyOf", "noneOf"):
            if set(predicate.get(key, [])) - set(steps):
                raise ValueError("Predicate refers to absent steps: " + flow["id"])
        completion = flow.get("completion", {})
        roles = [step for key in ("allOf", "anyOf", "optional")
                 for step in completion.get(key, [])]
        if set(roles) - set(steps) or len(roles) != len(set(roles)):
            raise ValueError("Invalid or overlapping completion roles: " + flow["id"])
        if set(flow.get("prerequisiteSteps", [])) - set(steps):
            raise ValueError("Unknown prerequisite steps: " + flow["id"])
        contract = flow.get("contextContract")
        if contract:
            if contract.get("status") not in ("draft", "needs-review", "reviewed"):
                raise ValueError("Invalid Context contract status: " + flow["id"])
            if not contract.get("precondition", "").strip():
                raise ValueError("Context contract needs a precondition: " + flow["id"])
            expectations = contract.get("expectations", [])
            expectation_ids = [item.get("id") for item in expectations]
            if not expectations or any(not item for item in expectation_ids):
                raise ValueError("Context contract needs identified expectations: " + flow["id"])
            if len(expectation_ids) != len(set(expectation_ids)):
                raise ValueError("Duplicate Context expectation IDs: " + flow["id"])
            for expectation in expectations:
                if expectation.get("expected") not in (
                    "PRESENT", "ABSENT", "EITHER", "UNRESOLVED"
                ):
                    raise ValueError("Invalid Context expectation: " + flow["id"])
                if not expectation.get("stepIds") or set(expectation["stepIds"]) - set(steps):
                    raise ValueError("Context expectation refers to absent steps: " + flow["id"])
                if (
                    not expectation.get("sourceIds")
                    or set(expectation["sourceIds"]) - source_ids
                    or not expectation.get("rationale", "").strip()
                ):
                    raise ValueError(
                        "Context expectation needs rationale and known sources: " + flow["id"]
                    )


def module_path(value):
    module = (ROOT / value).resolve()
    module.relative_to(ROOT)
    if not module.is_dir():
        raise ValueError("Instrumentation module does not exist: " + str(module))
    return module


def project_path(module):
    return ":" + str(module.relative_to(ROOT)).replace("/", ":")


def create_run(module, required_files):
    knowledge = module / "coverage"
    missing = [name for name in required_files if not (knowledge / name).is_file()]
    if missing:
        raise ValueError("Coverage knowledge is incomplete: " + ", ".join(missing))
    run = module / "build/instrumentation-coverage" / uuid.uuid4().hex
    run.mkdir(parents=True)
    for filename in KNOWLEDGE_FILES:
        source = knowledge / filename
        if source.is_file():
            destination = run / "knowledge" / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
    return run


def base_manifest(run, module, library, observation):
    test_tasks = library.get("testTasks", [library.get("testTask", "test")])
    return {"schemaVersion": 1, "runId": run.name,
            "module": str(module.relative_to(ROOT)),
            "testTask": test_tasks[0], "testTasks": test_tasks,
            "status": "preparing", "attribution": observation["attribution"]}


def gradle_bridge(run, module, test_jvm):
    project = project_path(module)
    return project, [ROOT / "gradlew", "-I", TOOL / "collection.init.gradle",
                     "-PcoverageModule=" + project, "-PcoverageRun=" + str(run),
                     "-PtestJvm=" + test_jvm, "--console=plain", "--no-configuration-cache"]


def export_artifacts(run, module, test_jvm):
    project, gradle = gradle_bridge(run, module, test_jvm)
    execute(*gradle, project + ":exportCoverageArtifacts")


def generate_graph(run):
    library, resolved = read(run / "knowledge/library.json"), read(run / "resolved.json")
    expected = library["library"] + ":" + library["reviewedVersion"]
    if expected not in [artifact["coordinate"] for artifact in resolved["artifacts"]]:
        raise ValueError(f"Knowledge needs review: expected {expected}; see resolved.json")
    for artifact in resolved["artifacts"]:
        if digest(Path(artifact["path"])) != artifact["sha256"]:
            raise ValueError("Resolved artifact changed: " + artifact["coordinate"])
    graph = run / "graph"
    analyzer = TOOL / "src/main/java/datadog/trace/agent/test/coverage/LibraryGraphAnalyzer.java"
    identity = {"artifacts": resolved["artifacts"], "analyzer": digest(analyzer),
                "build": digest(TOOL / "build.gradle"),
                "dependencies": digest(ROOT / "gradle/libs.versions.toml")}
    key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
    cache = TOOL / "build/graphs" / key
    if not (cache / "complete.json").exists():
        execute(ROOT / "gradlew", "-p", TOOL, "analyzeResolvedLibrary",
                "-PcoverageManifest=" + str(run / "resolved.json"),
                "-PcoverageGraphOutput=" + str(graph), "--console=plain")
        cache.parent.mkdir(parents=True, exist_ok=True)
        if not cache.exists():
            temporary = cache.with_name(key + "." + run.name)
            shutil.copytree(graph, temporary)
            write(temporary / "complete.json", identity)
            try:
                temporary.rename(cache)
            except FileExistsError:
                shutil.rmtree(temporary)
    else:
        shutil.copytree(cache, graph)
    write(run / "graph-identity.json", {"key": key, **identity})


def bind(run):
    (run / "binding").mkdir(parents=True, exist_ok=True)
    execute(sys.executable, TOOL / "validate-knowledge.py", "--graph",
            run / "graph/raw-graph.json", "--knowledge", run / "knowledge", "--output",
            run / "binding/knowledge-validation.json")
    validation = read(run / "binding/knowledge-validation.json")
    for warning in validation.get("warnings", []):
        print("Knowledge warning:", warning, flush=True)
    execute(sys.executable, TOOL / "scripts/join.py", run / "graph/raw-graph.json",
            run / "knowledge/flows.json", run / "binding", "--catalog-assessment",
            run / "binding/knowledge-validation.json")
    binding = read(run / "binding/flow-analysis.json")
    unresolved = [(flow["id"], step["id"]) for flow in binding["flows"]
                  for step in flow["steps"] if not step["matches"]]
    if unresolved:
        raise ValueError("Unresolved flow bindings require review: " + repr(unresolved))


def seal_evidence(run, manifest):
    manifest["evidenceHashes"] = {
        str(path.relative_to(run)): digest(path)
        for area in ("knowledge", "graph", "observations", "test-results")
        for path in sorted((run / area).rglob("*")) if path.is_file()}
    for name in ("resolved.json", "graph-identity.json", "collection-scope.json"):
        if name == "collection-scope.json" and not (run / name).is_file():
            continue
        manifest["evidenceHashes"][name] = digest(run / name)


def validate_observation_reports(run, required_transformed):
    reports = list((run / "observations").rglob("report.json"))
    if not reports:
        raise ValueError("No instrumentation observation reports were produced")
    transformed = set()
    for path in reports:
        value = read(path)
        health = value.get("health", {})
        execution = value.get("execution", {})
        if execution.get("runId") != run.name:
            raise ValueError("Observation belongs to another run: " + str(path))
        if (not value.get("finalized") or health.get("errors")
                or health.get("droppedObservations")):
            raise ValueError("Incomplete collection: " + str(path))
        transformed.update(execution.get("productionTransformedClasses", []))
    missing = sorted(set(required_transformed) - transformed)
    if missing:
        raise ValueError("Production transformer did not transform required types: "
                         + ", ".join(missing))
    return reports


def report(run):
    manifest = read(run / "manifest.json")
    if manifest["status"] != "collected":
        raise ValueError("Report requires a successfully collected run")
    if not manifest.get("evidenceHashes"):
        raise ValueError("Run has no evidence inventory")
    actual = {str(path.relative_to(run))
              for area in ("knowledge", "graph", "observations", "test-results")
              for path in (run / area).rglob("*") if path.is_file()}
    actual.update(("resolved.json", "graph-identity.json"))
    if (run / "collection-scope.json").is_file():
        actual.add("collection-scope.json")
    if actual != set(manifest["evidenceHashes"]):
        raise ValueError("Run evidence file inventory changed")
    for relative, expected in manifest["evidenceHashes"].items():
        if digest(run / relative) != expected:
            raise ValueError("Run evidence changed: " + relative)
    execute(sys.executable, TOOL / "scripts/join.py", run / "graph/raw-graph.json",
            run / "knowledge/flows.json", run / "report", "--coverage-root",
            run / "observations", "--test-results-root", run / "test-results",
            "--output-prefix", "joined-report", "--run-manifest", run / "manifest.json",
            "--catalog-assessment", run / "binding/knowledge-validation.json")
    execute(sys.executable, TOOL / "pharos.py", "render", "--report",
            run / "report/report.json", "--output", run / "pharos", "--run-directory", run)
    print("Viewer:", TOOL / "viewer/index.html")
    print("Report data:", run / "report/report.json")


def compare_reports(baseline_path, candidate_path):
    baseline, candidate = read(baseline_path), read(candidate_path)
    identity = (baseline.get("library"), baseline.get("version"))
    if identity != (candidate.get("library"), candidate.get("version")):
        raise ValueError("Cannot compare reports for different library identities")
    before = {flow["id"]: flow for flow in baseline.get("flows", [])}
    after = {flow["id"]: flow for flow in candidate.get("flows", [])}
    changes = []
    for flow_id in sorted(set(before) | set(after)):
        left, right = before.get(flow_id), after.get(flow_id)
        left_value = {"matchingTests": len(left.get("tests", [])) if left else 0,
                      "coverage": left.get("coverage", {}) if left else {}}
        right_value = {"matchingTests": len(right.get("tests", [])) if right else 0,
                       "coverage": right.get("coverage", {}) if right else {}}
        if left_value != right_value:
            changes.append({"flowId": flow_id, "before": left_value, "after": right_value})
    common = set(before) & set(after)
    return {"schemaVersion": 1, "library": identity[0], "version": identity[1],
            "baseline": str(baseline_path), "candidate": str(candidate_path),
            "changedFlows": changes,
            "unchangedFlows": len(common - {change["flowId"] for change in changes})}


def initialize(args):
    module = module_path(args.module)
    coverage = module / "coverage"
    destinations = [coverage / name for name in
                    ("library.json", "observation.json", "flows.json", "catalog.json")]
    if not args.force and any(path.exists() for path in destinations):
        raise ValueError("Coverage files already exist; --force replaces generated scaffolding")
    if not args.jdk_module and not args.artifact_group:
        raise ValueError("Resolved-library coverage needs at least one --artifact-group")
    library = {"schemaVersion": 1, "library": args.library,
               "reviewedVersion": args.version,
               "testTasks": [args.test_task, *args.additional_test_task]}
    if args.jdk_module:
        library["graphSource"] = {"kind": "jdk-module", "module": args.jdk_module}
    else:
        library["artifactGroups"] = args.artifact_group
        library["runtimeConfiguration"] = args.runtime_configuration
    write(coverage / "library.json", library)
    write(coverage / "observation.json", {"schemaVersion": 1, "adapter": args.adapter,
          "attribution": "serialized-test-window", "classes": args.observe_class,
          "requiredTransformed": args.required_transformed})
    write(coverage / "flows.json", {"schemaVersion": 2, "library": args.library,
          "version": args.version, "flowCatalog": {"status": "draft-empty",
          "scope": "To be authored from version-matched documentation, source, and graph evidence.",
          "exclusions": []}, "sources": [], "flows": []})
    write(coverage / "catalog.json", {"schemaVersion": 1, "library": args.library,
          "version": args.version, "status": "draft-empty",
          "scope": "Functionality families reviewed for inclusion in the flow catalog.",
          "families": []})
    print("Initialized:", coverage)


def collection_scope(patterns=(), reason=None, authorization=None):
    if patterns and (not reason or not reason.strip() or not authorization or not authorization.strip()):
        raise ValueError("Exclusions require a reason and explicit user authorization record")
    if not patterns and (reason or authorization):
        raise ValueError("Exclusion metadata requires at least one --exclude-test")
    if any(not pattern.strip() for pattern in patterns):
        raise ValueError("Exclusion patterns must be nonempty")
    return {"schemaVersion": 1, "excludedTests": [
        {"pattern": pattern, "reason": reason, "authorization": authorization}
        for pattern in dict.fromkeys(patterns)]}


def prepare(command, module, test_jvm, exclusions=(), exclusion_reason=None, exclusion_authorization=None):
    scope = collection_scope(exclusions, exclusion_reason, exclusion_authorization)
    required = ("library.json", "observation.json")
    if command in ("validate-knowledge", "knowledge", "run"):
        required += ("flows.json",)
    run = create_run(module, required)
    if command == "run":
        write(run / "collection-scope.json", scope)
    library = read(run / "knowledge/library.json")
    observation = read(run / "knowledge/observation.json")
    if observation.get("adapter") not in ("spock", "junit"):
        raise ValueError("Supported instrumentation adapters are spock and junit")
    if command in ("validate-knowledge", "knowledge", "run"):
        validate_knowledge(library, read(run / "knowledge/flows.json"), observation)
    manifest = base_manifest(run, module, library, observation)
    write(run / "manifest.json", manifest)
    print("Run directory:", run, flush=True)
    try:
        export_artifacts(run, module, test_jvm)
        generate_graph(run)
        if command == "graph":
            manifest["status"] = "graph-ready"
            print("Graph:", run / "graph/raw-graph.json")
        else:
            bind(run)
            if command in ("validate-knowledge", "knowledge"):
                manifest["status"] = "knowledge-ready"
                print("Knowledge validation:", run / "binding/knowledge-validation.json")
            else:
                execute(ROOT / "gradlew", "-p", TOOL, "observerJar", "bootstrapBridgeJar",
                        "--console=plain")
                project, gradle = gradle_bridge(run, module, test_jvm)
                tasks = library.get("testTasks", [library.get("testTask", "test")])
                execute(*gradle, *[project + ":" + task for task in tasks])
                validate_observation_reports(run, observation["requiredTransformed"])
                manifest["status"] = "collected"
                seal_evidence(run, manifest)
        write(run / "manifest.json", manifest)
        if command == "run":
            report(run)
    except Exception:
        manifest["status"] = "failed"
        write(run / "manifest.json", manifest)
        raise


def make_parser():
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="Create deterministic module coverage scaffolding")
    init.add_argument("--module", required=True)
    init.add_argument("--library", required=True, help="group:artifact")
    init.add_argument("--version", required=True)
    init.add_argument("--adapter", required=True, choices=("spock", "junit"))
    init.add_argument("--artifact-group", action="append", default=[])
    init.add_argument("--jdk-module", help="Analyze this module from the selected test JDK")
    init.add_argument("--test-task", default="test")
    init.add_argument("--additional-test-task", action="append", default=[])
    init.add_argument("--runtime-configuration", default="testRuntimeClasspath")
    init.add_argument("--observe-class", action="append", required=True)
    init.add_argument("--required-transformed", action="append", required=True)
    init.add_argument("--force", action="store_true")
    for name in ("graph", "validate-knowledge", "knowledge", "run"):
        command = commands.add_parser(name)
        command.add_argument("--module", required=True)
        command.add_argument("--test-jvm", default="21")
        if name == "run":
            command.add_argument("--exclude-test", action="append", default=[], help="User-authorized Gradle test filter pattern; repeatable")
            command.add_argument("--exclusion-reason")
            command.add_argument("--exclusion-authorization", help="Record the explicit user approval; never infer approval")
    regenerate = commands.add_parser("report")
    regenerate.add_argument("--run-directory", type=Path, required=True)
    compare = commands.add_parser("compare")
    compare.add_argument("--baseline", type=Path, required=True)
    compare.add_argument("--candidate", type=Path, required=True)
    compare.add_argument("--output", type=Path)
    return result


def main():
    args = make_parser().parse_args()
    if args.command == "init":
        initialize(args)
    elif args.command == "report":
        report(args.run_directory.resolve())
    elif args.command == "compare":
        comparison = compare_reports(args.baseline.resolve(), args.candidate.resolve())
        if args.output:
            write(args.output, comparison)
        else:
            print(json.dumps(comparison, indent=2, sort_keys=True))
    else:
        prepare(args.command, module_path(args.module), args.test_jvm,
                getattr(args, "exclude_test", []), getattr(args, "exclusion_reason", None),
                getattr(args, "exclusion_authorization", None))


if __name__ == "__main__":
    main()
