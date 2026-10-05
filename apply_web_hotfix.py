from pathlib import Path

p = Path("src/ui.js")
s = p.read_text(encoding="utf-8")

old = "vision:hasSessionKey(),"
if old in s:
    s = s.replace(old, "vision:true,", 1)

start = s.find("function nativeFallbackGroups(frames){")
end = s.find("function nativeSample(", start)
if start < 0 or end < 0:
    raise SystemExit("nativeFallbackGroups não encontrado")

replacement = r'''function nativeFallbackGroups(frames){
 if(!Array.isArray(frames)||!frames.length)return[];
 if(frames.length<8)return ['match','squad','calendar'].map(type=>({type,indices:frames.map(f=>f.index)}));

 const gaps=[];
 for(let i=1;i<frames.length;i++){
  const before=Number(frames[i-1]?.capturedAt||0),after=Number(frames[i]?.capturedAt||0);
  gaps.push({at:i,gap:Math.max(0,after-before)});
 }
 const cuts=gaps
  .filter(row=>row.gap>=2500)
  .sort((a,b)=>b.gap-a.gap)
  .slice(0,3)
  .map(row=>row.at)
  .sort((a,b)=>a-b);

 const bounds=[0,...cuts,frames.length],segments=[];
 for(let i=0;i<bounds.length-1;i++){
  const part=frames.slice(bounds[i],bounds[i+1]);
  if(part.length)segments.push(part.map(f=>f.index));
 }

 if(segments.length<2&&frames.length>=20){
  segments.length=0;
  const slots=Math.min(4,Math.max(1,Math.ceil(frames.length/10)));
  let from=0;
  for(let i=0;i<slots;i++){
   const to=Math.round((i+1)*frames.length/slots);
   segments.push(frames.slice(from,to).map(f=>f.index));
   from=to;
  }
 }

 return segments.slice(0,4).flatMap(indices=>
  ['match','squad','calendar'].map(type=>({type,indices:[...indices]}))
 );
}
'''
s = s[:start] + replacement + s[end:]

old2 = "nativeCollectorNotice='Nenhum dado pôde ser aplicado. A sessão foi preservada para nova tentativa.';"
new2 = "nativeCollectorNotice='Nenhum dado pôde ser aplicado. '+(report.length?'Detalhes: '+report.slice(-4).join(' · '):'A sessão foi preservada para nova tentativa.');"
if old2 in s:
    s = s.replace(old2, new2, 1)

s = s.replace("profile:'fast'\n },message=>{", "profile:'complete'\n },message=>{", 1)

p.write_text(s, encoding="utf-8")
print("Hotfix automático aplicado em src/ui.js")
