#!/usr/bin/env python3
"""Validate flow references and provenance against a supplied graph; no semantic approval."""
import argparse
import hashlib
import json
from pathlib import Path

from workflow import validate_knowledge
from catalog_reconciliation import reconcile


def validate_catalog(raw, flows, catalog):
    if catalog is None:
        return None, ['Missing catalog.json; reconcile functionality families before collection'], []
    errors, warnings = [], []
    if catalog.get('schemaVersion') != 1:
        errors.append('Unsupported catalog assessment schema version')
    if (catalog.get('library'), catalog.get('version')) != (flows['library'], flows['version']):
        errors.append('Catalog assessment and flow catalog identity differ')
    if catalog.get('status') not in ('draft', 'needs-review', 'reviewed'):
        errors.append('Catalog assessment needs a review status')
    if not catalog.get('scope', '').strip():
        errors.append('Catalog assessment needs an explicit scope')

    defined = {method['id'] for method in raw['definedMethods']}
    flow_ids = {flow['id'] for flow in flows['flows']}
    source_ids = {source['id'] for source in flows.get('sources', [])}
    family_ids, classified_flows, families = set(), set(), []
    for family in catalog.get('families', []):
        family_id = family.get('id')
        if not family_id or family_id in family_ids:
            errors.append('Catalog family IDs must be present and unique')
            continue
        family_ids.add(family_id)
        status = family.get('classification')
        if status not in ('mapped', 'candidate', 'excluded'):
            errors.append(family_id + ': unknown catalog classification')
        references = set(family.get('flowIds', []))
        if references - flow_ids:
            errors.append(family_id + ': references unknown flows')
        if status == 'mapped' and not references:
            errors.append(family_id + ': mapped family needs at least one flow')
        if status != 'mapped' and references:
            errors.append(family_id + ': only mapped families may reference flows')
        classified_flows.update(references)
        methods = family.get('entryMethods', [])
        missing = sorted(set(methods) - defined)
        if missing:
            errors.append(family_id + ': entry methods are absent from the graph: ' + ', '.join(missing))
        if status == 'candidate' and not methods:
            errors.append(family_id + ': candidate family needs graph entry methods')
        sources = set(family.get('sourceIds', []))
        if not sources or sources - source_ids or not family.get('rationale', '').strip():
            errors.append(family_id + ': needs rationale and known source references')
        families.append({
            'id': family_id,
            'name': family.get('name', family_id),
            'classification': status,
            'flowIds': sorted(references),
            'entryMethods': methods,
            'entryMethodBindings': [
                {'methodId': method, 'bindingStatus': 'RESOLVED' if method in defined else 'UNRESOLVED'}
                for method in methods
            ],
            'rationale': family.get('rationale', ''),
            'sourceIds': family.get('sourceIds', []),
        })
    if not families:
        errors.append('Catalog must have a nonempty functionality inventory')
    unclassified = sorted(flow_ids - classified_flows)
    if unclassified:
        errors.append('Flows absent from catalog assessment: ' + ', '.join(unclassified))
    counts = {classification: sum(
        item['classification'] == classification for item in families)
        for classification in ('mapped', 'candidate', 'excluded')}
    return {
        'schemaVersion': 1,
        'status': catalog.get('status'),
        'scope': catalog.get('scope'),
        'summary': {**counts, 'total': len(families),
                    'classifiedFlows': len(classified_flows),
                    'cataloguedFlows': len(flow_ids)},
        'families': families,
        'unclassifiedFlowIds': unclassified,
        'semanticReview': 'NOT_PERFORMED',
    }, errors, warnings


def validate(raw, library, flows, observation, evidence=None, graph_sha=None, catalog=None):
    validate_knowledge(library, flows, observation)
    errors, warnings, bindings = [], [], []
    artifact_ids = {a['coordinate'] for a in raw['artifacts']}
    library_id, version = flows['library'], flows['version']
    graph_source = library.get('graphSource', {})
    if graph_source.get('kind') == 'jdk-module':
        identity_matches = graph_source.get('module') in artifact_ids
    else:
        identity_matches = bool(
            {library_id + ':' + version, library_id.split(':')[-1] + '-' + version} & artifact_ids)
    if not identity_matches:
        errors.append('Primary library version is absent from graph artifacts')
    defined = {m['id'] for m in raw['definedMethods']}
    nodes = defined | {m for m in raw.get('externalMethods', []) if not m.startswith('invokedynamic/')}
    sources = {source['id'] for source in flows.get('sources', [])}
    flow_ids = {flow['id'] for flow in flows['flows']}
    for flow in flows['flows']:
        if not flow.get('sourceIds') or set(flow['sourceIds']) - sources:
            errors.append(flow['id'] + ': missing or unknown source references')
        if 'completion' not in flow:
            warnings.append(flow['id'] + ': legacy inferred completion; declare explicit roles')
        if not flow.get('status'):
            errors.append(flow['id'] + ': missing review status')
        for step in flow['steps']:
            matches = sorted(node for node in nodes if step['anchor'] in node)
            bindings.append({'flowId': flow['id'], 'stepId': step['id'], 'methods': matches})
            if not matches:
                errors.append(flow['id'] + '/' + step['id'] + ': unresolved anchor')
            elif len(matches) > 1:
                warnings.append(flow['id'] + '/' + step['id'] + ': multiple overloads; review existential step matching')
            if matches and not any(m in defined for m in matches):
                warnings.append(flow['id'] + '/' + step['id'] + ': external declaration only; no analyzed method body')
    if evidence is None:
        warnings.append('No evidence.json; claim-level provenance is not validated')
    else:
        if evidence.get('schemaVersion') != 1 or evidence.get('graphSha256') != graph_sha:
            errors.append('Evidence schema or graph fingerprint mismatch')
        ids = set()
        supported = set()
        for claim in evidence.get('claims', []):
            if not claim.get('id') or claim['id'] in ids:
                errors.append('Claim IDs must be present and unique')
            ids.add(claim.get('id'))
            if claim.get('flowId') not in flow_ids:
                errors.append('Claim references unknown flow')
            supported.add(claim.get('flowId'))
            if claim.get('basis') not in ('static', 'curated', 'hypothesis', 'observed'):
                errors.append('Unknown claim basis')
            if not claim.get('claim') or not claim.get('sourceIds') or set(claim['sourceIds']) - sources:
                errors.append('Claim needs text and known source references')
            if set(claim.get('methodIds', [])) - nodes:
                errors.append('Claim references unknown graph methods')
            if claim.get('basis') == 'observed' and not claim.get('runId'):
                errors.append('Observed claim needs a runId')
        if flow_ids - supported:
            errors.append('Every flow needs at least one provenance claim')
    catalog_assessment, catalog_errors, catalog_warnings = validate_catalog(raw, flows, catalog)
    errors.extend(catalog_errors)
    warnings.extend(catalog_warnings)
    if catalog_assessment:
        for family in catalog_assessment['families']:
            for binding in family['entryMethodBindings']:
                if binding['bindingStatus'] == 'UNRESOLVED':
                    errors.append(family['id'] + ': unresolved catalog entry method')
    return {'valid': not errors, 'semanticReview': 'NOT_PERFORMED', 'errors': errors,
            'warnings': warnings, 'bindings': bindings,
            'catalogAssessment': catalog_assessment}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--graph', type=Path, required=True)
    parser.add_argument('--knowledge', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    load = lambda name: json.loads((args.knowledge / name).read_text())
    graph = args.graph.read_bytes()
    evidence_path = args.knowledge / 'evidence.json'
    catalog_path = args.knowledge / 'catalog.json'
    try:
        result = validate(json.loads(graph), load('library.json'), load('flows.json'),
                          load('observation.json'), load('evidence.json') if evidence_path.exists() else None,
                          hashlib.sha256(graph).hexdigest(),
                          load('catalog.json') if catalog_path.exists() else None)
        if result['valid']:
            def optional(name):
                path = args.knowledge / name
                return load(name) if path.exists() else None
            scope = reconcile(load('flows.json'), load('catalog.json'),
                              optional('catalog-inputs.json'), optional('catalog-reconciliation.json'))
            result['catalogAssessment']['reconciliation'] = scope
            if scope['status'] != 'reconciled':
                result['warnings'].append(f"Catalog scope unresolved: {len(scope['unresolvedSignals'])} signals and {len(scope['candidateFamilies'])} candidate families; overall percentage unavailable")
    except (ValueError, KeyError, TypeError) as error:
        result = {'valid': False, 'semanticReview': 'NOT_PERFORMED', 'errors': [str(error)]}
    output = json.dumps(result, indent=2, sort_keys=True) + '\n'
    if args.output:
        args.output.write_text(output)
    else:
        print(output, end='')
    raise SystemExit(0 if result['valid'] else 1)


if __name__ == '__main__':
    main()
