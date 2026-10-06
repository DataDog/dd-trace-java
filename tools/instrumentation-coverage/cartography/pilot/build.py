#!/usr/bin/env python3
"""Build a bounded, source-pinned behavior pilot without modifying the existing report."""
import argparse,base64,collections,hashlib,json,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
BASE=HERE.parent
ROOT=BASE.parents[2]
sys.path.insert(0,str(BASE))
from classify import normalize
from evidence_model import validate, digest


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def build(report,packet,reference,assessment):
    source_map,claims=validate(assessment,report,packet,ROOT)
    sources=[{k:v for k,v in source.items() if k!='text'} for source in source_map.values()]
    upstream={e['name']:{**e,'familyId':f['id']} for f in packet['families'] for e in f['examples']}
    recording=collections.defaultdict(list)
    for window in reference['windows']:
        if window['repetition'] in (0,1):recording[window['id']].append({normalize(m) for m in window['methods']})
    inventory={m['id']:m for m in report['methodInventory']}
    tests=report['statisticalAttribution']['testAccounting']['tests']
    variants=[]
    for spec in assessment['variants']:
        examples=[]
        for name in spec['upstream']:
            e=upstream[name];runs=recording[e['testId']]
            if len(runs)!=2:raise ValueError('Missing stable reference repetitions')
            methods=sorted(set.intersection(*runs))
            helpers=[]
            for helper in spec.get('helpers',[]):
                src=source_map[helper['sourceId']]
                helpers.append({'name':helper['name'],'body':'\n'.join(src['text'].splitlines()[helper['startLine']-1:helper['endLine']])})
            examples.append({**e,'methods':methods,'scope':spec['referenceScopes'][name],'helpers':helpers})
        test_index={t['testId']:t for t in tests}
        associations=[]
        for binding in spec['testBindings']:
            test=test_index[binding['testId']]
            associations.append(dict(testId=test['testId'],name=test['name'],target=test['target'],suite=test['suite'],outcome=test['outcome'],dimension=binding['dimension'],evidenceStatus=binding['evidenceStatus'],reviewStatus=assessment['reviewStatus'],basis=binding['reason'],claimIds=binding['claimIds']))
        supported={t['testId'] for t in associations}
        families={e['familyId'] for e in examples}
        candidates=[dict(testId=t['testId'],name=t['name'],target=t['target'],suite=t['suite'],outcome=t['outcome'],evidenceStatus='SIMILAR_EXECUTION_ONLY') for t in tests if set(t.get('candidateFlowIds',[]))&families and t['testId'] not in supported]
        methods=set(m for e in examples for m in e['methods'])
        all_ids={t['testId'] for t in associations+candidates}
        evidence={m:{'observable':m in inventory,'tests':{tid:{'context':v.get('context',0),'root':v.get('root',0)} for tid,v in inventory.get(m,{}).get('tests',{}).items() if tid in all_ids}} for m in sorted(methods)}
        variants.append({**spec,'references':examples,'associations':associations,'similarityCandidates':candidates,'methods':evidence,'reviewStatus':assessment['reviewStatus'],'evidenceStatus':'BEHAVIOR_SUPPORTED' if any(t['evidenceStatus']=='BEHAVIOR_SUPPORTED' for t in associations) else 'PARTIALLY_SUPPORTED' if associations else 'NOT_ASSESSED','dimensions':[{'name':d,'status':'BEHAVIOR_SUPPORTED' if any(t['dimension']==d and t['evidenceStatus']=='BEHAVIOR_SUPPORTED' for t in associations) else 'PARTIALLY_SUPPORTED' if any(t['dimension']==d for t in associations) else 'NOT_ASSESSED'} for d in spec['dimensions']]})
    return dict(schemaVersion=1,library=report['library'],version=report['version'],runCoverage=run_coverage(report),capability=assessment['capability'],variants=variants,sources=sources,claims=list(claims.values()),limitations=assessment['limitations'],reviewStatus=assessment['reviewStatus'],testExecution=report['testExecution'],provenance={'run':report['experimentProvenance'],'assessmentSha256':digest(assessment),'inputIdentity':assessment['inputIdentity']},defaultTask='INVESTIGATE_TEST_EVIDENCE')


def run_coverage(report):
    counts=dict(context=0,root=0,mixed=0,unobserved=0)
    for method in report['methodInventory']:
        context=sum(t.get('context',0) for t in method.get('tests',{}).values())
        root=sum(t.get('root',0) for t in method.get('tests',{}).values())
        counts['mixed' if context and root else 'context' if context else 'root' if root else 'unobserved']+=1
    return dict(counts,inventory=len(report['methodInventory']),observed=counts['context']+counts['root']+counts['mixed'],static=report['staticInventory'],scope='Counts aggregate recorded test windows across the saved run. The collection inventory is a subset of the static scope; neither is complete behavioral coverage.')


def add_stages(data):
    groups=json.loads((HERE/'stage-map.json').read_text())
    for v in data['variants']:
        capability=v.get('capability',data.get('capability',{})).get('id')
        group='body' if capability=='request-body-binding' else 'async' if v['id'] in ('value','error','deferred-later','timeout','cleanup') else None
        if group:
            v['stages']=[dict(id=s['id'],name=s['name'],methods=sorted(m for m in v['methods'] if m.split('(')[0].split('/')[-1] in s['keys'])) for s in groups[group]]
    return data


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--assessment',type=Path,default=HERE/'assessment.json');parser.add_argument('--additional-assessment',type=Path,action='append',default=[]);parser.add_argument('--naming-evidence',type=Path,default=BASE/'build/naming-evidence.json');parser.add_argument('--reference',type=Path,default=BASE/'build/reference.json');parser.add_argument('--report',type=Path,default=BASE/'build/report/report.json');parser.add_argument('--output',type=Path,default=BASE/'build/behavior-pilot');parser.add_argument('--portal',action='store_true');args=parser.parse_args()
    saved_report=json.loads(args.report.read_text())
    if sha(args.reference)!=saved_report['experimentProvenance']['referenceSha256']:raise ValueError('Reference recording differs from report')
    data=build(json.loads(args.report.read_text()),json.loads(args.naming_evidence.read_text()),json.loads(args.reference.read_text()),json.loads(args.assessment.read_text()))
    data['assessments']=[data['provenance']]
    for v in data['variants']:
        v.update(capability=data['capability'],sourceEvidence=data['sources'],claimEvidence=data['claims'])
    for path in args.additional_assessment:
        extra=build(saved_report,json.loads(args.naming_evidence.read_text()),json.loads(args.reference.read_text()),json.loads(path.read_text()))
        for v in extra['variants']:
            if any(existing['id']==v['id'] for existing in data['variants']):raise ValueError('Duplicate behavior ID')
            v.update(capability=extra['capability'],sourceEvidence=extra['sources'],claimEvidence=extra['claims'])
            data['variants'].append(v)
        data['assessments'].append(extra['provenance'])
        data['limitations']=list(dict.fromkeys(data['limitations']+extra['limitations']))
    if args.additional_assessment:data['capability']={'id':'combined-behavior-catalog','name':data['library']+' · instrumentation coverage'}
    add_stages(data)
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'report.json').write_text(json.dumps(data,indent=2)+'\n')
    encoded=base64.b64encode(json.dumps(data).encode()).decode()
    (args.output/'index.html').write_text((HERE/'viewer.html').read_text().replace('__PILOT_DATA__',encoded))
    if args.portal:
        sys.path.insert(0,str(BASE.parent))
        from pharos import render
        render(data,args.output,Path(saved_report['experimentProvenance']['agentRun']))
    print('Pilot:',args.output/'index.html')
    print('Variants:',[(v['id'],len(v['associations']),len(v['similarityCandidates'])) for v in data['variants']])
if __name__=='__main__':main()
