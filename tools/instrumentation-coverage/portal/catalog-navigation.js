/* Catalog-authored navigation. No library names or scenario-ID parsing. */
globalThis.PharosNavigation = {
  validate(report){
    const navigation=report.catalogNavigation;
    if(!navigation){if(report.catalogAssessment?.navigationSchemaVersion===1)throw Error('Catalog navigation is missing');return;}
    if(navigation.schemaVersion!==1||!Array.isArray(navigation.families)||!navigation.families.length)throw Error('Invalid catalog navigation');
    const ids=new Set(report.variants.map(v=>v.id)),owners=new Set(),families=new Set();
    const named=s=>typeof s==='string'&&s.trim()&&!/^(?:(?:step|stage|checkpoint|method|family|group|dimension|value|scenario)[\s_-]*\d*|todo|tbd|unknown)$/i.test(s.trim());
    for(const family of navigation.families){
      if(!named(family.name)||!family.id||families.has(family.id)||!family.description?.trim()||!Array.isArray(family.dimensions))throw Error('Invalid catalog family');
      families.add(family.id);const dimensions=new Map();
      for(const d of family.dimensions){
        if(!d.id||dimensions.has(d.id)||!named(d.label)||!Array.isArray(d.values)||!d.values.length)throw Error('Invalid catalog dimension');
        const values=new Set();for(const value of d.values){if(!value.id||values.has(value.id)||!named(value.label))throw Error('Invalid catalog dimension value');values.add(value.id);}
        dimensions.set(d.id,values);
      }
      if(!family.scenarios||typeof family.scenarios!=='object'||Array.isArray(family.scenarios)||!Object.keys(family.scenarios).length)throw Error('Catalog family needs scenarios');
      const labels=new Set();
      for(const [id,scenario] of Object.entries(family.scenarios)){
        if(!ids.has(id)||owners.has(id)||!named(scenario.label)||!scenario.values||Object.keys(scenario.values).length!==dimensions.size)throw Error('Orphan or ambiguous catalog scenario');
        for(const [key,value] of Object.entries(scenario.values))if(!dimensions.get(key)?.has(value))throw Error('Unknown catalog dimension value');
        const identity=JSON.stringify([Array.from(dimensions.keys(),key=>scenario.values[key]),scenario.label.trim().toLowerCase()]);
        if(labels.has(identity))throw Error('Indistinguishable catalog scenario labels');labels.add(identity);
        owners.add(id);
      }
    }
    if(ids.size!==owners.size)throw Error('Catalog navigation has orphan scenarios');
  },
  render({report,host,state,selected,choose,row,el,verified,partially}){
    const families=report.catalogNavigation.families;
    const variants=new Map(report.variants.map(v=>[v.id,v]));
    const methodCoverage=members=>{
      const methods=new Map();
      for(const v of members)for(const method of new Set(v.references.flatMap(r=>r.methods))){
        const prior=methods.get(method)||{context:0,root:0};
        const ids=report.evidenceBasis==='execution-fingerprint'?Object.keys(v.methods[method]?.tests||{}):new Set([...v.associations,...v.similarityCandidates].map(t=>t.testId));
        for(const id of ids){
          const counts=v.methods[method]?.tests[id]||{};
          prior.context+=counts.context||0;prior.root+=counts.root||0;
        }
        methods.set(method,prior);
      }
      return {total:methods.size,context:[...methods.values()].filter(x=>x.context>0).length,
        root:[...methods.values()].filter(x=>!x.context&&x.root>0).length};
    };
    const summary=members=>{
      const declared=members.filter(v=>!v.id.startsWith('candidate.')),full=declared.filter(verified).length,partial=declared.filter(partially).length;
      return report.evidenceBasis==='execution-fingerprint'
        ? `${declared.length} scenarios · ${methodCoverage(declared).context+methodCoverage(declared).root} / ${methodCoverage(declared).total} methods hit`
        : `${declared.length} scenarios · ${full} supported · ${partial} partial · ${declared.length-full-partial} unverified`;
    };
    const coverageBar=members=>{
      const declared=members.filter(v=>!v.id.startsWith('candidate.'));
      const full=declared.filter(verified).length,partial=declared.filter(partially).length;
      const bar=el('span',undefined,'family-coverage');
      bar.setAttribute('role','img');bar.setAttribute('aria-label',summary(members));bar.title=summary(members);
      const coverage=methodCoverage(declared),fingerprints=report.evidenceBasis==='execution-fingerprint';
      for(const [state,count] of (fingerprints?[['context',coverage.context],['root',coverage.root],['unobserved',coverage.total-coverage.context-coverage.root]]:[['supported',full],['partial',partial],['unverified',declared.length-full-partial]])){
        const segment=el('span',undefined,fingerprints?state:'family-coverage-'+state);segment.dataset.count=count;
        const total=fingerprints?coverage.total:declared.length;segment.style.width=(total?100*count/total:0)+'%';bar.append(segment);
      }
      return bar;
    };
    let family=families.find(f=>Object.hasOwn(f.scenarios,selected));state.family=family.id;
    state.values={...family.scenarios[selected].values};
    host.replaceChildren();
    const picker=el('details',undefined,'family-browser');picker.open=true;
    picker.append(el('summary',`Functionality families (${families.length})`));
    const cards=el('div',undefined,'family-cards');
    for(const f of families){
      const members=Object.keys(f.scenarios).map(id=>variants.get(id));
      const card=el('button',undefined,'family-card'+(f.id===family.id?' active':''));card.dataset.family=f.id;
      card.setAttribute('aria-pressed',String(f.id===family.id));card.append(el('strong',f.name),el('small',summary(members)),coverageBar(members));
      card.onclick=()=>choose(members[0]);cards.append(card);
    }
    const legend=el('div',undefined,'family-coverage-legend');
    for(const [state,label] of (report.evidenceBasis==='execution-fingerprint'
      ? [['context','With Context'],['root','Root only'],['unobserved','Not hit']]
      : [['supported','Supported'],['partial','Partial'],['unverified','Unverified']])){
      const item=el('span');const swatch=el('i',undefined,report.evidenceBasis==='execution-fingerprint'?'dot '+state:'family-coverage-'+state);swatch.setAttribute('aria-hidden','true');
      item.append(swatch,document.createTextNode(label));legend.append(item);
    }
    picker.append(legend,cards);host.append(picker);
    const heading=el('div',undefined,'family-heading');
    heading.append(el('h3',family.name),el('p',family.description),el('small',summary(Object.keys(family.scenarios).map(id=>variants.get(id)))));host.append(heading);
    let members=Object.keys(family.scenarios);
    for(const dimension of family.dimensions){
      const group=el('div',undefined,'dimension-group');group.dataset.dimension=dimension.id;group.append(el('small',dimension.label));
      const tabs=el('div',undefined,'dimension-tabs');
      for(const value of dimension.values){
        const matches=members.filter(id=>family.scenarios[id].values[dimension.id]===value.id);if(!matches.length)continue;
        const button=el('button',value.label,'dimension-tab'+(state.values[dimension.id]===value.id?' active':''));
        button.dataset.value=value.id;button.setAttribute('aria-pressed',String(state.values[dimension.id]===value.id));
        button.onclick=()=>choose(variants.get(matches[0]));tabs.append(button);
      }
      group.append(tabs);host.append(group);
      members=members.filter(id=>family.scenarios[id].values[dimension.id]===state.values[dimension.id]);
    }
    const outcomes=el('div',undefined,'variant-heading');outcomes.append(el('strong','Scenarios'),el('small',summary(members.map(id=>variants.get(id)))));host.append(outcomes);
    for(const id of members)host.append(row(variants.get(id),family.scenarios[id].label));
  }
};
