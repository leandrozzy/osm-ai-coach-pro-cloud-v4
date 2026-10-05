from pathlib import Path

p = Path("src/ui.js")
s = p.read_text(encoding="utf-8")

s = s.replace(
    "const NATIVE_SESSION_DONE='osm-ai-coach-pro:native-session-done:v2';",
    "const NATIVE_SESSION_DONE='osm-ai-coach-pro:native-session-done:v3';",
    1
)

needle = "let nativeCollectorBusy=false,nativeCollectorNotice='';"
replacement = """let nativeCollectorBusy=false,nativeCollectorNotice='';
let nativeCollectorProgress={active:false,current:0,total:0,percent:0,label:'',success:0,failed:0};
function nativeSetProgress(current,total,label='',success=nativeCollectorProgress.success,failed=nativeCollectorProgress.failed){
 const safeTotal=Math.max(1,Number(total)||1),safeCurrent=Math.max(0,Math.min(safeTotal,Number(current)||0));
 nativeCollectorProgress={active:safeCurrent<safeTotal,current:safeCurrent,total:safeTotal,percent:Math.round(safeCurrent*100/safeTotal),label,success,failed};
 const bar=root?.querySelector?.('[data-native-progress-bar]');
 if(bar)bar.style.width=nativeCollectorProgress.percent+'%';
 const text=root?.querySelector?.('[data-native-progress-text]');
 if(text)text.textContent=(label||'Processando')+' · '+safeCurrent+'/'+safeTotal+' · '+nativeCollectorProgress.percent+'%';
 const stats=root?.querySelector?.('[data-native-progress-stats]');
 if(stats)stats.textContent='Aplicados: '+success+' · Falhas: '+failed;
}"""
if needle not in s:
    raise SystemExit("Estado nativo não encontrado.")
s = s.replace(needle, replacement, 1)

start = s.find("function nativeGroups(frames){")
end = s.find("function nativeFallback(frames){", start)
if start < 0 or end < 0:
    raise SystemExit("nativeGroups/nativeFallback não encontrados.")

new_groups = """function nativeSessionSegments(frames){
 if(!Array.isArray(frames)||!frames.length)return[];
 const all=frames.map((f,i)=>({...f,index:Number.isInteger(f.index)?f.index:i}));
 const gaps=[];
 for(let i=1;i<all.length;i++){
  const before=Number(all[i-1]?.capturedAt||0),after=Number(all[i]?.capturedAt||0);
  gaps.push({at:i,gap:Math.max(0,after-before)});
 }
 const cuts=gaps
  .filter(row=>row.gap>=2200)
  .sort((a,b)=>b.gap-a.gap)
  .slice(0,3)
  .map(row=>row.at)
  .sort((a,b)=>a-b);

 const bounds=[0,...cuts,all.length],segments=[];
 for(let i=0;i<bounds.length-1;i++){
  const part=all.slice(bounds[i],bounds[i+1]);
  if(part.length)segments.push(part);
 }

 if(segments.length<2&&all.length>=20){
  segments.length=0;
  const slots=Math.min(4,Math.max(1,Math.ceil(all.length/10)));
  let from=0;
  for(let i=0;i<slots;i++){
   const to=Math.round((i+1)*all.length/slots);
   segments.push(all.slice(from,to));
   from=to;
  }
 }
 return segments.slice(0,4);
}

function nativeGroups(frames){
 const segments=nativeSessionSegments(frames),out=[];
 segments.forEach((segment,segmentIndex)=>{
  const slotHint=Math.min(4,segmentIndex+1);
  const classified={match:[],squad:[],calendar:[]},unknown=[];
  for(const frame of segment){
   const type=nativeType(frame);
   if(type==='unknown')unknown.push(frame.index);
   else classified[type].push(frame.index);
  }

  const classifiedCount=Object.values(classified).reduce((n,rows)=>n+rows.length,0);
  if(!classifiedCount){
   for(const type of ['match','squad','calendar']){
    out.push({type,indices:segment.map(f=>f.index),slotHint});
   }
   return;
  }

  for(const type of ['match','squad','calendar']){
   const own=classified[type];
   const indices=own.length ? [...own] : [...unknown];
   if(indices.length)out.push({type,indices,slotHint});
  }
 });
 return out;
}
"""
s = s[:start] + new_groups + s[end:]

start = s.find("function nativeFallback(frames){")
end = s.find("function nativeSample(", start)
if start < 0 or end < 0:
    raise SystemExit("nativeFallback não encontrado.")
new_fallback = """function nativeFallback(frames){
 return nativeSessionSegments(frames).flatMap((segment,index)=>{
  const slotHint=Math.min(4,index+1),indices=segment.map(f=>f.index);
  return ['match','squad','calendar'].map(type=>({type,indices:[...indices],slotHint}));
 });
}
"""
s = s[:start] + new_fallback + s[end:]

s = s.replace("vision:hasSessionKey(),", "vision:true,", 1)
s = s.replace("profile:'fast'},m=>{", "profile:'complete'},m=>{", 1)

start = s.find("async function processNativeCollector(){")
end = s.find("\nfunction readCurrentReview", start)
if start < 0 or end < 0:
    raise SystemExit("processNativeCollector não encontrado.")

new_process = """async function processNativeCollector(){
 const bridge=nativeBridge();
 if(!bridge||nativeCollectorBusy||busy)return;

 let session;
 try{session=JSON.parse(bridge.latestSession()||'null')}catch{return}
 if(!session||session.state!=='ready'||!session.id||!Array.isArray(session.frames)||!session.frames.length)return;
 if(localStorage.getItem(NATIVE_SESSION_DONE)===session.id)return;

 nativeCollectorBusy=true;
 busy=true;
 let success=0,failed=0;
 const report=[];

 try{
  nativeCollectorNotice='Organizando '+session.frames.length+' telas capturadas…';
  let groups=nativeGroups(session.frames);
  if(!groups.length)groups=nativeFallback(session.frames);

  nativeSetProgress(0,groups.length,'Organizando sessão',0,0);
  if(root)render();

  for(let i=0;i<groups.length;i++){
   const g=groups[i];
   const preferred=Math.min(4,Math.max(1,Number(g.slotHint)||1));
   const files=await nativeFiles(session,g.indices);

   nativeCollectorNotice='S'+preferred+' · '+g.type+' · analisando…';
   nativeSetProgress(i,groups.length,nativeCollectorNotice,success,failed);
   if(root)render();

   if(!files.length){
    failed++;
    report.push('S'+preferred+' '+g.type+': sem telas');
    nativeSetProgress(i+1,groups.length,'Sem telas para S'+preferred+' '+g.type,success,failed);
    continue;
   }

   try{
    const target=await nativeApply(files,g.type,preferred);
    success++;
    report.push('S'+target+' '+g.type+' ✓');
    nativeSetProgress(i+1,groups.length,'S'+target+' '+g.type+' concluído',success,failed);
   }catch(error){
    failed++;
    report.push('S'+preferred+' '+g.type+': '+error.message);
    nativeSetProgress(i+1,groups.length,'Falha em S'+preferred+' '+g.type,success,failed);
   }
  }

  for(const slot of getState().slots){
   if(!known(slotTeam(slot))&&!slot.squad?.players?.length&&!slot.calendar?.length)continue;
   updateSlot(state=>{
    try{state.director.plan=buildMarketPlan(state.squad,state.director.cash,state.match.myStrength)}catch{}
   },slot.id);
  }

  if(success){
   localStorage.setItem(NATIVE_SESSION_DONE,session.id);
   nativeCollectorNotice='Sessão OSM concluída: '+success+' leitura(s) aplicada(s), '+failed+' falha(s). '+report.join(' · ');
  }else{
   nativeCollectorNotice='Nenhum dado pôde ser aplicado. Detalhes: '+report.slice(-6).join(' · ');
  }
 }catch(error){
  nativeCollectorNotice='Falha ao processar sessão automática: '+error.message;
  failed++;
 }finally{
  nativeCollectorProgress={...nativeCollectorProgress,active:false,percent:100,success,failed};
  nativeCollectorBusy=false;
  busy=false;
  if(root){render();toast(nativeCollectorNotice);}
 }
}"""
s = s[:start] + new_process + s[end:]

old = """return pagehead('Seu dia, organizado.','Uma visão geral do que fazer e do que falta nos quatro slots.','VISÃO GERAL')+notice+(nativeBridge()?'<p class=\\"muted\\" data-native-status>'+esc(nativeCollectorNotice||'Coletor Android conectado. Ao fechar o OSM, a sessão será processada automaticamente.')+'</p>':'')+renderSlotDashboard"""
if old not in s:
    old = """return pagehead('Seu dia, organizado.','Uma visão geral do que fazer e do que falta nos quatro slots.','VISÃO GERAL')+notice+(nativeBridge()?'<p class="muted" data-native-status>'+esc(nativeCollectorNotice||'Coletor Android conectado. Ao fechar o OSM, a sessão será processada automaticamente.')+'</p>':'')+renderSlotDashboard"""
if old not in s:
    raise SystemExit("today() atual não encontrado.")

progress_expr = """return pagehead('Seu dia, organizado.','Uma visão geral do que fazer e do que falta nos quatro slots.','VISÃO GERAL')+notice+(nativeBridge()?'<section style="margin:12px 0 20px;padding:14px;border:1px solid #d9dfcf;border-radius:14px;background:#f7f9f2"><div style="display:flex;justify-content:space-between;gap:12px;margin-bottom:8px"><strong>Leitura automática do OSM</strong><span data-native-progress-stats style="font-size:12px">Aplicados: '+nativeCollectorProgress.success+' · Falhas: '+nativeCollectorProgress.failed+'</span></div><div style="height:12px;background:#e5e9df;border-radius:999px;overflow:hidden"><div data-native-progress-bar style="height:100%;width:'+nativeCollectorProgress.percent+'%;background:#6d8d36;transition:width .25s ease"></div></div><div data-native-progress-text style="margin-top:8px;font-size:13px">'+esc(nativeCollectorProgress.label||nativeCollectorNotice||'Pronto para nova sessão')+(nativeCollectorProgress.total?' · '+nativeCollectorProgress.current+'/'+nativeCollectorProgress.total+' · '+nativeCollectorProgress.percent+'%':'')+'</div><p class="muted" data-native-status style="margin:8px 0 0">'+esc(nativeCollectorNotice||'Coletor Android conectado. Ao fechar o OSM, a sessão será processada automaticamente.')+'</p></section>':'')+renderSlotDashboard"""
s = s.replace(old, progress_expr, 1)

p.write_text(s, encoding="utf-8")
print("Progress bar + slot distribution + full automatic analysis applied.")
