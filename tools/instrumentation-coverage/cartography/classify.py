#!/usr/bin/env python3
"""Deterministic exploratory clustering and partial-reference matching. Scores are not probabilities."""
import argparse, collections, hashlib, importlib.util, json, math, re
from pathlib import Path
import semantics
import accounting
import task_plans

HERE=Path(__file__).resolve().parent
TOOL=HERE.parent
spec=importlib.util.spec_from_file_location('coverage_join',TOOL/'scripts/join.py')
join=importlib.util.module_from_spec(spec);spec.loader.exec_module(join)

def dump(path,data):path.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
def normalize(method):return join.normalize_observed_method(method)
def weights(vectors):
    counts=collections.Counter(m for v in vectors for m in v)
    return {m:1+math.log((len(vectors)+1)/(n+1)) for m,n in counts.items()}
def jaccard(a,b,w):
    union=a|b
    return sum(w.get(m,1) for m in sorted(a&b))/sum(w.get(m,1) for m in sorted(union)) if union else 0

def cluster(vectors,w,threshold=.7):
    """Complete-link: every pair in a family has weighted Jaccard >= threshold."""
    ids=sorted(vectors);similarities={(a,b):jaccard(vectors[a],vectors[b],w) for a in ids for b in ids if a<b}
    groups=[(i,) for i in ids]
    while True:
        best=None
        for i,a in enumerate(groups):
            for j in range(i+1,len(groups)):
                b=groups[j]
                score=min(similarities[tuple(sorted((x,y)))] for x in a for y in b)
                if score>=threshold and (best is None or score>best[0]):best=(score,i,j)
        if best is None:return groups
        _,i,j=best;merged=tuple(sorted(groups[i]+groups[j]));groups=[g for n,g in enumerate(groups) if n not in (i,j)]+[merged];groups.sort()

def match(reference,target,inventory,w):
    projected=reference & inventory
    shared=projected & target
    all_weight=sum(w.get(m,1) for m in sorted(reference))
    weight=sum(w.get(m,1) for m in sorted(projected))
    recall=sum(w.get(m,1) for m in sorted(shared))/weight if weight else 0
    # Keep visibility of the reference separate from similarity on available evidence.
    availability=weight/all_weight if all_weight else 0
    return dict(score=recall*(1-math.exp(-len(shared)/3)),referenceRecall=recall,referenceAvailability=availability,sharedCount=len(shared),sharedMethods=sorted(shared),missingObservableMethods=sorted(projected-target),unknownReferenceMethods=sorted(reference-inventory))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reference',type=Path,required=True);p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--run',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--interpretation',type=Path,default=HERE/'spring-6.0.2-interpretation.json')
    args=p.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=True)
    reference=json.loads(args.reference.read_text());baseline=json.loads(args.baseline.read_text())
    if not reference['installed'] or reference['errors']:raise ValueError('Unhealthy reference recording')
    baseline_counts=next(iter(baseline['outcomes'].values()))
    if baseline_counts['found'] == 0:raise ValueError('Empty baseline')
    baseline_ids={x['id'] for x in baseline['windows']}
    for r in (reference,baseline):
        if any(x['failed'] or x['containerFailed'] or x['passed']!=baseline_counts['passed'] or x['found']!=baseline_counts['found'] for x in r['outcomes'].values()):raise ValueError('Reference outcome mismatch')
        if {x['id'] for x in r['windows']} != baseline_ids:raise ValueError('Test identity mismatch')
    manifest=json.loads((args.run/'manifest.json').read_text())
    if manifest['status'] not in ('collected','reported'):raise ValueError('Our collection did not succeed')
    expected=manifest.get('evidenceHashes',{})
    actual={str(p.relative_to(args.run)) for area in ('knowledge','graph','observations','test-results') for p in (args.run/area).rglob('*') if p.is_file()} | {'resolved.json','graph-identity.json'}
    if actual != set(expected):raise ValueError('Agent run evidence inventory changed')
    for relative,digest in expected.items():
        if hashlib.sha256((args.run/relative).read_bytes()).hexdigest()!=digest:raise ValueError('Agent evidence changed: '+relative)
    raw=json.loads((args.run/'graph/raw-graph.json').read_text())
    jars={x['file']:x['sha256'] for x in json.loads(reference['runtimeManifest'])}
    for artifact in raw['artifacts']:
        name=Path(artifact['file']).name
        if name in jars and jars[name]!=artifact['sha256']:raise ValueError('Reference artifact mismatch: '+name)
    for name in ['spring-web-6.0.2.jar','spring-webmvc-6.0.2.jar']:
        if name not in jars:raise ValueError('Missing reference artifact '+name)
    defined={m['id'] for m in raw['definedMethods']}
    grouped=collections.defaultdict(list)
    for window in reference['windows']:grouped[window['id']].append(window)
    vectors={};held={};labels={};stability={}
    for id,runs in sorted(grouped.items()):
        runs.sort(key=lambda r:r['repetition'])
        if len(runs)!=3:raise ValueError('Three independent invocation recordings required')
        training=[{normalize(m) for m in r['methods']} & defined for r in runs[:2]]
        vectors[id]=set.intersection(*training)
        held[id]={normalize(m) for m in runs[2]['methods']} & defined
        labels[id]=runs[0]['display'];stability[id]=len(training[0]&training[1])/len(training[0]|training[1]) if training[0]|training[1] else 1
    w=weights(vectors.values());groups=cluster(vectors,w)
    families=[];assignment={}
    for members in groups:
        id='reference.'+hashlib.sha256('\n'.join(members).encode()).hexdigest()[:12]
        medoid=min(members,key=lambda a:(-sum(jaccard(vectors[a],vectors[b],w) for b in members),a))
        union=set().union(*(vectors[x] for x in members))
        support=collections.Counter(m for x in members for m in vectors[x])
        family=dict(id=id,memberIds=list(members),representativeId=medoid,representative=labels[medoid],methods=sorted(union),methodSupport={m:n/len(members) for m,n in sorted(support.items())})
        family['examples']=[dict(name=labels[t],testId=t) for t in members]
        family['referenceNesting']=dict(sorted(grouped[medoid][1]['edges'].items()))
        families.append(family)
        for m in members:assignment[m]=id
    # Held-out repetitions check repeatability, not semantic labeling accuracy.
    repeat_matches=[]
    for id,v in held.items():
        ranked=sorted(((max(jaccard(v,vectors[t],w) for t in f['memberIds']),f['id']) for f in families),reverse=True)
        repeat_matches.append(dict(test=labels[id],expectedFamily=assignment[id],topFamily=ranked[0][1],score=ranked[0][0],correct=ranked[0][1]==assignment[id]))
    observations,reports,inventory=join.load_observations(args.run/'observations')
    if not reports or any(not r['finalized'] or r['health'].get('errors') or r['health'].get('droppedObservations') for r in reports):raise ValueError('Unhealthy agent-enabled collection')
    ours=collections.defaultdict(set);names={};test_metadata={};attribution=collections.defaultdict(set)
    for method,obs in observations.items():
        for id,test in obs['tests'].items():
            if test['name']=='<unattributed>':continue
            test_metadata[id]={'target':test.get('target') or 'test','suite':test['source']}
            ours[id].add(method);names[id]=test['name'];attribution[id].update(test['attributionConfidence'])
    assignments=[];selected=collections.defaultdict(dict)
    for id,target in sorted(ours.items()):
        ranks=[]
        for f in families:
            candidates=[(match(vectors[t],target,set(inventory),w),t) for t in f['memberIds']]
            candidate,refid=max(candidates,key=lambda x:(x[0]['score'],x[0]['referenceAvailability'],x[1]))
            ranks.append(dict(flowId=f['id'],referenceTest=labels[refid],**candidate))
        ranks.sort(key=lambda x:(-x['score'],x['flowId']))
        eligible=[r for r in ranks if r['score']>=.70 and r['sharedCount']>=4 and r['referenceAvailability']>=.35]
        if not eligible:status='UNMATCHED';accepted=[]
        else:
            accepted=[r for r in eligible if r['score']>=eligible[0]['score']-.05][:3]
            status='SINGLE_CANDIDATE' if len(accepted)==1 else 'AMBIGUOUS_OR_MULTIPLE'
        for r in accepted:selected[r['flowId']][id]=r
        assignments.append(dict(testId=id,name=names[id],**test_metadata[id],status=status,attributionConfidence=sorted(attribution[id]),candidateFlowIds=[r['flowId'] for r in accepted],topCandidates=ranks[:5]))
    evidence=semantics.packet(families)
    dump(out/'naming-evidence.json',evidence)
    if not args.interpretation.exists():
        raise ValueError('Naming packet written. Follow NAMING.md to create an interpretation before rendering.')
    annotation=json.loads(args.interpretation.read_text())
    interpreted=semantics.interpret(evidence,annotation)
    source_refs=[];flows=[]
    for f in families:
        meaning=interpreted[f['id']]
        sourceIds=[]
        for example in meaning['examples']:
            sid=hashlib.sha256(example['testId'].encode()).hexdigest()[:12]
            source_refs.append(dict(id=sid,kind='upstream-test',url=example['url'],claim='Pinned source body supporting an LLM interpretation; not a verified scenario label.'))
            sourceIds.append(sid)
        steps=[dict(id='m'+str(i),kind='library-method',anchor=m) for i,m in enumerate(f['methods'])]
        flows.append(dict(id=f['id'],feature=meaning['feature'],variant=meaning['name'],outcome=f"{len(f['memberIds'])} upstream examples · interpretation draft",status=meaning['status'],sourceIds=sourceIds,steps=steps,identification={'allOf':[],'anyOf':[x['id'] for x in steps],'noneOf':[]},prerequisiteSteps=[],completion={'allOf':[],'anyOf':[],'optional':[x['id'] for x in steps]}))
    flows.sort(key=lambda f:(f['feature'],f['variant']))
    coordinates=json.loads((args.run/'resolved.json').read_text())['artifacts']
    coordinate=next(a['coordinate'] for a in coordinates if ':spring-webmvc:' in a['coordinate'])
    library,version=coordinate.rsplit(':',1)
    learned=dict(schemaVersion=2,library=library,version=version,sources=source_refs,flowCatalog={'status':'llm-interpreted-source-backed-draft','scope':'Execution families from selected upstream tests, named from their source bodies and assertions. Landmark groups are conceptual, not an execution sequence. Similarity associations are hypotheses.','exclusions':['Unselected upstream behavior','causal async attribution','verified semantic labels','probability calibration']},flows=flows)
    dump(out/'learned-flows.json',learned)
    original=join.tests_for_flow
    def statistical_tests(flow,obs,selected_methods):
        possible=original(flow,obs,selected_methods)
        return [{**t,'statisticalMatch':selected[flow['id']][t['id']]} for t in possible if t['id'] in selected[flow['id']]]
    join.tests_for_flow=statistical_tests
    try:
        result=join.analyze(raw,learned,observations,reports,inventory,{},None,join.load_test_results(args.run/'test-results'))
    finally:
        join.tests_for_flow=original
    result['namingProvenance']={**annotation['provenance'],'packetSha256':semantics.digest(evidence),'interpretationSha256':semantics.digest(annotation)}
    result['staticInventory']={'artifacts':len(raw['artifacts']),'definedMethods':len(raw['definedMethods']),'dynamicCallSites':len(raw.get('dynamicCallSites',[]))}
    for flow in result['flows']:
        meaning=interpreted[flow['id']]
        family=next(f for f in families if f['id']==flow['id'])
        flow['actions']=[];flow.pop('exercise',None)
        flow['semanticInterpretation']=meaning
        flow['statisticalFamily']=family
        stages=[]
        for stage in meaning['stages']:
            matches=stage['methods']
            stages.append(dict(id=stage['id'],label=stage['label'],kind='library-method',anchor='',matches=matches,bindingStatus='RESOLVED',runtimeEvidence=join.state_for(matches,observations,set(inventory))))
            for method in flow['expectedMethods']:
                if method['methodId'] in matches:
                    method['stepIds']=[stage['id']]
                    method['referenceSupport']={'observed':sum(method['methodId'] in vectors[t] for t in family['memberIds']),'total':len(family['memberIds'])}
        flow['steps']=stages;flow['transitions']=[]
        flow['runtimeFlowStatus']='STATISTICAL_CANDIDATES' if flow['tests'] else 'NO_STATISTICAL_MATCH'
    result['actions']=[];result['taskBundles']={}
    for flow in result['flows']:
        task=task_plans.build(flow,vectors,w,dict(module=manifest['module'],library=library,version=version,runId=manifest['runId'],requestedTasks=['allTests']))
        flow['testPlanId']=task['taskId']
        result['taskBundles'][task['taskId']]=task
        flow['actions']=[dict(id=task['taskId'],kind=task['kind'],downloadFilename=task['downloadFilename'])]
        result['actions'].extend(flow['actions'])
    evaluation=dict(referenceTests=len(vectors),recordedInvocations=len(reference['windows']),families=len(families),upstreamOutcomes=reference['outcomes'],baselineOutcomes=baseline['outcomes'],trainingStability=stability,heldOutRepeatMatches=repeat_matches,heldOutSameFamily=sum(x['correct'] for x in repeat_matches),classificationCounts=dict(collections.Counter(a['status'] for a in assignments)),classificationTests=len(assignments),referenceDistinctMethods=len(set().union(*vectors.values())),commonInventoryMethods=len(set().union(*vectors.values()) & set(inventory)),ourObservationMethods=len(inventory),referenceTransformationErrors=reference['errors'],temporalWorkerEntries=sum(r['temporalWorkerEntries'] for r in reference['windows']))
    result['statisticalAttribution']=dict(evaluation=evaluation,assignments=assignments,families=families,algorithm={'representation':'Method presence, independent of entry counts and Context state; synchronous edge recordings retained for inspection, not cross-domain matching.','clustering':'Complete-link weighted Jaccard >= 0.70; training uses intersections of repetitions 0 and 1.','matching':'IDF weighted reference containment projected to target inventory, multiplied by 1-exp(-shared/3).','thresholds':{'score':.70,'minimumSharedMethods':4,'minimumReferenceAvailability':.35,'ambiguityBand':.05},'interpretation':'Exploratory, uncalibrated similarity. Thresholds fixed before target inspection; not tuned on ground-truth scenario labels. Held-out repetition measures repeatability only.'})
    result['statisticalAttribution']['testAccounting']=accounting.reconcile(args.run,assignments)
    result['experimentProvenance']={'agentRun':str(args.run.resolve()),'referenceSha256':hashlib.sha256(args.reference.read_bytes()).hexdigest(),'baselineSha256':hashlib.sha256(args.baseline.read_bytes()).hexdigest(),'graphSha256':hashlib.sha256((args.run/'graph/raw-graph.json').read_bytes()).hexdigest(),'classifierSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'semanticsSha256':hashlib.sha256((HERE/'semantics.py').read_bytes()).hexdigest(),'interpretationSha256':semantics.digest(annotation)}
    dump(out/'classification.json',result['statisticalAttribution']);dump(out/'report.json',result)
    (out/'joined-report.html').write_text(join.html(result))
    print(json.dumps({k:v for k,v in evaluation.items() if k not in ['heldOutRepeatMatches','trainingStability']},indent=2))
if __name__=='__main__':main()
