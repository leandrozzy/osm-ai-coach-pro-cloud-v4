from pathlib import Path

p = Path("src/ui.js")
s = p.read_text(encoding="utf-8")

# 1) Automatic Android sessions must use server-side providers configured in Vercel,
# not only keys stored inside the APK WebView.
s = s.replace("vision:hasSessionKey(),", "vision:true,", 1)

# 2) Use complete profile for automatic saved-session processing.
s = s.replace("profile:'fast'},m=>{", "profile:'complete'},m=>{", 1)

# 3) Replace the current equal-quarter fallback with time-gap session segmentation.
old_start = s.find("function nativeFallback(frames){")
old_end = s.find("function nativeSample(", old_start)
if old_start < 0 or old_end < 0:
    raise SystemExit("nativeFallback atual não encontrado em src/ui.js")

replacement = r'''function nativeFallback(frames){
 if(!Array.isArray(frames)||!frames.length)return[];
 const all=frames.map((f,i)=>({...f,index:Number.isInteger(f.index)?f.index:i}));
 if(all.length<8)return ['match','squad','calendar'].map(type=>({type,indices:all.map(f=>f.index)}));

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
  if(part.length)segments.push(part.map(f=>f.index));
 }

 if(segments.length<2&&all.length>=20){
  segments.length=0;
  const slots=Math.min(4,Math.max(1,Math.ceil(all.length/10)));
  let from=0;
  for(let i=0;i<slots;i++){
   const to=Math.round((i+1)*all.length/slots);
   segments.push(all.slice(from,to).map(f=>f.index));
   from=to;
  }
 }

 return segments.slice(0,4).flatMap(indices=>
  ['match','squad','calendar'].map(type=>({type,indices:[...indices]}))
 );
}
'''
s = s[:old_start] + replacement + s[old_end:]

# 4) Keep the concrete reader failures visible if nothing was applied.
s = s.replace(
    "else nativeCollectorNotice='Nenhum dado pôde ser aplicado. A sessão foi preservada para nova tentativa.'",
    "else nativeCollectorNotice='Nenhum dado pôde ser aplicado. '+(report.length?'Detalhes: '+report.slice(-5).join(' · '):'A sessão foi preservada para nova tentativa.')",
    1
)

p.write_text(s, encoding="utf-8")
print("OSM automatic-session hotfix v2 applied.")
