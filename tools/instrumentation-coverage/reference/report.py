#!/usr/bin/env python3
"""Classify local tests against recorded upstream execution fingerprints and render the report."""
import argparse
import base64
from collections import defaultdict
import json
import re
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

from plan import examples
from run import digest, validate, write
from source_review import excerpt
from fingerprint import classify, match_tests, method_weights

HERE = Path(__file__).resolve().parent


def validate_plan(plan):
    definitions = [plan.get('stageDefinitions', [])]
    definitions += [flow.get('stages', []) for family in plan['families'] for flow in family['flows']]
    for stages in definitions:
        labels = set()
        for stage in stages:
            label = stage['label'].strip()
            if not label or label in labels or re.match(r'^(step|stage)\s+\d', label, re.I):
                raise ValueError('Duplicate or placeholder stage label')
            if not stage['description'].strip() or not stage['selectors']:
                raise ValueError('Missing authored stage meaning or selectors')
            for selector in stage['selectors']:
                re.compile(selector)
            labels.add(label)
    families, flows = set(), set()
    for family in plan['families']:
        if family['id'] in families or not family['description'].strip():
            raise ValueError('Duplicate or undocumented family')
        families.add(family['id'])
        labels = set()
        for flow in family['flows']:
            identity = (tuple(flow['values']), flow['label'])
            if flow['id'] in flows or identity in labels:
                raise ValueError('Duplicate flow ownership or indistinguishable navigation')
            if len(flow['values']) != len(family['dimensions']) or not flow['label'].strip():
                raise ValueError('Missing authored navigation')
            if flow['label'].lower().startswith(('step ', 'stage ')):
                raise ValueError('Placeholder flow label')
            flows.add(flow['id'])
            labels.add(identity)


def canonical(method):
    if '#' in method:
        return method
    separator = method.rfind('.', 0, method.index('('))
    return method[:separator].replace('.', '/') + '#' + method[separator + 1:]


def authored_stage_groups(methods, definitions):
    remaining, groups = set(methods), []
    for stage in definitions:
        selected = sorted(method for method in remaining
                          if any(re.search(pattern, method) for pattern in stage['selectors']))
        groups.append((stage['label'], selected, stage['description']))
        remaining.difference_update(selected)
    if remaining:
        groups.append(('Supporting library methods', sorted(remaining),
                       'Other recorded library entries in this upstream example.'))
    return groups


def observable_method(method):
    """Mirror the JVM collector's eligibility without interpreting method names."""
    # JVM access flags: synthetic, abstract, native and bridge.
    return not method['name'].startswith('<') and not method['access'] & 0x1540


def reference_stage_groups(methods, focused_methods, definitions):
    if definitions:
        return authored_stage_groups(methods, definitions)
    return [('Reference methods', sorted(focused_methods),
             'Recorded methods of the catalog-selected class; no lifecycle grouping is inferred.')]


def reference_class(flow):
    """Use the neutral catalog field, accepting the original field for compatibility."""
    name = flow.get('referenceClass', flow.get('operator'))
    if not isinstance(name, str) or not name.strip():
        raise ValueError('Missing catalog referenceClass: ' + flow['id'])
    if 'referenceClass' in flow and 'operator' in flow and flow['operator'] != name:
        raise ValueError('Conflicting catalog class fields: ' + flow['id'])
    return name


def verify_seal(directory):
    seal = json.loads((directory / 'seal.json').read_text())
    for item in seal['files']:
        path = (directory / item['path']).resolve()
        if not path.is_relative_to(directory.resolve()) or digest(path) != item['sha256']:
            raise ValueError('Reference seal mismatch: ' + item['path'])


def build(reference, ours, plan):
    validate_plan(plan)
    verify_seal(reference)
    if (ours / 'seal.json').is_file():
        verify_seal(ours)
    else:
        inputs = [ours / 'resolved.json', ours / 'graph/raw-graph.json']
        inputs += sorted((ours / 'observations').rglob('report.json'))
        inputs += sorted((ours / 'test-results').rglob('TEST-*.xml'))
        inputs += sorted((ours / 'knowledge').glob('*.json'))
        write(ours / 'seal.json', dict(files=[dict(path=str(path.relative_to(ours)), sha256=digest(path))
                                             for path in inputs]))
    recordings = sorted(path for path in reference.glob('recorded-*') if path.is_dir())
    validation = validate(reference / 'baseline', recordings)
    recording = json.loads((recordings[0] / 'recording.json').read_text())
    if recording.get('outstandingFutures', -1) or recording.get('activeRootsAtSnapshot', -1):
        raise ValueError('Outstanding reference work')
    windows = {window['id']: window for window in recording['windows']}
    repeats = {directory.name: {window['id']: window for window in
               json.loads((directory / 'recording.json').read_text())['windows']} for directory in recordings}
    graph = json.loads((ours / 'graph/raw-graph.json').read_text())
    inventory = {method['id']: method for method in graph['definedMethods']}
    resolved = json.loads((ours / 'resolved.json').read_text())
    upstream_artifacts = {item['coordinate']: item['sha256'] for item in
                          json.loads((reference / 'baseline/runtime-manifest.json').read_text())}
    for item in resolved['artifacts']:
        if upstream_artifacts.get(item['coordinate']) != item['sha256']:
            raise ValueError('Our/upstream artifact mismatch: ' + item['coordinate'])
    hits, eligible, tests = defaultdict(list), set(), {}
    local_methods = defaultdict(lambda: defaultdict(int))
    for path in sorted((ours / 'observations').rglob('report.json')):
        data = json.loads(path.read_text())
        if not data['finalized'] or data['health']['errors'] or data['health']['droppedObservations']:
            raise ValueError('Unhealthy local collection')
        if data['execution']['runId'] != ours.name:
            raise ValueError('Local run identity mismatch')
        tests.update({item['id']: item['name'] for item in data['scenarios']})
        for row in data['methods']:
            method = canonical(row['method'])
            eligible.add(method)
            hits[method].extend(row['observations'])
            for observation in row['observations']:
                if observation['count'] > 0:
                    local_methods[observation['scenarioId']][method] += observation['count']
    outcomes = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (ours / 'test-results').rglob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in outcomes:
            outcomes[key] += int(suite.get(key, '0'))
    if outcomes['failures'] or outcomes['errors'] or not outcomes['tests']:
        raise ValueError('Our test suite did not pass')
    library_package = plan['libraryPackage']
    test_package = plan['testPackage']
    settings = plan.get('classification', {})
    classify(0, settings)
    local_methods = {test: {method: count for method, count in methods.items()
                           if method.startswith(library_package) and method in inventory}
                     for test, methods in local_methods.items()}
    reference_methods = []
    for family in plan['families']:
        for flow in family['flows']:
            # A union is used only to compute corpus method frequencies, never as a path to match.
            reference_methods.append({method for identity in examples(flow, test_package)
                                      for repetition in repeats.values() for method in repetition[identity]['methods']
                                      if method in eligible and method.startswith(library_package)})
    weights = method_weights(reference_methods)
    families = []
    for family in plan['families']:
        flows = []
        for flow in family['flows']:
            upstream = []
            alternatives = []
            for identity in examples(flow, test_package):
                window = windows[identity]
                for directory in recordings:
                    # Neutrality and link validation are checked for every repeat above.
                    if not (directory / 'recording.json').is_file():
                        raise ValueError('Missing repetition')
                physical = flow.get('async') and identity.removeprefix(test_package) not in flow.get('synchronousExamples', [])
                if physical and not window['handoffs']:
                    raise ValueError('Async example has no independent ownership evidence: ' + identity)
                if any(item['status'] == 'pending' for item in window.get('futures', [])):
                    raise ValueError('Pending upstream future: ' + identity)
                owner = library_package + reference_class(flow)
                owned = sorted(method for method in window['methods'] if method in inventory
                               and observable_method(inventory[method])
                               and (method.startswith(owner + '#') or method.startswith(owner + '$')))
                if flow.get('methodNames'):
                    owned = [method for method in owned if method.split('#')[1].split('(')[0] in flow['methodNames']]
                authored_stages = flow.get('stages', plan.get('stageDefinitions'))
                groups = reference_stage_groups(
                    (method for method in window['methods'] if method in inventory
                     and observable_method(inventory[method]) and method.startswith(library_package)),
                    owned, authored_stages)
                stages = []
                for label, methods, rationale in groups:
                    if not methods:
                        continue
                    if set(methods) - eligible:
                        raise ValueError('Local collection-scope gap: ' + identity)
                    rows = [dict(id=method, hit=bool(hits[method]),
                                 tests=sorted({item['scenarioId'] for item in hits[method]}),
                                 contextByTest={test: sorted({item['state'] for item in hits[method] if item['scenarioId'] == test})
                                                for test in {item['scenarioId'] for item in hits[method]}},
                                 entriesByTest={test: dict(context=sum(item['count'] for item in hits[method]
                                                      if item['scenarioId'] == test and item['state'] == 'NON_ROOT_CONTEXT'),
                                                      root=sum(item['count'] for item in hits[method]
                                                      if item['scenarioId'] == test and item['state'] == 'ROOT_CONTEXT'))
                                                for test in {item['scenarioId'] for item in hits[method]}},
                                 states=sorted({item['state'] for item in hits[method]})) for method in methods]
                    stages.append(dict(label=label, rationale=rationale, methods=rows))
                source = reference / 'upstream/src/test/java' / (identity.split('#')[0].replace('.', '/') + '.java')
                if flow.get('curatedReference'):
                    source = reference / 'tool/src/upstream/java' / (identity.split('#')[0].replace('.', '/') + '.java')
                implementation = reference / 'upstream/src/main/java' / (owner + '.java')
                upstream.append(dict(id=identity, stages=stages,
                                     provenance='curated-reference-example' if flow.get('curatedReference') else 'original-upstream-test',
                                     source=dict(path=str(source), sha256=digest(source), text=excerpt(source, identity.split('#')[1])),
                                     implementation=dict(path=str(implementation), sha256=digest(implementation), text=implementation.read_text()),
                                     methodsObserved=len(window['methods']),
                                     handoffs=window['handoffs'], registrations=window['registrations'],
                                     carrierLinks=window['carrierLinks'],
                                     handoffGroups=len(window['handoffs']),
                                     handoffExecutions=sum(item['count'] for item in window['handoffs']),
                                     futureStates=dict(completed=sum(item['status'] == 'completed' for item in window.get('futures', [])),
                                                       cancelled=sum(item['status'] == 'cancelled' for item in window.get('futures', []))),
                                     lateEntries=window['lateEntries']))
                upstream[-1]['repetitions'] = []
                for directory in recordings:
                    alternative = repeats[directory.name][identity]
                    repeat_methods = sorted(m for m in alternative['methods'] if m in inventory and
                                            (m.startswith(owner + '#') or m.startswith(owner + '$')))
                    upstream[-1]['repetitions'].append(dict(recording=directory.name, methods=repeat_methods,
                        handoffs=alternative['handoffs'], edges=alternative['edges'], futures=alternative['futures']))
                    alternatives.append(dict(id=identity, recording=directory.name,
                        methods={method: count for method, count in alternative['methods'].items()
                                 if method in eligible and method.startswith(library_package)},
                        stages=[dict(label=stage['label'], methods=[row['id'] for row in stage['methods']
                                if row['id'] in alternative['methods']]) for stage in stages]))
            methods = {row['id']: row for example in upstream for stage in example['stages'] for row in stage['methods']}
            matches = match_tests(alternatives, local_methods, owner, weights, settings)
            best_score = matches[0]['score'] if matches else 0
            flows.append(dict(id=flow['id'], label=flow['label'], values=flow['values'], examples=upstream,
                              stagePresentation=dict(mode='semantic') if flow.get('stages', plan.get('stageDefinitions'))
                                  else dict(mode='ungrouped', reason='No catalog-authored lifecycle stages.'),
                              hit=sum(row['hit'] for row in methods.values()), total=len(methods),
                              instrumentationObligation=flow.get('instrumentationObligation'),
                              score=best_score, classification=classify(best_score, settings), matches=matches))
        family_methods = {row['id']: row for flow in flows for example in flow['examples']
                          for stage in example['stages'] for row in stage['methods']}
        families.append(dict(id=family['id'], name=family['name'], description=family['description'],
                             dimensions=family['dimensions'], flows=flows,
                             hit=sum(row['hit'] for row in family_methods.values()), total=len(family_methods),
                             counts={label: sum(flow['classification'] == label for flow in flows)
                                     for label in ['Likely match', 'Partial match', 'No match']}))
        for flow in flows:
            for match in flow['matches']:
                close = []
                strongest = max((item['score'] for other in flows for item in other['matches']
                                 if item['testId'] == match['testId']), default=0)
                if match['score'] >= strongest - settings.get('closeMargin', 0.05):
                    close = [other['id'] for other in flows if other is not flow
                             and any(item['testId'] == match['testId'] and item['score'] >= strongest - settings.get('closeMargin', 0.05)
                                     for item in other['matches'])]
                match['closeAlternatives'] = close
    return dict(schemaVersion=1, library=plan['library'], version=plan['version'], scope=plan['scope'],
                metric='Execution-fingerprint similarity to upstream scenarios',
                classificationSettings=dict(likely=settings.get('likely', 0.80), partial=settings.get('partial', 0.50),
                    closeMargin=settings.get('closeMargin', 0.05), method='Inverse-frequency weighted method counts; 75% focused class, 25% full library path'),
                families=families, tests=tests, ourOutcomes=outcomes, referenceValidation=validation,
                referenceRun=str(reference), localRun=str(ours), graphSha256=digest(ours / 'graph/raw-graph.json'),
                localSealSha256=digest(ours / 'seal.json'),
                remainingReferenceScope=plan['remainingReferenceScope'], retainedFindings=plan['retainedFindings'],
                localAttribution='Initiating thread exact; worker attribution temporal within serialized tests, not independent causal identity',
                recorderScope='Method-entry comparison; upstream edges and handoffs are retained for browsing but not scored because the local collector does not record comparable edges')


def portal_data(data):
    """Adapt classification results to the existing Pharos layout, without assertion bindings."""
    families, variants = [], []
    for family in data['families']:
        dimensions = [dict(id=str(index), label=name,
                           values=[dict(id=value, label=value) for value in
                                   dict.fromkeys(flow['values'][index] for flow in family['flows'])])
                      for index, name in enumerate(family['dimensions'])]
        families.append(dict(id=family['id'], name=family['name'], description=family['description'],
            dimensions=dimensions, scenarios={flow['id']: dict(label=flow['label'],
                values={str(index): value for index, value in enumerate(flow['values'])}) for flow in family['flows']}))
        for flow in family['flows']:
            methods, stages, references = {}, {}, []
            for example in flow['examples']:
                ids = []
                for stage in example['stages']:
                    group = stages.setdefault(stage['label'], dict(id=stage['label'], name=stage['label'], methods=[]))
                    for row in stage['methods']:
                        methods[row['id']] = dict(observable=True, tests=row['entriesByTest'])
                        ids.append(row['id'])
                        if row['id'] not in group['methods']:
                            group['methods'].append(row['id'])
                references.append(dict(name=example['id'], upstreamId=example['id'], methods=list(dict.fromkeys(ids)),
                    scope=example['provenance'], body=example['source']['text'],
                    helpers=[dict(name='Reference implementation', body=example['implementation']['text'])]))
            candidates = [dict(match, name=data['tests'].get(match['testId'], match['testId']),
                suite='Instrumentation tests', target='test', evidenceStatus='EXECUTION_SIMILARITY_ONLY')
                for match in flow['matches']]
            variants.append(dict(id=flow['id'], name=family['name']+' · '+' · '.join(flow['values'])+' · '+flow['label'],
                stagePresentation=flow['stagePresentation'],
                trigger=family['description'], expected=flow['label'], classification=flow['classification'], score=flow['score'],
                associations=[], similarityCandidates=candidates, methods=methods,
                stages=list(stages.values()) if flow['stagePresentation']['mode'] == 'semantic' else [],
                references=references, limits=[], sourceEvidence=[], claimEvidence=[], assessmentSummary=''))
    inventory = {method for variant in variants for method in variant['methods']}
    observed = {method for variant in variants for method, row in variant['methods'].items() if row['tests']}
    return dict(format='pharos-report', schemaVersion=1, library=data['library'], version=data['version'],
        evidenceBasis='execution-fingerprint', variants=variants, catalogNavigation=dict(schemaVersion=1, families=families),
        classificationSettings=data['classificationSettings'],
        testExecution=dict(passed=data['ourOutcomes']['tests']-data['ourOutcomes']['skipped'], **data['ourOutcomes']),
        runCoverage=dict(observed=len(observed), inventory=len(inventory)),
        sources=[], claims=[], limitations=[], provenance=dict(referenceRun=data['referenceRun'], localRun=data['localRun'],
            scope=data['scope'], referenceTestsPassed=data['referenceValidation']['passed'], metric=data['metric']))


def render(data, output):
    tool = HERE.parent
    template = (tool / 'portal/index.html').read_text()
    template = template.replace('<script src="catalog-navigation.js"></script>',
                                '<script>' + (tool / 'portal/catalog-navigation.js').read_text() + '</script>')
    template = template.replace('<img src="../brand/pharos-mark.svg" alt="Pharos lighthouse">',
                                (tool / 'brand/pharos-mark.svg').read_text())
    projected = portal_data(data)
    encoded = base64.b64encode(json.dumps(projected).encode()).decode()
    output.write_text(template.replace('__PILOT_DATA__', encoded))
    write(output.parent / 'portal-report.json', projected)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--ours', type=Path, required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--findings', type=Path)
    args = parser.parse_args()
    data = build(args.reference.resolve(), args.ours.resolve(), json.loads(args.plan.read_text()))
    if args.findings:
        data['knownFindingEvidence'] = json.loads(args.findings.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    write(args.output / 'report.json', data)
    shutil.copy2(args.plan, args.output / 'catalog.json')
    render(data, args.output / 'report.html')
    inputs = [args.output / name for name in ['report.json', 'catalog.json', 'report.html', 'portal-report.json']]
    write(args.output / 'seal.json', dict(files=[dict(path=path.name, sha256=digest(path)) for path in inputs]))
    print(args.output / 'report.html')
