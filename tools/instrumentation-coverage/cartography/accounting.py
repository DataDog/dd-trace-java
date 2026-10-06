"""Reconcile statistical retrieval against every XML test result, keeping task identity."""
import collections
import json
import xml.etree.ElementTree as ET


def reconcile(run, assignments):
    classified = collections.defaultdict(list)
    for item in assignments:
        key = (item['target'], item['suite'], item['name'])
        classified[key].append(item)
    windows = set()
    collected_suites = set()
    for path in (run/'observations').rglob('report.json'):
        parts = path.relative_to(run/'observations').parts
        target = parts[0] if len(parts) >= 4 else 'test'
        suite = path.parent.name
        collected_suites.add((target, suite))
        for scenario in json.loads(path.read_text()).get('scenarios', []):
            windows.add((target, suite, scenario['name']))
    cases = []
    for path in sorted((run/'test-results').rglob('TEST-*.xml')):
        target = path.relative_to(run/'test-results').parts[0]
        for index, case in enumerate(ET.parse(path).getroot().findall('testcase')):
            key = (target, case.get('classname'), case.get('name'))
            outcome = ('SKIPPED' if case.find('skipped') is not None else
                       'FAILED' if case.find('failure') is not None or case.find('error') is not None else 'PASSED')
            cases.append((key, outcome, str(path.relative_to(run)), index))
    frequencies = collections.Counter(key for key, _, _, _ in cases)
    rows = []
    for key, outcome, source, index in cases:
        matches = classified.get(key, [])
        ambiguous = len(matches) > 1 or frequencies[key] > 1
        if outcome == 'SKIPPED':
            status = 'SKIPPED'
        elif ambiguous:
            status = 'IDENTITY_AMBIGUOUS'
        elif matches:
            status = matches[0]['status']
        elif key in windows:
            status = 'NO_LIBRARY_OBSERVATIONS'
        elif key[:2] in collected_suites:
            status = 'NO_TEST_WINDOW'
        else:
            status = 'NO_COLLECTION'
        row = dict(matches[0]) if len(matches) == 1 and not ambiguous else dict(
            testId=f'{source}#{index}', candidateFlowIds=[], topCandidates=[], attributionConfidence=[])
        row.update(target=key[0], suite=key[1], name=key[2], outcome=outcome, status=status, resultSource=source)
        rows.append(row)
    orphans = set(classified)-set(frequencies)
    if orphans:
        raise ValueError('Classified windows absent from XML results: '+repr(sorted(orphans)))
    resolved = json.loads((run/'resolved.json').read_text())
    targets = []
    declared = resolved.get('testTargets', [{'task': x, 'artifacts': []} for x in sorted({k[0] for k in frequencies})])
    for target in declared:
        selected = [r for r in rows if r['target'] == target['task']]
        targets.append(dict(**target, results=len(selected), outcomes=dict(collections.Counter(r['outcome'] for r in selected)),
                            categories=dict(collections.Counter(r['status'] for r in selected))))
    return dict(tests=rows, total=len(rows), counts=dict(collections.Counter(r['status'] for r in rows)),
                targets=targets, requestedTasks=resolved.get('requestedTasks', []))
