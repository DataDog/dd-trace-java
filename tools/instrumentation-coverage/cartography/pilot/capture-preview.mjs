import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,writeFile,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const output=resolve(process.argv[2] || 'tools/instrumentation-coverage/cartography/build/portal-preview');
const expected=JSON.parse(await readFile(join(output,'report.json'),'utf8'));
const profile=await mkdtemp(join(tmpdir(),'cartography-browser-'));
const browser=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--disable-gpu','--disable-background-networking','--disable-component-update','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:['ignore','ignore','pipe']});
let socket;
try {
 const endpoint=await new Promise((resolve,reject)=>{let stderr='';const timer=setTimeout(()=>reject(Error('Startup timeout')),20000);browser.stderr.on('data',chunk=>{stderr+=chunk;const match=stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(match){clearTimeout(timer);resolve(match[1]);}});browser.on('error',reject);});
 const pages=await (await fetch(endpoint.replace('ws:','http:').replace(/\/devtools\/browser\/.*/,'/json/list'))).json();
 socket=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise(r=>socket.addEventListener('open',r,{once:true}));
 let serial=0;const pending=new Map();socket.addEventListener('message',e=>{const d=JSON.parse(e.data);if(d.id){const p=pending.get(d.id);pending.delete(d.id);d.error?p.reject(d.error):p.resolve(d.result);}});
 const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++serial;const timer=setTimeout(()=>{pending.delete(id);reject(Error('Browser command timed out: '+method));},45000);pending.set(id,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});socket.send(JSON.stringify({id,method,params}));});
 const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
 await send('Emulation.setDeviceMetricsOverride',{width:1600,height:1000,deviceScaleFactor:1,mobile:false});
 await send('Page.navigate',{url:pathToFileURL(join(output,'index.html')).href});
 for(let i=0;i<150;i++){if(await evaluate("document.querySelectorAll('.row').length>0"))break;await new Promise(r=>setTimeout(r,100));}
 assert.equal(await evaluate("document.getElementById('error').textContent"),'');
 assert.equal(await evaluate("document.querySelectorAll('.row').length"),expected.variants.length);
 assert.equal(await evaluate("!!document.getElementById('test-select')"),!!(expected.variants[0].associations.length+expected.variants[0].similarityCandidates.length));
 assert.equal(await evaluate("document.getElementById('details').open"),false);
 assert.equal(await evaluate("document.querySelectorAll('.methods').length"),0);
 assert.ok(await evaluate("document.getElementById('assessment').textContent.length>0"));
 assert.equal(await evaluate("document.getElementById('library-title').textContent"),expected.library+' '+expected.version);
 assert.equal(await evaluate("document.querySelectorAll('.brand-mark svg').length"),1);
 assert.equal(await evaluate("document.querySelectorAll('.assessment svg').length"),0);
 const inventory=new Map();
 const ids=v=>[...new Set([...v.associations,...v.similarityCandidates].map(t=>t.testId))];
 const sums=(v,m)=>ids(v).reduce((n,id)=>{const x=v.methods[m]?.observable?v.methods[m].tests[id]:null;return{context:n.context+(x?.context||0),root:n.root+(x?.root||0)};},{context:0,root:0});
 for(const v of expected.variants)for(const m of new Set(v.references.flatMap(r=>r.methods))){const n=sums(v,m);inventory.set(m,inventory.get(m)||n.context+n.root>0);}
 const hit=[...inventory.values()].filter(Boolean).length,total=inventory.size;
 const declared=expected.variants.filter(v=>!v.id.startsWith('candidate.'));
 const verified=declared.filter(v=>v.associations.some(t=>t.evidenceStatus==='BEHAVIOR_SUPPORTED')).length;
 assert.equal(await evaluate("document.querySelector('.coverage-ring span').textContent"),declared.length?(verified===declared.length?'100':Math.min(99.9,100*verified/declared.length).toFixed(1))+'%':'—');
 assert.equal(await evaluate("!!document.getElementById('catalog-scope')"),false);
 assert.equal(await evaluate("document.getElementById('assessment').textContent.includes('Catalog scope unresolved')"),false);
 await mkdir(join(output,'tasks'),{recursive:true});
 await evaluate("window.blob=null;const original=URL.createObjectURL;URL.createObjectURL=b=>{window.blob=b;return original(b);};document.addEventListener('click',e=>{if(e.target.matches('a[download]'))e.preventDefault();});");
 const shot=await send('Page.captureScreenshot',{format:'png'});await writeFile(join(output,'overview.png'),Buffer.from(shot.data,'base64'));
 for(let i=0;i<expected.variants.length;i++){
  const v=expected.variants[i];await evaluate(`document.querySelectorAll('.row')[${i}].click();document.getElementById('download-task').click()`);
  const task=await evaluate("window.blob.text().then(JSON.parse)");assert.equal(task.variant.id,v.id);assert.equal(task.reportIdentity,expected.reportIdentity);
  await writeFile(join(output,'tasks',`${i}-investigation.json`),JSON.stringify(task,null,2)+'\n');
  assert.deepEqual(task.reference.methods,[...new Set(v.references.flatMap(r=>r.methods))]);
  assert.equal(await evaluate("document.querySelectorAll('.methods').length"),0);
  if(await evaluate("document.querySelectorAll('.stage').length>0")){
   await evaluate("document.querySelector('.stage').click();document.getElementById('download-task').click()");
   const focused=await evaluate("window.blob.text().then(JSON.parse)");
   const shown=await evaluate("Array.from(document.querySelectorAll('.method')).map(m=>m.dataset.method)");
   assert.deepEqual(shown,focused.stageFocus.methods);assert.ok(shown.length>0);
   assert.ok(shown.every(m=>task.reference.methods.includes(m)));
   assert.ok(await evaluate("Array.from(document.querySelectorAll('.method strong')).every(e=>!/[;()]/.test(e.textContent))"));
   await evaluate("document.getElementById('close-methods').click()");
   assert.equal(await evaluate("document.querySelectorAll('.methods').length"),0);
  }
  await evaluate("document.getElementById('all-methods').click()");
  assert.equal(await evaluate("document.querySelectorAll('.method').length"),task.reference.methods.length);
  await evaluate("document.getElementById('all-methods').click()");

  assert.equal(task.selectedTest,null);assert.equal(task.investigationScope,'aggregate');
  assert.deepEqual([...task.contributingTestIds].sort(),ids(v).sort());
  for(const e of task.methodEvidence){const n=sums(v,e.method);assert.equal(e.context,n.context);assert.equal(e.root,n.root);assert.equal(e.state,!v.methods[e.method]?.observable?'unknown':n.context?'context':n.root?'root':'unobserved');}
  if(ids(v).length){
   await evaluate("{const s=document.getElementById('test-select');s.selectedIndex=1;s.dispatchEvent(new Event('change'));document.getElementById('download-task').click();}");
   const single=await evaluate("window.blob.text().then(JSON.parse)");assert.equal(single.investigationScope,'selected-test');
   for(const e of single.methodEvidence){const n=v.methods[e.method]?.observable?(v.methods[e.method].tests[single.selectedTest.testId]||{context:0,root:0}):{context:0,root:0};assert.equal(e.context,n.context);assert.equal(e.root,n.root);assert.equal(e.state,!v.methods[e.method]?.observable?'unknown':n.context&&n.root?'mixed':n.context?'context':n.root?'root':'unobserved');}
   await evaluate("document.getElementById('test-select').value='';document.getElementById('test-select').dispatchEvent(new Event('change'))");
   assert.equal(await evaluate("document.querySelectorAll('.methods').length"),0);
  }
  assert.equal(await evaluate("document.getElementById('error').textContent"),'');
 }
 const testVariant=expected.variants.findIndex(v=>v.associations.length||v.similarityCandidates.length);
 if(testVariant>=0){
 await evaluate(`document.querySelectorAll('.row')[${testVariant}].click();const s=document.getElementById('test-select');s.selectedIndex=s.options.length-1;s.dispatchEvent(new Event('change'));document.getElementById('download-task').click()`);
 const switched=await evaluate("window.blob.text().then(JSON.parse)");assert.equal(switched.selectedTest.testId,await evaluate("document.getElementById('test-select').value"));
 await evaluate("const refSelect=document.getElementById('reference-select');refSelect.selectedIndex=refSelect.options.length-1;refSelect.dispatchEvent(new Event('change'));document.getElementById('download-task').click()");
 const ref=await evaluate("window.blob.text().then(JSON.parse)");assert.equal(ref.reference.name,expected.variants[testVariant].references.at(-1).name);
 }
 await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
 assert.ok(await evaluate("document.documentElement.scrollWidth<=window.innerWidth"));
 // Exercise the actual file picker in the standalone shared viewer, not only embedded snapshots.
 await send('Page.navigate',{url:pathToFileURL(resolve('tools/instrumentation-coverage/portal/index.html')).href});
 for(let i=0;i<100;i++){if(await evaluate("!!document.getElementById('report-file')"))break;await new Promise(r=>setTimeout(r,50));}
 for(const directory of [output,...(process.argv[3]?[resolve(process.argv[3])]:[])]){
  const data=JSON.parse(await readFile(join(directory,'report.json'),'utf8'));
  const doc=await send('DOM.getDocument');const input=await send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'#report-file'});
  await send('DOM.setFileInputFiles',{nodeId:input.nodeId,files:[join(directory,'report.json')]});
  for(let i=0;i<100;i++){if(await evaluate(`document.getElementById('library-title').textContent===${JSON.stringify(data.library+' '+data.version)}`))break;await new Promise(r=>setTimeout(r,50));}
  assert.equal(await evaluate("document.getElementById('library-title').textContent"),data.library+' '+data.version);
  assert.equal(await evaluate("document.querySelectorAll('.row').length"),data.variants.length);
  assert.equal(await evaluate("document.getElementById('error').textContent"),'');
  assert.ok(await evaluate("document.documentElement.scrollWidth<=window.innerWidth"));
 }
 console.log('Pharos preview passed: shared file loading,  assessment, hidden methods, stage drilldown and reset, aggregate union, per-test isolation, all behaviors, exact download evidence, reference/test switching, mobile width.');
} finally {socket?.close();browser.kill();}
