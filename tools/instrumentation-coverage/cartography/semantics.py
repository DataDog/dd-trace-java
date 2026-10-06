#!/usr/bin/env python3
"""Build source-pinned naming packets and validate an explicitly authored interpretation."""
import argparse
import hashlib
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def packet(families):
    index = json.loads((HERE.parent / 'research/spring-webmvc-test-led/knowledge/upstream-test-inventory.json').read_text())
    tests = {t['owner'] + '#' + t['name']: t for t in index['tests']}
    sources = {}
    evidence = []
    for family in families:
        examples = []
        for example in family['examples']:
            test = tests[example['name']]
            path = test['path']
            if path not in sources:
                relative = path.split('/src/test/java/')[1]
                body = (HERE / 'build/upstream-src' / relative).read_bytes()
                if hashlib.sha256(body).hexdigest() != test['sourceSha256']:
                    raise ValueError('Upstream source changed: ' + path)
                sources[path] = dict(sha256=test['sourceSha256'], text=body.decode())
            snippet = '\n'.join(sources[path]['text'].splitlines()[test['startLine']-1:test['endLine']])
            examples.append(dict(name=example['name'], testId=example['testId'], path=path,
                                 startLine=test['startLine'], endLine=test['endLine'], url=test['url'], body=snippet))
        evidence.append(dict(id=family['id'], examples=examples, methods=family['methods'],
                             methodSupport=family['methodSupport'], recordedNesting=family['referenceNesting']))
    return dict(schemaVersion=1, sourceVersion='v6.0.2', families=evidence, sources=sources,
                limits=['Selected upstream tests only', 'Method presence is not branch or outcome evidence',
                        'Recorded nesting is synchronous and partial', 'Families may mix behavioral variants'])


def interpret(evidence, annotation):
    if annotation.get('packetSha256') != digest(evidence):
        raise ValueError('Naming evidence changed; regenerate and review the interpretation')
    families = {f['id']: f for f in evidence['families']}
    entries = annotation['families']
    if len(entries) != len(families) or {a['id'] for a in entries} != set(families):
        raise ValueError('Interpretation must cover each execution family exactly once')
    results = {}
    for entry in entries:
        family = families[entry['id']]
        if not all(isinstance(entry.get(k), str) and entry[k].strip() for k in ('name', 'feature', 'summary')):
            raise ValueError('Missing semantic label')
        names = {x['name'] for x in family['examples']}
        if set(entry.get('evidenceTests', [])) != names:
            raise ValueError('Interpretation must retain all member-test evidence')
        stages = []
        remaining = set(family['methods'])
        for rule in annotation['stageRules']:
            matched = sorted(m for m in remaining if re.search(rule['pattern'], m))
            if not matched:
                continue
            stages.append(dict(id=rule['id'], label=rule['label'], methods=matched))
            remaining.difference_update(matched)
        if remaining:
            raise ValueError('Methods omitted from landmark groups')
        if len({s['id'] for s in stages}) != len(stages):
            raise ValueError('Duplicate landmark group')
        results[entry['id']] = dict(**entry, stages=stages, examples=family['examples'],
                                    status='llm-interpreted-source-backed-draft',
                                    ordering='Conceptual grouping, not measured execution order')
    return results


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--classification', type=Path, default=HERE/'build/report/classification.json')
    p.add_argument('--output', type=Path, default=HERE/'build/naming-evidence.json')
    p.add_argument('--interpretation', type=Path)
    args = p.parse_args()
    evidence = packet(json.loads(args.classification.read_text())['families'])
    if args.interpretation:
        interpret(evidence, json.loads(args.interpretation.read_text()))
    args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True)+'\n')
    print('Evidence packet:', args.output, '\nSHA-256:', digest(evidence))


if __name__ == '__main__':
    main()
