#!/usr/bin/env python3
"""Prepare a bounded LLM dossier, validate an assessment, or render a behavior report."""
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
from build import BASE,HERE,ROOT
from evidence_model import digest,validate


def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['prepare','validate','build'])
    p.add_argument('--report',type=Path,default=BASE/'build/report/report.json')
    p.add_argument('--naming-evidence',type=Path,default=BASE/'build/naming-evidence.json')
    p.add_argument('--reference',type=Path,default=BASE/'build/reference.json')
    p.add_argument('--assessment',type=Path)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--source',action='append',default=[])
    p.add_argument('--upstream-filter',action='append',default=[])
    p.add_argument('--capability-id');p.add_argument('--capability-name')
    args=p.parse_args()
    if args.command=='build':
        if not args.assessment:p.error('--assessment is required')
        subprocess.run([sys.executable,str(HERE/'build.py'),'--report',str(args.report),'--naming-evidence',str(args.naming_evidence),'--reference',str(args.reference),'--assessment',str(args.assessment),'--output',str(args.output)],check=True);return
    report=json.loads(args.report.read_text());packet=json.loads(args.naming_evidence.read_text())
    args.output.mkdir(parents=True,exist_ok=True)
    if args.command=='validate':
        if not args.assessment:p.error('--assessment is required')
        assessment=json.loads(args.assessment.read_text());sources,claims=validate(assessment,report,packet,ROOT)
        write(args.output/'validation.json',{'validReferences':True,'semanticApproval':False,'sources':len(sources),'claims':len(claims),'variants':len(assessment['variants']),'assessmentSha256':digest(assessment)})
        print('References valid; semantic interpretation still requires review.');return
    if not args.capability_id or not args.capability_name:p.error('prepare requires --capability-id and --capability-name')
    sources=[]
    for relative in args.source:
        source=(ROOT/relative).resolve()
        if not source.is_relative_to(ROOT.resolve()):raise ValueError('Source outside repository')
        text=source.read_text();sources.append(dict(id='local-'+str(len(sources)),origin='local',path=str(source.relative_to(ROOT)),sha256=hashlib.sha256(text.encode()).hexdigest(),ranges=[[1,len(text.splitlines())]],text=text))
    families=[]
    for family in packet['families']:
        examples=[e for e in family['examples'] if not args.upstream_filter or any(f in e['name'] for f in args.upstream_filter)]
        if examples:families.append({**family,'examples':examples})
    if not families:raise ValueError('No upstream examples matched')
    upstream_paths={e['path'] for f in families for e in f['examples']}
    identity={'report':digest(report),'namingEvidence':digest(packet)}
    write(args.output/'dossier.json',dict(library=report['library'],version=report['version'],sourceVersion=packet['sourceVersion'],inputIdentity=identity,localSources=sources,upstreamFamilies=families,upstreamSources={k:v for k,v in packet['sources'].items() if k in upstream_paths},testResults=report['statisticalAttribution']['testAccounting']['tests'],limitations=['Family method unions are not per-example fingerprints.','Similar execution does not establish behavior.','Current local source is not an immutable snapshot of the earlier run.']))
    write(args.output/'assessment.skeleton.json',dict(schemaVersion=2,library=report['library'],version=report['version'],sourceVersion=packet['sourceVersion'],inputIdentity=identity,capability=dict(id=args.capability_id,name=args.capability_name),reviewStatus='LLM_DRAFT_NOT_HUMAN_REVIEWED',sources=[{k:v for k,v in s.items() if k!='text'} for s in sources],claims=[],variants=[],limitations=[]))
    print('Dossier and incomplete assessment skeleton:',args.output)
if __name__=='__main__':main()
