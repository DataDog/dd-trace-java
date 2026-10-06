#!/usr/bin/env python3
"""Run the isolated Spring experiment from a supplied, unmodified v6.0.2 source checkout."""
import argparse,json,re,subprocess,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
MODULE=Path('dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0')

def execute(*args,log):
    with log.open('w') as out:
        subprocess.run([str(a) for a in args],cwd=ROOT,stdout=out,stderr=subprocess.STDOUT,check=True)
    return log.read_text()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);args=p.parse_args()
    build=HERE/'build';build.mkdir(exist_ok=True)
    graph_log=execute(sys.executable,HERE.parent/'workflow.py','graph','--module',MODULE,log=build/'graph.log')
    graph=Path(re.search(r'^Graph: (.+)$',graph_log,re.M)[1])
    execute(sys.executable,HERE/'prepare.py','--source',args.source.resolve(),'--graph',graph,log=build/'prepare.log')
    execute(ROOT/'gradlew','-p',HERE,'referenceRun','-Pbaseline','-Prepeats=1','-Poutput=build/baseline.json','--console=plain',log=build/'baseline.log')
    execute(ROOT/'gradlew','-p',HERE,'referenceRun','-Prepeats=3','--console=plain',log=build/'reference.log')
    reference=json.loads((build/'reference.json').read_text())
    if reference['errors']:raise ValueError('Upstream transformation failed')
    path=ROOT/MODULE/'coverage/observation.json';observation=json.loads(path.read_text())
    observed={m[:m.rfind('.',0,m.index('('))] for w in reference['windows'] for m in w['methods']}
    observation['classes']=sorted(set(observation['classes'])|observed)
    observation['maxDistinctObservations']=250000
    path.write_text(json.dumps(observation,indent=2)+'\n')
    run_log=execute(sys.executable,HERE.parent/'workflow.py','run','--module',MODULE,log=build/'agent-tests.log')
    run=Path(re.search(r'^Report data: (.+)$',run_log,re.M)[1]).parents[1]
    execute(sys.executable,HERE/'classify.py','--reference',build/'reference.json','--baseline',build/'baseline.json','--run',run,'--output',build/'report',log=build/'classification.log')
    print('Graph:',graph);print('Agent run:',run);print('Report:',build/'report/joined-report.html')
if __name__=='__main__':main()
