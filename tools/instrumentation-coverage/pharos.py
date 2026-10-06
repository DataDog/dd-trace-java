#!/usr/bin/env python3
"""Render, inspect, and compare Pharos reports without an LLM or new collection."""

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from stage_contract import meaningful_label, validate_stages
from catalog_navigation import project as project_navigation, validate_report as validate_navigation

TOOL = Path(__file__).resolve().parent
ROOT = TOOL.parents[1]
SUPPORTED = {'BEHAVIOR_SUPPORTED', 'PARTIALLY_SUPPORTED'}


def digest(data):
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def from_joined(report):
    """Adapt static flow matches, deliberately without granting assertion support."""
    inventory = {m['id']: m for m in report['methodInventory']}
    variants = []
    for flow in report['flows']:
        presentation = flow.get('stagePresentation', {'mode': 'semantic'})
        # Old sealed runs have no stage contract. Keep their methods readable without
        # manufacturing lifecycle names from internal IDs.
        if 'stagePresentation' not in flow and any(not meaningful_label(s.get('label')) or
                not s.get('rationale') or not s.get('sourceIds') for s in flow['steps']):
            presentation = dict(mode='ungrouped', reason='Legacy mapping has no authored semantic stage labels.')
        if not isinstance(presentation, dict):
            raise ValueError('Invalid stage presentation')
        if presentation.get('mode') == 'semantic':
            validate_stages(flow, {s['id'] for s in report.get('sources', [])})
        else:
            validate_stages(dict(flow, stagePresentation=presentation), set())
        methods = sorted({m['methodId'] for m in flow['expectedMethods'] if m.get('methodId')})
        tests = [dict(testId=t['id'], name=t['name'], suite=t['suite'],
                      target=t['id'].split('::')[0], evidenceStatus='EXECUTION_ANCHORS_ONLY',
                      attribution=t.get('attribution', []),
                      attributionConfidence=t.get('attributionConfidence', []))
                 for t in flow['tests']]
        test_ids = {t['testId'] for t in tests}
        variants.append(dict(
            id=flow['id'], name=' · '.join(str(flow[k]) for k in ('feature', 'variant', 'outcome')),
            trigger='Recorded methods satisfy the declared flow anchors.',
            expected='Inspect the flow contract and test assertions to establish behavioral support.',
            dimensions=[], associations=[], similarityCandidates=tests,
            evidenceStatus='NOT_ASSESSED', reviewStatus='NOT_ASSESSED',
            assessmentSummary='Execution matching only; no assertion or contract verification is inferred.',
            limits=['Static flow methods are possible checkpoints, not an observed call sequence.'],
            references=[dict(name='Declared flow contract', methods=methods,
                             scope='Static catalog; not an upstream runtime reference.',
                             body=json.dumps({k: v for k, v in flow.items() if k in (
                                 'identification', 'expectations', 'completion', 'contextContract', 'exercise')}, indent=2))],
            stagePresentation=presentation,
            stages=[dict(id=s['id'], name=s['label'], rationale=s['rationale'], sourceIds=s['sourceIds'],
                         methods=[m for m in s.get('matches', []) if m in methods])
                    for s in flow['steps'] if presentation['mode'] == 'semantic'],
            methods={m: dict(observable=m in inventory, tests={tid: counts for tid, counts in
                     inventory.get(m, {}).get('tests', {}).items() if tid in test_ids}) for m in methods}))
    observed = sum(any(t.get('context', 0) + t.get('root', 0) for t in m.get('tests', {}).values())
                   for m in inventory.values())
    data = dict(schemaVersion=1, library=report['library'], version=report['version'],
                evidenceBasis='execution-anchors', variants=variants,
                catalogAssessment=report.get('catalogAssessment'),
                runCoverage=dict(inventory=len(inventory), observed=observed),
                testExecution=report['testExecution'], sources=report.get('sources', []), claims=[],
                provenance=report.get('evidenceRun', {}),
                limitations=['Execution matches do not establish assertion support or correctness.',
                             'Worker attribution uses serialized test windows, not causal request identity.',
                             'The declared catalog is bounded; outside scope is not measured.'])
    catalog = report.get('catalogAssessment') or {}
    if catalog.get('navigationSchemaVersion') == 1:
        data['catalogNavigation'] = project_navigation(catalog['families'], {v['id'] for v in variants})
    return data


def validate(data):
    if data.get('schemaVersion') != 1 or not data.get('library') or not data.get('version'):
        raise ValueError('Unsupported report schema or missing library identity')
    variants = data.get('variants', [])
    if not variants or len({v['id'] for v in variants}) != len(variants):
        raise ValueError('Report needs nonempty, unique behavior IDs')
    if 'catalogNavigation' in data:
        validate_navigation(data['catalogNavigation'], {v['id'] for v in variants})
    elif (data.get('catalogAssessment') or {}).get('navigationSchemaVersion') == 1:
        raise ValueError('Authored catalog navigation lost from report')
    if data.get('testExecution', {}).get('failures', 0) or data.get('testExecution', {}).get('errors', 0):
        raise ValueError('Failed test runs cannot produce a successful evidence report')
    scope = data.get('collectionScope')
    if scope is not None:
        if not isinstance(scope, dict) or scope.get('schemaVersion') != 1 or not isinstance(scope.get('excludedTests'), list):
            raise ValueError('Invalid collection scope')
        for excluded in scope['excludedTests']:
            if not isinstance(excluded, dict) or any(not isinstance(excluded.get(k), str) or
                    not excluded[k].strip() for k in ('pattern', 'reason', 'authorization')):
                raise ValueError('Test exclusions need pattern, reason and authorization')
    for v in variants:
        if not v.get('references'):
            raise ValueError('Behavior needs a reference: ' + v['id'])
        associations = v['associations']
        if any(t['evidenceStatus'] not in SUPPORTED for t in associations):
            raise ValueError('Execution similarity cannot be promoted to assertion support')
        if data.get('evidenceBasis') == 'execution-anchors' and associations:
            raise ValueError('Static execution matches cannot establish assertion support')
        ts = associations + v['similarityCandidates']
        ids = {t['testId'] for t in ts}
        if len(ids) != len(ts):
            raise ValueError('Duplicate test bindings in ' + v['id'])
        for ref in v['references']:
            if set(ref['methods']) - set(v['methods']):
                raise ValueError('Reference methods missing observation eligibility')
        stages = v.get('stages', [])
        if len({s['id'] for s in stages}) != len(stages):
            raise ValueError('Duplicate stage IDs in ' + v['id'])
        presentation = v.get('stagePresentation')
        if presentation is not None:
            if not isinstance(presentation, dict) or presentation.get('mode') not in ('semantic', 'ungrouped'):
                raise ValueError('Invalid stage presentation')
            if presentation['mode'] == 'ungrouped' and (stages or not presentation.get('reason', '').strip()):
                raise ValueError('Ungrouped presentation needs a reason and no stages')
            if presentation['mode'] == 'semantic':
                source_ids = {s['id'] for s in data.get('sources', [])}
                source_ids.update(s['id'] for s in v.get('knowledgeEvidence', {}).get('sources', []))
                validate_stages(dict(id=v['id'], steps=[dict(s, label=s.get('name')) for s in stages]), source_ids)
        for stage in stages:
            if not meaningful_label(stage.get('name')):
                raise ValueError('Meaningful stage label required (not a placeholder)')
            if set(stage['methods']) - set(v['methods']):
                raise ValueError('Stage contains an unknown method')
        for method in v['methods'].values():
            if not isinstance(method['observable'], bool) or set(method['tests']) - ids:
                raise ValueError('Invalid observation inventory or unknown test identity')
            for counts in method['tests'].values():
                if any(type(counts.get(k, 0)) is not int or counts.get(k, 0) < 0 for k in ('context', 'root')):
                    raise ValueError('Method counts must be nonnegative integers')


def normalize(report, run=None):
    expected_schema = 2 if 'flows' in report else 1
    if report.get('schemaVersion') != expected_schema:
        raise ValueError('Unsupported input schema version')
    data = from_joined(report) if 'flows' in report else copy.deepcopy(report)
    data.pop('reportIdentity', None)
    data.update(format='pharos-report', schemaVersion=1)
    data.setdefault('evidenceBasis', 'source-assessment')
    if run:
        manifest = read(run / 'manifest.json')
        library = read(run / 'knowledge/library.json')
        if manifest['status'] != 'collected':
            raise ValueError('Report metadata requires a collected run')
        if (data['library'], data['version']) != (library['library'], library['reviewedVersion']):
            raise ValueError('Report and run library identity differ')
        data['run'] = {k: manifest[k] for k in ('runId', 'module', 'status', 'attribution') if k in manifest}
        data['commands'] = dict(
            collect=['python3', 'tools/instrumentation-coverage/workflow.py', 'run', '--module', manifest['module']],
            replay=['python3', 'tools/instrumentation-coverage/pharos.py', 'report', '--run-directory', str(run)])
        scope = run / 'collection-scope.json'
        if scope.is_file():
            data['collectionScope'] = read(scope)
        exclusions = data.get('collectionScope', {}).get('excludedTests', [])
        if exclusions:
            for excluded in exclusions:
                data['commands']['collect'].extend(['--exclude-test', excluded['pattern']])
            for field, flag in [('reason', '--exclusion-reason'), ('authorization', '--exclusion-authorization')]:
                values = list(dict.fromkeys(excluded[field] for excluded in exclusions))
                data['commands']['collect'].extend([flag, '; '.join(values)])
    data.setdefault('run', {})
    data.setdefault('commands', {})
    data['catalogScope'] = (data.get('catalogAssessment') or {}).get('reconciliation') or {
        'status': 'unknown', 'unresolvedSignals': [], 'candidateFamilies': [], 'exclusions': [],
        'limitation': 'This saved report has no catalog reconciliation. Its scope is unknown.'}
    validate(data)
    data['deliveryState'] = delivery_state(data)
    data['reportIdentity'] = digest(data)
    return data


def delivery_state(data):
    """Describe evidence stage/scope, never infer user-task completion or semantic truth."""
    declared = [v for v in data['variants'] if not v['id'].startswith('candidate.')]
    stage = ('COLLECTED_NOT_ASSESSED' if data.get('evidenceBasis') == 'execution-anchors' else
             'ASSERTIONS_ASSESSED' if data.get('assertionAssessment') else 'ASSESSMENT_UNRECORDED')
    return dict(assessmentStage=stage, declaredBehaviors=len(declared),
                supportedBehaviors=sum(any(t['evidenceStatus'] == 'BEHAVIOR_SUPPORTED'
                                          for t in v['associations']) for v in declared),
                candidateFamilies=sum(v['id'].startswith('candidate.') for v in data['variants']),
                excludedTests=len(data.get('collectionScope', {}).get('excludedTests', [])))


def render(report, output, run=None):
    data = normalize(report, run)
    template = (TOOL / 'portal/index.html').read_text()
    template = template.replace('<script src="catalog-navigation.js"></script>',
                                '<script>' + (TOOL / 'portal/catalog-navigation.js').read_text() + '</script>')
    template = template.replace('<img src="../brand/pharos-mark.svg" alt="Pharos lighthouse">', (TOOL / 'brand/pharos-mark.svg').read_text())
    encoded = base64.b64encode(json.dumps(data, sort_keys=True).encode()).decode()
    output.mkdir(parents=True, exist_ok=True)
    write(output / 'report.json', data)
    (output / 'index.html').write_text(template.replace('__PILOT_DATA__', encoded))
    print('Pharos report:', output / 'index.html')
    return data


def summary(v):
    observed = {m for m, item in v['methods'].items()
                if any(t.get('context', 0) + t.get('root', 0) for t in item['tests'].values())}
    return dict(assessedTests=sorted(t['testId'] for t in v['associations']),
                candidateTests=sorted(t['testId'] for t in v['similarityCandidates']),
                observedMethods=sorted(observed))


def compare(left, right):
    validate(left); validate(right)
    if (left['library'], left['version'], left.get('evidenceBasis')) != (
            right['library'], right['version'], right.get('evidenceBasis')):
        raise ValueError('Comparison requires identical library/version and evidence basis')
    before = {v['id']: v for v in left['variants']}
    after = {v['id']: v for v in right['variants']}
    # A changed denominator or collection scope is not a coverage improvement.
    if set(before) != set(after) or any(
            {m: x['observable'] for m, x in before[k]['methods'].items()} !=
            {m: x['observable'] for m, x in after[k]['methods'].items()} or
            [r['methods'] for r in before[k]['references']] != [r['methods'] for r in after[k]['references']]
            for k in before):
        raise ValueError('Catalog or observation scope changed; review it before comparing coverage')
    changes = [dict(behaviorId=k, before=summary(before[k]), after=summary(after[k]))
               for k in sorted(before) if summary(before[k]) != summary(after[k])]
    return dict(library=left['library'], version=left['version'],
                baseline=left.get('reportIdentity'), candidate=right.get('reportIdentity'),
                changedBehaviors=changes, unchangedBehaviors=len(before)-len(changes),
                limitations=['Method-set changes do not establish correct Context identity or causal propagation.',
                             'Changed source assessments require review independently of changed execution.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('prepare', help='Prepare a module-independent LLM assessment dossier')
    prepare.add_argument('--report', type=Path, required=True)
    prepare.add_argument('--source', action='append', default=[])
    prepare.add_argument('--previous', type=Path)
    prepare.add_argument('--output', type=Path, required=True)
    assess = commands.add_parser('assess', help='Validate an authored assessment and generate its report')
    assess.add_argument('--session', type=Path, required=True)
    resume = commands.add_parser('resume', help='Print saved paths and the next workflow handoff')
    resume.add_argument('--session', type=Path, required=True)
    replay = commands.add_parser('report', help='Verify a sealed run and generate both report views')
    replay.add_argument('--run-directory', type=Path, required=True)
    build = commands.add_parser('build', help='Validate source assessments and build a behavioral portal report')
    for name in ('report', 'reference', 'naming-evidence', 'assessment', 'output'):
        build.add_argument('--'+name, type=Path, required=True)
    build.add_argument('--additional-assessment', type=Path, action='append', default=[])
    for name in ('render', 'validate'):
        p = commands.add_parser(name)
        p.add_argument('--report', type=Path, required=True)
        if name == 'render':
            p.add_argument('--output', type=Path, required=True)
            p.add_argument('--run-directory', type=Path)
    p = commands.add_parser('compare')
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            from assessment import prepare
            session = prepare(args.report, args.source, args.output, args.previous)
            print(json.dumps(session, indent=2))
        elif args.command == 'assess':
            from assessment import finish
            finish(args.session)
        elif args.command == 'resume':
            print(json.dumps(read(args.session), indent=2))
        elif args.command == 'report':
            subprocess.run([sys.executable, str(TOOL/'workflow.py'), 'report', '--run-directory',
                            str(args.run_directory.resolve())], cwd=ROOT, check=True)
        elif args.command == 'build':
            command = [sys.executable, str(TOOL/'cartography/pilot/build.py'), '--portal']
            for name in ('report', 'reference', 'naming_evidence', 'assessment', 'output'):
                command.extend(['--'+name.replace('_','-'), str(getattr(args,name))])
            for path in args.additional_assessment:
                command.extend(['--additional-assessment', str(path)])
            subprocess.run(command, cwd=ROOT, check=True)
        elif args.command == 'render':
            render(read(args.report), args.output, args.run_directory)
        elif args.command == 'validate':
            normalize(read(args.report)); print('Report structure valid; no semantic approval granted.')
        else:
            write(args.output, compare(read(args.baseline), read(args.candidate)))
    except (ValueError, KeyError) as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
