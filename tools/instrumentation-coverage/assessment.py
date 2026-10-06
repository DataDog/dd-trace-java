"""Prepare and validate run-specific assertion assessments for any Pharos module report."""

import hashlib
from pathlib import Path

from pharos import ROOT, digest, normalize, read, render, write

FORMAT = 'pharos-assertion-assessment'


def source_record(relative, root):
    root = root.resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError('Source must be a file inside the repository: ' + str(relative))
    text = path.read_text()
    return dict(id=str(path.relative_to(root)), path=str(path.relative_to(root)),
                sha256=hashlib.sha256(text.encode()).hexdigest(), text=text)


def candidates(variant):
    return {t['testId']: t for t in variant['associations'] + variant['similarityCandidates']}


def prepare(report_path, sources, output, previous=None, root=ROOT):
    """Create a fresh draft; previous bindings are hints, never current support."""
    data = normalize(read(report_path))
    prior = read(previous) if previous else None
    if prior and (prior.get('format') != FORMAT or prior.get('schemaVersion') != 1):
        raise ValueError('Previous assessment must use the generic Pharos format')
    if prior and (prior['library'], prior['version']) != (data['library'], data['version']):
        raise ValueError('Library/version changed; refresh the KB before reassessment')
    paths = list(dict.fromkeys([s['path'] for s in prior['sources']] + list(sources))) if prior else sources
    if not paths:
        raise ValueError('Supply --source for test bodies and relevant fixture/assertion helpers')
    records = [source_record(path, root) for path in dict.fromkeys(paths)]
    source_meta = [{k: v for k, v in s.items() if k != 'text'} for s in records]
    old_sources = {s['id']: s for s in prior['sources']} if prior else {}
    changes = [dict(path=s['path'], status='new' if s['id'] not in old_sources else
                    'unchanged' if s['sha256'] == old_sources[s['id']]['sha256'] else 'changed') for s in records]
    identities = {v['id']: candidates(v) for v in data['variants']}
    old_bindings = [{**b, 'behaviorId': v['id'],
                     'identityStillPresent': b['testId'] in identities.get(v['id'], {}),
                     'requiresReassessment': True}
                    for v in prior['behaviors'] for b in v['bindings']] if prior else []
    assessment = dict(format=FORMAT, schemaVersion=1, library=data['library'], version=data['version'],
                      reportIdentity=data['reportIdentity'], ready=False,
                      reviewStatus='LLM_DRAFT_NOT_HUMAN_REVIEWED', sources=source_meta,
                      behaviors=[dict(id=v['id'], assessmentSummary='', bindings=[])
                                 for v in data['variants']])
    dossier = dict(schemaVersion=1, library=data['library'], version=data['version'],
                   reportIdentity=data['reportIdentity'], sources=records, behaviors=data['variants'],
                   sourceChanges=changes, previousAssessment=prior, previousBindings=old_bindings,
                   limitations=data.get('limitations', []) + [
                       'Source files are current working-tree evidence, not immutable source snapshots of the original run.',
                       'Passing tests and matching methods do not establish their assertions support a behavior.',
                       'Previous bindings require reassessment even when IDs and source hashes match.'])
    if output.exists() and any(output.iterdir()):
        raise ValueError('Output directory is not empty; use a new iteration directory')
    output.mkdir(parents=True, exist_ok=True)
    write(output/'dossier.json', dossier)
    write(output/'assessment.json', assessment)
    session = dict(schemaVersion=1, phase='assess', module=data.get('run', {}).get('module'),
                   library=data['library'], version=data['version'],
                   report=str(report_path.resolve()), reportIdentity=data['reportIdentity'],
                   assessment=str((output/'assessment.json').resolve()),
                   dossier=str((output/'dossier.json').resolve()),
                   previousAssessment=str(previous.resolve()) if previous else None,
                   commands=data.get('commands', {}),
                   nextCommand=['python3', 'tools/instrumentation-coverage/pharos.py', 'assess',
                                '--session', str((output/'session.json').resolve())])
    write(output/'session.json', session)
    return session


def assess(report_path, assessment_path, output, root=ROOT):
    data = normalize(read(report_path)); a = read(assessment_path)
    if a.get('format') != FORMAT or a.get('schemaVersion') != 1 or a.get('ready') is not True:
        raise ValueError('Assessment is incomplete; inspect the dossier and set ready=true after authoring')
    if a.get('reviewStatus') != 'LLM_DRAFT_NOT_HUMAN_REVIEWED':
        raise ValueError('This LLM workflow produces draft assessments, not human approval')
    if (a['library'], a['version'], a['reportIdentity']) != (data['library'], data['version'], data['reportIdentity']):
        raise ValueError('Input evidence changed; prepare a new assessment')
    sources = {}
    for source in a['sources']:
        current = source_record(source['path'], root)
        if source['id'] in sources or source['id'] != current['id']:
            raise ValueError('Duplicate or invalid source identity')
        if source['sha256'] != current['sha256']:
            raise ValueError('Source changed; prepare again and reassess: ' + source['path'])
        sources[source['id']] = current
    by_id = {b['id']: b for b in a['behaviors']}
    if len(by_id) != len(a['behaviors']) or set(by_id) != {v['id'] for v in data['variants']}:
        raise ValueError('Assessment must retain the entire declared behavior inventory')
    for variant in data['variants']:
        item = by_id[variant['id']]; available = candidates(variant); seen = set(); associations = []; claims = []
        if not item['assessmentSummary'].strip():
            raise ValueError('Explain assessed support or unresolved evidence for ' + variant['id'])
        for binding in item['bindings']:
            tid = binding['testId']
            if tid not in available or tid in seen:
                raise ValueError('Unknown or duplicate test identity for ' + variant['id'])
            seen.add(tid)
            if available[tid].get('outcome') not in (None, 'PASSED'):
                raise ValueError('Only successful test executions may support an assertion binding')
            if binding['evidenceStatus'] not in ('BEHAVIOR_SUPPORTED', 'PARTIALLY_SUPPORTED'):
                raise ValueError('Unsupported assertion evidence status')
            if not binding['reason'].strip() or not binding['citations']:
                raise ValueError('Support requires rationale and exact local assertion citations')
            for citation in binding['citations']:
                source = sources.get(citation['sourceId'])
                if not source:
                    raise ValueError('Unknown citation source')
                start, end = citation['startLine'], citation['endLine']; lines = source['text'].splitlines()
                if (type(start) is not int or type(end) is not int or not 1 <= start <= end <= len(lines)
                        or not citation['quote'].strip() or citation['quote'] not in '\n'.join(lines[start-1:end])):
                    raise ValueError('Citation quote/range does not bind')
            claim_id = 'assertion-' + str(len(claims))
            claims.append(dict(id=claim_id, kind='assertion', text=binding['reason'], citations=binding['citations']))
            associations.append({**available[tid], 'evidenceStatus': binding['evidenceStatus'],
                                 'basis': binding['reason'], 'claimIds': [claim_id],
                                 'reviewStatus': a['reviewStatus']})
        variant['associations'] = associations
        # Preserve why unassessed candidates were retrieved, without retaining old assertion status.
        variant['similarityCandidates'] = [dict(t, evidenceStatus=(t['evidenceStatus'] if t['evidenceStatus'] in
                ('SIMILAR_EXECUTION_ONLY', 'EXECUTION_ANCHORS_ONLY') else 'UNASSESSED_TEST'))
                for tid, t in available.items() if tid not in seen]
        variant['assessmentSummary'] = item['assessmentSummary']
        variant['reviewStatus'] = a['reviewStatus']
        variant['evidenceStatus'] = ('BEHAVIOR_SUPPORTED' if any(t['evidenceStatus'] == 'BEHAVIOR_SUPPORTED'
                                      for t in associations) else 'PARTIALLY_SUPPORTED' if associations else 'NOT_ASSESSED')
        # Keep KB reference sources and claims in their own provenance, not as fresh assertion evidence.
        variant['knowledgeEvidence'] = variant.get('knowledgeEvidence') or dict(sources=variant.get('sourceEvidence', data.get('sources', [])),
                                            claims=variant.get('claimEvidence', data.get('claims', [])))
        variant['sourceEvidence'] = [{k: v for k, v in s.items() if k != 'text'} for s in sources.values()]
        variant['claimEvidence'] = claims
        for dimension in variant.get('dimensions', []):
            if isinstance(dimension, dict): dimension['status'] = 'NOT_ASSESSED'
    data['evidenceBasis'] = 'source-assessment'
    data['reviewStatus'] = a['reviewStatus']
    data['assertionAssessment'] = dict(sha256=digest(a), inputReportIdentity=a['reportIdentity'],
                                      sourceSnapshots='current-working-tree', semanticApproval=False)
    data['inputAssessmentProvenance'] = data.get('assessments', data.get('provenance', {}))
    data['assessments'] = [data['assertionAssessment']]
    data['limitations'] = list(dict.fromkeys(data.get('limitations', []) + [
        'Assertion associations are LLM-authored drafts; citation validation does not prove semantic correctness.',
        'Source assessment uses current working-tree files; original-run source snapshots are not available.']))
    return render(data, output)


def finish(session_path, root=ROOT):
    session = read(session_path)
    if normalize(read(Path(session['report'])))['reportIdentity'] != session['reportIdentity']:
        raise ValueError('Session input changed; prepare a new iteration')
    output = session_path.parent/'report'
    data = assess(Path(session['report']), Path(session['assessment']), output, root)
    session.update(phase='investigate', assessedReport=str((output/'report.json').resolve()),
                   assessedReportIdentity=data['reportIdentity'], nextAction='Inspect unresolved evidence; improve a test within the requested scope, recollect, then prepare with --previous.')
    session.pop('nextCommand', None)
    write(session_path, session)
    return data
