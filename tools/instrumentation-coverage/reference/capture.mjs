import {spawn} from 'node:child_process';
import {mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const output=resolve(process.argv[2]);
const expected=JSON.parse(await readFile(join(output,'report.json'),'utf8'));
const portal=JSON.parse(await readFile(join(output,'portal-report.json'),'utf8'));
const profile=await mkdtemp(join(tmpdir(),'pharos-reference-browser-'));
const browser=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--disable-gpu','--disable-background-networking','--no-first-run','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:['ignore','ignore','pipe']});
let socket;
try{
 const endpoint=await new Promise((resolve,reject)=>{let stderr='';const timer=setTimeout(()=>reject(Error('Startup timeout')),15000);browser.stderr.on('data',chunk=>{stderr+=chunk;const match=stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(match){clearTimeout(timer);resolve(match[1]);}});browser.on('error',reject)});
 const pages=await(await fetch(endpoint.replace('ws:','http:').replace(/\/devtools\/browser\/.*/,'/json/list'))).json();
 socket=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise(r=>socket.addEventListener('open',r,{once:true}));
 let serial=0;const pending=new Map();socket.addEventListener('message',event=>{const data=JSON.parse(event.data);if(data.id){const p=pending.get(data.id);pending.delete(data.id);data.error?p.reject(data.error):p.resolve(data.result)}});
 const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++serial;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}))});
 const evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value};
 await send('Emulation.setDeviceMetricsOverride',{width:1400,height:1100,deviceScaleFactor:1,mobile:false});
 await send('Page.navigate',{url:pathToFileURL(join(output,'report.html')).href});
 for(let i=0;i<100;i++){if(await evaluate("document.querySelectorAll('[data-family]').length>0"))break;await new Promise(r=>setTimeout(r,50))}
 assert.equal(await evaluate("document.querySelectorAll('[data-family]').length"),expected.families.length,await evaluate("document.querySelector('#error').textContent"));
 assert.equal(await evaluate("document.querySelector('.coverage-ring>span').textContent"),(100*portal.runCoverage.observed/portal.runCoverage.inventory).toFixed(1)+'%');
 let reviewed=0;
 for(const family of expected.families){
  await evaluate(`document.querySelector('[data-family="${family.id}"]').click()`);
  assert.equal(await evaluate("document.querySelectorAll('.dimension-group').length"),family.dimensions.length);
  for(const flow of family.flows){
   for(let index=0;index<flow.values.length;index++)await evaluate(`Array.from(document.querySelector('[data-dimension="${index}"]').querySelectorAll('.dimension-tab')).find(e=>e.dataset.value===${JSON.stringify(flow.values[index])}).click()`);
   await evaluate(`document.querySelector('[data-id="${flow.id}"]').click()`);
   const variant=portal.variants.find(v=>v.id===flow.id);
   assert.equal(await evaluate("document.querySelector('#detail h2').textContent"),variant.name);
   const stages=await evaluate("Array.from(document.querySelectorAll('#detail .stage strong')).map(e=>e.textContent)");
   assert.deepEqual(stages,variant.stages.map(stage=>stage.name));
   assert.ok(stages.every(label=>!/^Step \d|^Stage \d/.test(label)));
   await evaluate("document.querySelector('#all-methods').click()");
   assert.equal(await evaluate("document.querySelectorAll('#detail .method').length"),new Set(variant.references.flatMap(r=>r.methods)).size);
   assert.ok(!(await evaluate("document.querySelector('#detail').textContent")).includes('behavior needs verification'));
   assert.ok(!/\b(?:Likely match|Partial match|No match|likely matches|partial matches|no match)\b/.test(await evaluate("document.querySelector('main').innerText")));
   assert.ok(!/similarity/i.test(await evaluate("document.querySelector('main').innerText")));
   if(flow.matches.length>1){
    const second=flow.matches[1];
    await evaluate(`document.querySelector('#test-select').value=${JSON.stringify(second.testId)};document.querySelector('#test-select').dispatchEvent(new Event('change'))`);
    assert.deepEqual(await evaluate("Array.from(document.querySelectorAll('#detail .stage strong')).map(e=>e.textContent)"),variant.stagePresentation?.mode==='ungrouped'?[]:second.stages.map(stage=>stage.label));
    assert.ok(!/Likely match|Partial match|No match/.test(await evaluate("document.querySelector('#match-note').textContent")));
   }
   reviewed++;
  }
 }
 await evaluate("document.querySelector('[data-family]').click()");
 assert.equal(await evaluate("document.querySelector('#error').textContent"),'');
 assert.equal(await evaluate("getComputedStyle(document.querySelector('.layout')).gridTemplateColumns.split(' ').length"),2);
 await send('Page.captureScreenshot',{format:'png'}).then(shot=>writeFile(join(output,'overview.png'),Buffer.from(shot.data,'base64')));
 await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
 assert.ok(await evaluate('document.documentElement.scrollWidth<=window.innerWidth+1'));
 await send('Page.captureScreenshot',{format:'png'}).then(shot=>writeFile(join(output,'mobile.png'),Buffer.from(shot.data,'base64')));
 await writeFile(join(output,'browser-check.json'),JSON.stringify({families:expected.families.length,scenarios:reviewed,mobileOverflow:false},null,2)+'\n');
 console.log('Validated '+reviewed+' scenarios and desktop/mobile layout');
}finally{socket?.close();browser.kill()}
