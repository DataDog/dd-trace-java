#!/usr/bin/env python3
"""Inventory catalog inputs and validate explicit dispositions; no semantic approval."""
import argparse
import hashlib
import json
from pathlib import Path


def identity(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def prepare(flows, upstream, previous, findings):
    signals = []
    for path in sorted(upstream.rglob('*.java')):
        signals.append({'id': 'upstream:' + path.relative_to(upstream).as_posix(),
                        'kind': 'upstream-test-source',
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    if not signals:
        raise ValueError('Upstream test inventory is empty')
    for old in previous:
        if old['library'] != flows['library']:
            raise ValueError('Previous catalog belongs to another library')
        for flow in old['flows']:
            signals.append({'id': 'prior:' + old['version'] + ':' + flow['id'],
                            'kind': 'prior-flow', 'flowId': flow['id'], 'definition': flow})
    for finding in findings:
        if not all(finding.get(k) for k in ('id', 'description', 'evidence')):
            raise ValueError('Finding needs id, description and evidence')
        signals.append(dict(finding, id='finding:' + finding['id'], kind='known-finding'))
    signals = list({s['id']: s for s in signals}.values())
    inputs = {'schemaVersion': 1, 'library': flows['library'], 'version': flows['version'],
              'signals': signals}
    review = {'schemaVersion': 1, 'inputIdentity': identity(inputs),
              'flowIds': sorted(f['id'] for f in flows['flows']), 'decisions': []}
    return inputs, review


def reconcile(flows, catalog, inputs, review):
    if inputs is None or review is None:
        raise ValueError('Catalog reconciliation inputs/review missing; inventory upstream tests, previous flows and known findings first')
    if inputs.get('schemaVersion') != 1 or review.get('schemaVersion') != 1:
        raise ValueError('Unsupported reconciliation schema')
    if (inputs.get('library'), inputs.get('version')) != (flows['library'], flows['version']):
        raise ValueError('Reconciliation library identity differs')
    if review.get('inputIdentity') != identity(inputs):
        raise ValueError('Reconciliation input identity changed')
    current = {f['id'] for f in flows['flows']}
    if sorted(current) != review.get('flowIds'):
        raise ValueError('Flow inventory changed; reconcile additions and removals before reuse')
    signals = inputs.get('signals', [])
    ids = {s['id'] for s in signals}
    if len(ids) != len(signals) or not any(s['kind'] == 'upstream-test-source' for s in signals):
        raise ValueError('Inventory needs unique signals and upstream test sources')
    families = {f['id']: f for f in catalog['families']}
    decisions = review.get('decisions', [])
    seen = set()
    unresolved = []
    excluded = []
    for d in decisions:
        sid = d['signalId']
        if sid in seen or sid not in ids or not d.get('rationale', '').strip():
            raise ValueError('Invalid or duplicate reconciliation decision: ' + sid)
        seen.add(sid)
        status = d.get('status')
        if status == 'excluded':
            excluded.append({'id': sid, 'rationale': d['rationale']})
        elif status == 'mapped':
            fs = d.get('flowIds', [])
            if not fs or set(fs) - current:
                raise ValueError('Mapped signal needs existing flow IDs: ' + sid)
        elif status == 'candidate':
            family = families.get(d.get('familyId'))
            if not family or family['classification'] != 'candidate':
                raise ValueError('Candidate signal needs a candidate family: ' + sid)
            unresolved.append(sid)
        else:
            raise ValueError('Unknown reconciliation decision status: ' + sid)
    unresolved.extend(sorted(ids - seen))
    removed = [s for s in signals if s['kind'] == 'prior-flow' and s['flowId'] not in current]
    if any(s['id'] not in seen for s in removed):
        raise ValueError('Prior flows disappeared without an explicit disposition')
    candidates = [dict(id=f['id'], name=f['name']) for f in families.values()
                  if f['classification'] == 'candidate']
    excluded.extend({'id': 'family:' + f['id'], 'rationale': f['rationale']}
                    for f in families.values() if f['classification'] == 'excluded')
    return {'status': 'unresolved' if unresolved or candidates else 'reconciled',
            'signalCount': len(signals), 'unresolvedSignals': sorted(unresolved),
            'knownFindings': [s for s in signals if s['kind'] == 'known-finding'],
            'candidateFamilies': candidates, 'exclusions': excluded,
            'limitation': 'Accounting for supplied evidence is not proof of complete library coverage.'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--knowledge', type=Path, required=True)
    p.add_argument('--upstream-tests', type=Path, required=True)
    p.add_argument('--previous-flows', type=Path, action='append', default=[])
    p.add_argument('--findings', type=Path, help='JSON array of supplied known findings')
    a = p.parse_args()
    targets = [a.knowledge / n for n in ('catalog-inputs.json', 'catalog-reconciliation.json')]
    if any(t.exists() for t in targets):
        p.error('Refusing to overwrite existing reconciliation; preserve/review prior inputs first')
    read = lambda path: json.loads(path.read_text())
    inputs, review = prepare(read(a.knowledge / 'flows.json'), a.upstream_tests,
                             [read(x) for x in a.previous_flows], read(a.findings) if a.findings else [])
    for path, value in zip(targets, (inputs, review)):
        path.write_text(json.dumps(value, indent=2) + '\n')
    print(f'Inventoried {len(inputs["signals"])} signals; author explicit dispositions in {targets[1]}')


if __name__ == '__main__':
    main()
