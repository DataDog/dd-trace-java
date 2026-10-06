"""Domain validation for source-grounded behavioral assessments; no semantic certification."""
import hashlib,json

def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def validate(assessment,report,packet,root):
    if assessment.get('schemaVersion')!=2:raise ValueError('Behavior assessment schemaVersion must be 2')
    if assessment['library']!=report['library'] or assessment['version']!=report['version'] or assessment['sourceVersion']!=packet['sourceVersion']:
        raise ValueError('Library/version mismatch')
    if assessment['inputIdentity']!={'report':digest(report),'namingEvidence':digest(packet)}:
        raise ValueError('Input evidence changed; regenerate dossier and reassess')
    if assessment.get('reviewStatus') not in ('LLM_DRAFT_NOT_HUMAN_REVIEWED','HUMAN_REVIEWED'):raise ValueError('Explicit review status required')
    if assessment['reviewStatus']=='HUMAN_REVIEWED' and not assessment.get('reviewRecord'):raise ValueError('Human review requires a review record')
    sources={}
    for source in assessment['sources']:
        if source['id'] in sources:raise ValueError('Duplicate source id')
        if source['origin']=='local':
            path=(root/source['path']).resolve()
            if not path.is_relative_to(root.resolve()):raise ValueError('Source outside repository')
            body=path.read_text()
        elif source['origin']=='upstream':body=packet['sources'][source['path']]['text']
        else:raise ValueError('Unknown source origin')
        if hashlib.sha256(body.encode()).hexdigest()!=source['sha256']:raise ValueError('Source changed; reassess '+source['path'])
        lines=body.splitlines()
        for a,b in source['ranges']:
            if not 1<=a<=b<=len(lines):raise ValueError('Invalid source range')
        sources[source['id']]={**source,'text':body,'excerpts':[dict(startLine=a,endLine=b,body='\n'.join(lines[a-1:b])) for a,b in source['ranges']]}
    claims={}
    for claim in assessment['claims']:
        if claim['id'] in claims:raise ValueError('Duplicate claim id')
        if claim['kind'] not in ('trigger','outcome','assertion','scope'):raise ValueError('Invalid claim kind')
        if claim['scope'] not in ('component','mocked-lifecycle','http-integration','source-only'):raise ValueError('Explicit claim scope required')
        if not claim.get('citations'):raise ValueError('Uncited claim')
        for c in claim['citations']:
            source=sources[c['sourceId']];a,b=c['startLine'],c['endLine'];lines=source['text'].splitlines()
            if not 1<=a<=b<=len(lines) or not c['quote'] or c['quote'] not in '\n'.join(lines[a-1:b]):raise ValueError('Citation quote/range does not bind')
        claims[claim['id']]=claim
    variants=assessment['variants']
    if not variants:raise ValueError('Assessment has no variants')
    if len({v['id'] for v in variants})!=len(variants):raise ValueError('Duplicate variant id')
    tests={t['testId']:t for t in report['statisticalAttribution']['testAccounting']['tests']}
    upstream={e['name'] for f in packet['families'] for e in f['examples']}
    for v in variants:
        if not all(v.get(k) for k in ('name','trigger','expected','dimensions','assessmentSummary')):raise ValueError('Incomplete variant')
        for field,kind in [('triggerClaims','trigger'),('outcomeClaims','outcome')]:
            if not v.get(field) or any(c not in claims or claims[c]['kind']!=kind for c in v[field]):raise ValueError('Unbound variant '+field)
        if not v['upstream'] or any(e not in upstream for e in v['upstream']):raise ValueError('Unknown upstream example')
        if set(v['referenceScopes'])!=set(v['upstream']):raise ValueError('Per-reference scope required')
        seen=set()
        for binding in v['testBindings']:
            if binding['testId'] in seen:raise ValueError('Duplicate test binding')
            seen.add(binding['testId'])
            if binding['testId'] not in tests:raise ValueError('Unknown test identity')
            if binding['dimension'] not in v['dimensions']:raise ValueError('Unknown API dimension')
            if binding['evidenceStatus'] not in ('BEHAVIOR_SUPPORTED','PARTIALLY_SUPPORTED'):raise ValueError('Invalid assessed status')
            cs=[claims[c] for c in binding['claimIds']]
            if not any(c['kind']=='assertion' and any(sources[x['sourceId']]['origin']=='local' for x in c['citations']) for c in cs):raise ValueError('Test association needs cited assertion evidence')
            if not binding.get('reason'):raise ValueError('Test association needs rationale')
        for helper in v.get('helpers',[]):
            if helper['sourceId'] not in sources:raise ValueError('Unknown helper source')
            if not 1<=helper['startLine']<=helper['endLine']<=len(sources[helper['sourceId']]['text'].splitlines()):raise ValueError('Invalid helper range')
    return sources,claims
