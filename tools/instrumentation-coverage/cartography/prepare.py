#!/usr/bin/env python3
"""Copy unmodified selected upstream source; pin every copied file and observed artifact."""
import argparse,hashlib,json,shutil
from pathlib import Path
HERE=Path(__file__).resolve().parent
TESTS=['DeferredResultReturnValueHandlerTests','StreamingResponseBodyReturnValueHandlerTests','ResponseBodyEmitterTests','SseEmitterTests','HandlerExecutionChainTests','WebAsyncManagerTests','WebAsyncManagerTimeoutTests','WebAsyncManagerErrorTests','RequestResponseBodyMethodProcessorMockTests','HttpEntityMethodProcessorTests']
a=argparse.ArgumentParser();a.add_argument('--source',type=Path,required=True);a.add_argument('--graph',type=Path,required=True);args=a.parse_args()
build=HERE/'build'; dest=build/'upstream-src';dest.mkdir(parents=True,exist_ok=True)
files=[];classes=[]
for name in TESTS:
 found=list(args.source.glob('*/src/test/java/**/'+name+'.java'))
 if len(found)!=1:raise ValueError((name,found))
 p=found[0];relative=str(p).split('/src/test/java/')[1];classes.append(relative[:-5].replace('/','.'));files.append((p,relative))
fixture=args.source/'spring-web/src/testFixtures/java'
for part in ['org/springframework/web/testfixture/servlet','org/springframework/web/testfixture/method']:
 for p in sorted((fixture/part).rglob('*.java')):files.append((p,str(p.relative_to(fixture))))
pin=json.loads((HERE.parent/'research/spring-webmvc-test-led/source-lock.json').read_text())['files']
manifest=[]
for p,relative in files:
 key=str(p.relative_to(args.source))
 if '/src/test/java/' in key and hashlib.sha256(p.read_bytes()).hexdigest()!=pin.get(key):raise ValueError('Test source differs from pinned v6.0.2: '+key)
 target=dest/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
 manifest.append({'source':str(p.relative_to(args.source)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
graph=json.loads(args.graph.read_text())
artifacts={a['coordinate']:a for a in graph['artifacts']}
assert 'spring-webmvc-6.0.2' in artifacts
owners=sorted({m['owner'].replace('/','.') for m in graph['definedMethods'] if m['artifact'] in ['spring-web-6.0.2','spring-webmvc-6.0.2']})
(build/'observe-classes.txt').write_text('\n'.join(owners)+'\n');(build/'test-classes.txt').write_text('\n'.join(classes)+'\n')
(build/'source-manifest.json').write_text(json.dumps({'sourceRevision':'v6.0.2','files':manifest,'graphSha256':hashlib.sha256(args.graph.read_bytes()).hexdigest(),'artifacts':graph['artifacts'],'testClasses':classes},indent=2)+'\n')
print('Prepared',len(classes),'test classes;',len(owners),'observed library types')
