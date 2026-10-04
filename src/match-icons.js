import {normalize} from './utils.js';
import {matchOcrRows,parseMatchOverlay} from './parser-match.js';
import {recognizeDigitNumber} from './osm-digits.js';
const yellow=(r,g,b)=>r>160&&g>110&&b<120&&r>b*1.6&&g>b*1.3;
const white=(r,g,b)=>Math.min(r,g,b)>175&&Math.max(r,g,b)-Math.min(r,g,b)<65;
const dark=(r,g,b)=>Math.max(r,g,b)<150;
function components(image,box,predicate){
 const points=new Set(),left=Math.max(0,Math.floor(box.left)),right=Math.min(image.width,Math.ceil(box.right)),top=Math.max(0,Math.floor(box.top)),bottom=Math.min(image.height,Math.ceil(box.bottom));
 for(let y=top;y<bottom;y++)for(let x=left;x<right;x++){const offset=(y*image.width+x)*4;if(image.data[offset+3]>200&&predicate(image.data[offset],image.data[offset+1],image.data[offset+2],x,y))points.add(y*image.width+x);}
 const out=[];
 while(points.size){const seed=points.values().next().value;points.delete(seed);const queue=[seed],pixels=new Set();let x0=image.width,x1=0,y0=image.height,y1=0;
  while(queue.length){const point=queue.pop(),x=point%image.width,y=Math.floor(point/image.width);pixels.add(point);x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=Math.max(y1,y);
   for(const next of [point-1,point+1,point-image.width,point+image.width])if(points.has(next)&&Math.abs(next%image.width-x)<=1){points.delete(next);queue.push(next);}
  }
  out.push({left:x0,right:x1+1,top:y0,bottom:y1+1,w:x1-x0+1,h:y1-y0+1,pixels});
 }
 return out;
}
function fraction(image,box,predicate){
 let found=0,total=0;
 for(let y=Math.max(0,Math.floor(box.top));y<Math.min(image.height,Math.ceil(box.bottom));y++)for(let x=Math.max(0,Math.floor(box.left));x<Math.min(image.width,Math.ceil(box.right));x++){const i=(y*image.width+x)*4;found+=predicate(image.data[i],image.data[i+1],image.data[i+2])?1:0;total++;}
 return total?found/total:0;
}
function padlock(image,label){
 const box={left:label.left-image.width*.057,right:label.left-image.width*.007,top:label.top-image.height*.045,bottom:label.bottom+image.height*.045};
 for(const body of components(image,box,yellow)){
  if(body.w<image.width*.009||body.w>image.width*.025||body.h<image.height*.02||body.h>image.height*.055||body.w/body.h<.7||body.w/body.h>1.3||body.pixels.size/(body.w*body.h)<.65)continue;
  const holeBox={left:body.left+body.w*.25,right:body.left+body.w*.75,top:body.top+body.h*.15,bottom:body.top+body.h*.85};
  const hole=components(image,holeBox,dark).some(part=>part.h>body.h*.27&&part.h<body.h*.72&&part.w>body.w*.07&&part.w<body.w*.35);
  if(!hole)continue;
  // The handle has two white sides and a white rounded top, with blue inside.
  const handle={left:body.left+body.w*.1,right:body.right-body.w*.1,top:body.top-body.h*.62,bottom:body.top+body.h*.06};
  const handleCenter=fraction(image,{left:body.left+body.w*.33,right:body.left+body.w*.67,top:body.top-body.h*.32,bottom:body.top-body.h*.06},white);
  if(fraction(image,handle,white)<.14||handleCenter>.5)continue;
  return {confidence:.96,box:{left:body.left,right:body.right,top:handle.top,bottom:body.bottom}};
 }
 return null;
}
function thermometers(image,box){
 const found=[];
 const predicates=[['Verde',(r,g,b)=>g>110&&g>r*1.4&&g>b*1.3],['Azul',(r,g,b)=>b>140&&b>r*1.6&&b>g*1.12],['Amarelo',(r,g,b)=>yellow(r,g,b)&&g>r*.73],['Laranja',(r,g,b)=>r>190&&g>80&&g<r*.73&&b<90],['Vermelho',(r,g,b)=>r>160&&r>g*1.7&&r>b*1.5]];
 for(const [color,predicate] of predicates)for(const part of components(image,box,predicate)){
  if(part.w<image.width*.005||part.w>image.width*.016||part.h<image.height*.029||part.h>image.height*.095||part.h/part.w<1.7||part.h/part.w>7||part.pixels.size/(part.w*part.h)<.5)continue;
  const widths=[];for(let y=part.top;y<part.bottom;y++){let left=image.width,right=-1;for(let x=part.left;x<part.right;x++)if(part.pixels.has(y*image.width+x)){left=Math.min(left,x);right=Math.max(right,x);}widths.push(right>=left?right-left+1:0);}
  const upper=Math.max(...widths.slice(0,Math.max(1,Math.floor(part.h*.4)))),lower=Math.max(...widths.slice(Math.floor(part.h*.65)));
  if(upper>part.w*.7||lower<part.w*.85)continue;
  const candidateBox={left:part.left,right:part.right,top:part.top,bottom:part.bottom};
  // Warm orange also satisfies the broader red threshold at some edges.
  // Those masks describe the same physical object, not two referees; preserve
  // the established colour order while keeping distinct objects ambiguous.
  if(found.some(candidate=>{
   const previous=candidate.box,overlap=Math.max(0,Math.min(previous.right,part.right)-Math.max(previous.left,part.left))*Math.max(0,Math.min(previous.bottom,part.bottom)-Math.max(previous.top,part.top));
   return overlap/Math.max((previous.right-previous.left)*(previous.bottom-previous.top),part.w*part.h)>.75;
  }))continue;
  found.push({color,confidence:.94,box:candidateBox});
 }
 return found;
}
function thermometer(image,label){
 return thermometers(image,{left:label.left-image.width*.039,right:label.left-image.width*.001,top:label.top-image.height*.052,bottom:label.bottom+image.height*.035})[0]||null;
}
function refereeBesideAvatar(image,parsed){
 if(!parsed._versus||!parsed.myName||!parsed.rivalName)return null;
 // The label can be unreadable and the central panel can move vertically.
 // A thermometer still needs its actual narrow stem and bulb, next to the
 // referee's small face. A shirt colour or a coloured button is insufficient.
 const found=thermometers(image,{left:image.width*.46,right:image.width*.54,top:image.height*.32,bottom:image.height*.65}).filter(candidate=>{
  const box=candidate.box,face={left:box.left-image.width*.027,right:box.left-image.width*.003,top:box.top,bottom:box.bottom+image.height*.005};
  const skin=(r,g,b)=>r>140&&g>95&&b<160&&r>g*1.12&&g>b*1.15;
  return opaqueBox(image,face)&&fraction(image,face,skin)>.08&&fraction(image,face,(r,g,b)=>Math.max(r,g,b)<90)>.07&&fraction(image,face,white)>.008;
 });
 return found.length===1?found[0]:null;
}
function visiblyEmptyManagerLine(image,area){
 if(!area||!['squad-header','versus'].includes(area.kind))return null;
 const box={left:Math.floor(area.left),right:Math.ceil(area.right),top:Math.floor(area.top),bottom:Math.ceil(area.bottom)};
 if(box.left<0||box.top<0||box.right>image.width||box.bottom>image.height||box.right-box.left<image.width*.12||box.bottom-box.top<image.height*.027)return null;
 let opaque=0,blue=0,total=0;
 for(let y=box.top;y<box.bottom;y++)for(let x=box.left;x<box.right;x++){
  const at=(y*image.width+x)*4,r=image.data[at],g=image.data[at+1],b=image.data[at+2];total++;
  if(image.data[at+3]<240)continue;opaque++;
  if(b>45&&b>r*1.25&&b>g*.88&&r<155&&g<205)blue++;
 }
 // Missing OCR is not an empty username. Check the original visible blue
 // band for any white text/flag pixels, and reject cropped/loading images.
 if(!total||opaque/total<.995||blue/total<.72)return null;
 const ink=(r,g,b)=>Math.min(r,g,b)>170&&Math.max(r,g,b)-Math.min(r,g,b)<65||r>45&&g>145&&b>175&&g>r*1.3&&b>g*1.07;
 const fragments=components(image,box,ink).filter(part=>part.h>=Math.max(5,image.height*.0065)&&part.pixels.size>=Math.max(5,image.width*image.height*.0000025));
 if(fragments.length)return null;
 const luminance=(r,g,b)=>(r+g*2+b)/4;
 const dim=components(image,box,(r,g,b,x,y)=>{
  const palette=Math.min(r,g,b)>110&&Math.max(r,g,b)-Math.min(r,g,b)<80||g>90&&b>110&&g>r*1.18&&b>r*1.35;
  if(!palette)return false;
  const values=[];
  for(let yy=Math.max(0,y-5);yy<=Math.min(image.height-1,y+5);yy++)for(let xx=Math.max(0,x-5);xx<=Math.min(image.width-1,x+5);xx++){const at=(yy*image.width+xx)*4;values.push(luminance(image.data[at],image.data[at+1],image.data[at+2]));}
  values.sort((a,b)=>a-b);return luminance(r,g,b)-values[Math.floor(values.length/2)]>=24;
 }).filter(part=>part.h>=Math.max(5,image.height*.0065)&&part.pixels.size>=8);
 if(dim.some(part=>part.h>=image.height*.014&&part.pixels.size>=30)||dim.some((part,i)=>dim.some((other,j)=>i!==j&&Math.abs(part.top+part.h/2-other.top-other.h/2)<image.height*.014)))return null;
 return {confidence:.97,box};
}
function opaqueBox(image,box){
 let total=0,opaque=0;
 for(let y=Math.floor(box.top);y<Math.ceil(box.bottom);y++)for(let x=Math.floor(box.left);x<Math.ceil(box.right);x++){total++;if(image.data[(y*image.width+x)*4+3]>=240)opaque++;}
 return total&&opaque/total>.995;
}
function headerCircle(image,parsed){
 const fields=['myName','rivalName'].filter(field=>parsed._headerFields?.includes(field)&&parsed[field]);
 if(fields.length!==1||parsed._versus)return null;
 const cyan=(r,g,b)=>r<160&&g>125&&b>160&&g>r*1.2&&b>r*1.4;
 const rings=components(image,{left:image.width*.78,right:image.width*.91,top:image.height*.18,bottom:image.height*.44},cyan).filter(part=>part.h>image.height*.13&&part.h<image.height*.26&&part.w/part.h>.82&&part.w/part.h<1.18&&part.pixels.size/(part.w*part.h)>.035&&part.pixels.size/(part.w*part.h)<.42);
 if(rings.length!==1)return null;
 const ring=rings[0];return {field:fields[0]==='myName'?'myStrength':'rivalStrength',club:parsed[fields[0]],box:{left:ring.left+ring.w*.23,right:ring.right-ring.w*.23,top:ring.top+ring.h*.33,bottom:ring.bottom-ring.h*.17}};
}
const bonusGreen=(r,g,b)=>g>130&&g>r*1.5&&g>b*1.15&&r<160;
function versusCircles(image,parsed,mode='strength'){
 if(!parsed._versus||!parsed.myName||!parsed.rivalName)return [];
 const cyan=(r,g,b)=>r<160&&g>125&&b>160&&g>r*1.2&&b>r*1.4,found=[];
 for(const left of [true,false]){
  const rings=components(image,{left:image.width*(left?.28:.62),right:image.width*(left?.38:.72),top:image.height*.18,bottom:image.height*.33},cyan).filter(part=>part.h>image.height*.065&&part.h<image.height*.145&&part.w/part.h>.82&&part.w/part.h<1.18&&part.pixels.size/(part.w*part.h)>.035&&part.pixels.size/(part.w*part.h)<.42);
  if(rings.length!==1)continue;
  const ring=rings[0],own=parsed.location===(left?'Casa':'Fora'),box={left:ring.left+ring.w*.19,right:ring.right-ring.w*.19,top:ring.top+ring.h*.22,bottom:ring.bottom-ring.h*.20};
  // The own percentage is green; a rival percentage can use the same white
  // font as strength. The plus and percent glyphs, rather than colour alone,
  // decide whether this native circle is a percentage or a strength crop.
  const percentageInk=fraction(image,box,bonusGreen)>=.03&&bonusGlyphs(image,box,bonusGreen)?'green':fraction(image,box,white)>=.03&&bonusGlyphs(image,box,white)?'white':null;
  const partialPercentage=mode==='strength'&&fraction(image,box,white)>=.03&&bonusGlyphs(image,box,white,false);
  if(mode==='bonus'?!percentageInk:percentageInk||partialPercentage||fraction(image,box,(r,g,b)=>Math.min(r,g,b)>145&&Math.max(r,g,b)-Math.min(r,g,b)<65)<.03)continue;
  found.push({field:own?(mode==='bonus'?'myBonus':'myStrength'):(mode==='bonus'?'rivalBonus':'rivalStrength'),club:own?parsed.myName:parsed.rivalName,box,...(mode==='bonus'?{ink:percentageInk}:{})});
 }
 return found;
}
function bonusGlyphs(image,box,predicate=bonusGreen,requirePlus=true){
 const parts=components(image,box,predicate).filter(part=>part.h>=image.height*.004&&part.pixels.size>=8).sort((a,b)=>a.left-b.left);
 if(parts.length<(requirePlus?5:4))return false;
 const maximumH=Math.max(...parts.map(part=>part.h)),plus=parts[0];
 if(maximumH<image.height*.012)return false;
 if(requirePlus){
  if(plus.h<maximumH*.3||plus.h>maximumH*.75||plus.w/plus.h<.6||plus.w/plus.h>1.5)return false;
  const rowWidth=y=>{const xs=[];for(let x=plus.left;x<plus.right;x++)if(plus.pixels.has(y*image.width+x))xs.push(x);return xs.length?Math.max(...xs)-Math.min(...xs)+1:0;};
  const middle=Math.max(...Array.from({length:Math.max(1,Math.round(plus.h*.35))},(_,i)=>rowWidth(plus.top+Math.floor(plus.h*.35)+i)));
  if(middle<plus.w*.8||rowWidth(plus.top)>plus.w*.6||rowWidth(plus.bottom-1)>plus.w*.6)return false;
 }
 // The percent sign has two small circles and a separate ascending diagonal.
 // The up arrows above the badge and bare strength numerals cannot satisfy
 // this sequence inside its native cyan circle.
 for(const slash of parts){
  if(slash.h<maximumH*.75||slash.w/slash.h<.25||slash.w/slash.h>.9||slash.pixels.size/(slash.w*slash.h)>.5)continue;
  const centerAt=y=>{const xs=[];for(let x=slash.left;x<slash.right;x++)if(slash.pixels.has(y*image.width+x))xs.push(x);return xs.length?xs.reduce((a,b)=>a+b,0)/xs.length:null;};
  const first=centerAt(slash.top+1),last=centerAt(slash.bottom-2);if(first===null||last===null||first-last<slash.h*.3)continue;
  const dots=parts.filter(part=>part!==slash&&(!requirePlus||part!==plus)&&part.h>=maximumH*.3&&part.h<=maximumH*.75&&part.w/part.h>.4&&part.w/part.h<1.3);
  const start=requirePlus?plus.right:box.left;
  const upper=dots.find(part=>part.left>start&&part.left+part.w/2<first&&Math.abs(part.top-slash.top)<maximumH*.25);
  const lower=dots.find(part=>part.left+part.w/2>last&&Math.abs(part.bottom-slash.bottom)<maximumH*.25&&upper&&part.top>upper.top+maximumH*.25);
  if(upper&&lower&&parts.some(part=>(!requirePlus||part!==plus)&&part!==slash&&part!==upper&&part!==lower&&part.left>=start&&part.right<=upper.right&&part.h>=maximumH*.75))return true;
 }
 return false;
}
export function detectMatchPixels(image,evidence={}){
 const empty={match:{},meta:{}};
 if(!image?.data||!image.width||!image.height||image.data.length!==image.width*image.height*4||image.width/image.height<1.8||image.width/image.height>2.6||evidence.region&&evidence.region!=='full')return empty;
 const width=Number(evidence.width||image.width),height=Number(evidence.height||image.height),ocr=evidence.ocr||{},context=evidence.context||{};
 const parsed=parseMatchOverlay(ocr,width,height,context),match={};for(const [field,value] of Object.entries(parsed))if(!field.startsWith('_')&&value!==null&&value!=='NI'&&value!=='')match[field]=value;
 const meta={_ocrFields:Object.keys(match),_headerFields:(parsed._headerFields||[]).filter(field=>Object.hasOwn(match,field)),matchEvidence:parsed._evidence||{}},sx=image.width/width,sy=image.height/height;
 if(parsed._versusEvidence){
  const layout=parsed._versusEvidence,boxes=[...layout.clubs.map(club=>club.box),...(layout.manager?[layout.manager.box]:[])].map(box=>({left:box.left*sx,right:box.right*sx,top:box.top*sy,bottom:box.bottom*sy}));
  if(boxes.every(box=>opaqueBox(image,box)&&fraction(image,box,white)>.025))meta.matchLayoutEvidence={...layout,originalTextVisible:true};
 }
 const circle=headerCircle(image,parsed),circles=[...(circle?[circle]:[]),...versusCircles(image,parsed)];
 for(const detected of circles){
  // The parser's broad header area also contains the Equipa caption. Use the
  // actual cyan circle's interior whenever it is visible; otherwise a numeric
  // whitelist can turn letters in that caption into a false extra digit.
  parsed._strengthAreas={...parsed._strengthAreas,[detected.field]:{kind:'strength-badge-area',club:detected.club,coordinateSource:'native-circle',box:{left:detected.box.left/sx,right:detected.box.right/sx,top:detected.box.top/sy,bottom:detected.box.bottom/sy}}};
  if(match[detected.field]!=null)continue;
  // An isolated crop can read the actual numeral while the complete-page
  // engine misses Equipa and all sectors. Its word is still literally inside
  // this detected native circle of the identified club; no label is invented.
  const words=matchOcrRows(ocr).flatMap(row=>row.words),box=detected.box;
  const candidates=words.filter(word=>/^[1-9]\d{1,2}$/.test(String(word.WordText).trim())&&+word.WordText<=400&&
   Number(word.Left)*sx>=box.left-2&&Number(word.Left+word.Width)*sx<=box.right+2&&
   Number(word.Top)*sy>=box.top-2&&Number(word.Top+word.Height)*sy<=box.bottom+2&&Number(word.Height)*sy>=image.height*.020);
  const values=[...new Set(candidates.map(word=>+word.WordText))];if(values.length!==1)continue;
  const word=candidates[0],proof={kind:'strength-badge',club:detected.club,value:values[0],box:{left:word.Left,right:word.Left+word.Width,top:word.Top,bottom:word.Top+word.Height}};
  match[detected.field]=values[0];parsed._evidence={...parsed._evidence,[detected.field]:proof};meta.matchEvidence=parsed._evidence;
  if(!meta._headerFields.includes(detected.field))meta._headerFields.push(detected.field);
  if(!meta._ocrFields.includes(detected.field))meta._ocrFields.push(detected.field);
 }
 for(const field of ['myStrength','rivalStrength']){
  const proof=parsed._evidence?.[field]?.kind==='strength-badge'?parsed._evidence[field]:parsed._strengthAreas?.[field];if(!proof?.club||!proof.box)continue;
  const raw=proof.box,areaOnly=proof.kind==='strength-badge-area',padX=areaOnly?0:Math.max(2,(raw.right-raw.left)*sx*.14),padY=areaOnly?0:Math.max(2,(raw.bottom-raw.top)*sy*.15);
  const box={left:Math.max(0,raw.left*sx-padX),right:Math.min(image.width,raw.right*sx+padX),top:Math.max(0,raw.top*sy-padY),bottom:Math.min(image.height,raw.bottom*sy+padY)};
  if(!opaqueBox(image,box))continue;
  let digits=recognizeDigitNumber(image,box,'light');
  if(!digits&&!areaOnly&&parsed._strengthAreas?.[field]?.box){
   const full=parsed._strengthAreas[field].box;
   digits=recognizeDigitNumber(image,{left:full.left*sx,right:full.right*sx,top:full.top*sy,bottom:full.bottom*sy},'light');
  }
  const full=parsed._strengthAreas?.[field]?.box,circleBox=full?{left:full.left*sx,right:full.right*sx,top:full.top*sy,bottom:full.bottom*sy}:box;
  const observedValue=match[field]??null,agreesWithOcr=digits&&digits.value===observedValue;
  const reason=!digits?'FONT_OR_CROP_UNRECOGNISED':observedValue!==null&&!agreesWithOcr?'LITERAL_TEMPLATE_DISAGREEMENT':'NUMERIC_CORROBORATION_REQUIRED';
  // A high template score measures resemblance, not the correctness of a
  // numeral. Compression can make 6/9 resemble 0 almost perfectly. Every
  // identified circle gets independent crop OCR; a conflicting template is
  // diagnostic only and must never replace the club-oriented literal value.
  meta.localOcrRegions=[...(meta.localOcrRegions||[]),{field,club:proof.club,kind:'strength',box:circleBox,observedValue,nativeValue:digits?.value??null,nativeConfidence:digits?.confidence??null,reason}];
  if(agreesWithOcr&&digits.value>0&&digits.value<=400){
   meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'strength-numerals',field,club:proof.club,value:observedValue,confidence:digits.confidence,box,corroboration:'club-oriented-literal'}];
  }else{
   meta.digitDiagnostics=[...(meta.digitDiagnostics||[]),{field,reason,observedValue,nativeValue:digits?.value??null,nativeConfidence:digits?.confidence??null,box:circleBox}];
  }
  // An actually empty circle can disprove the same OCR guess; a font mismatch
  // or a different template candidate cannot disprove a visible numeral.
  if(match[field]!=null&&opaqueBox(image,circleBox)&&fraction(image,circleBox,(r,g,b)=>Math.min(r,g,b)>145&&Math.max(r,g,b)-Math.min(r,g,b)<65)<.003){
   meta.pendingMatchFacts=[...(meta.pendingMatchFacts||[]),{field,value:match[field],reason:'Nenhum dígito visível na área identificada do círculo de força.',source:'OCR do círculo da equipa'}];
   delete match[field];meta._headerFields=meta._headerFields.filter(key=>key!==field);meta._ocrFields=meta._ocrFields.filter(key=>key!==field);
  }
 }
 for(const badge of versusCircles(image,parsed,'bonus')){
  if(!opaqueBox(image,badge.box))continue;
  meta.localOcrRegions=[...(meta.localOcrRegions||[]),{...badge,kind:'bonus',reason:'VISIBLE_PERCENTAGE_REQUIRES_LITERAL'}];
  const words=matchOcrRows(ocr).flatMap(row=>row.words).filter(word=>{
   const text=String(word.WordText||'').trim(),box={left:Number(word.Left)*sx,right:(Number(word.Left)+Number(word.Width))*sx,top:Number(word.Top)*sy,bottom:(Number(word.Top)+Number(word.Height))*sy};
   return /^[+%\d]+$/.test(text)&&box.left>=badge.box.left-2&&box.right<=badge.box.right+2&&box.top>=badge.box.top-2&&box.bottom<=badge.box.bottom+2;
  }).sort((a,b)=>Number(a.Left)-Number(b.Left));
  const literal=words.map(word=>String(word.WordText||'').trim()).join('');
  if(!/^\+\d{1,3}%$/.test(literal)||words.some(word=>!Number.isFinite(Number(word.Confidence??ocr.confidence))||Number(word.Confidence??ocr.confidence)<50))continue;
  const value=Number(literal.slice(1,-1));if(value>100)continue;match[badge.field]=value;
  if(!meta._headerFields.includes(badge.field))meta._headerFields.push(badge.field);
  if(!meta._ocrFields.includes(badge.field))meta._ocrFields.push(badge.field);
  meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'bonus-percentage',field:badge.field,club:badge.club,value,confidence:.96,box:badge.box,ink:badge.ink,corroboration:'plus-and-percentage-with-literal'}];
 }
 if(parsed._versus)for(const field of ['myBonus','rivalBonus']){
  if(!parsed._headerFields?.includes(field)||match[field]==null||meta.iconEvidence?.some(icon=>icon.kind==='bonus-percentage'&&icon.field===field))continue;
  meta.pendingMatchFacts=[...(meta.pendingMatchFacts||[]),{field,value:match[field],reason:'Percentual não confirmado no círculo de bônus identificado na imagem original.',source:'OCR do círculo de bônus'}];
  delete match[field];meta._headerFields=meta._headerFields.filter(key=>key!==field);meta._ocrFields=meta._ocrFields.filter(key=>key!==field);
 }
 if(parsed._weakHeaderFields?.includes('human')&&parsed._evidence?.human?.kind==='manager-line'){
  const proof=parsed._evidence.human,raw=proof.box;
  if(raw){
   const box={left:raw.left*sx,right:raw.right*sx,top:raw.top*sy,bottom:raw.bottom*sy};
   const letters=opaqueBox(image,box)?components(image,box,white).filter(part=>part.h>=image.height*.012&&part.h<image.height*.04&&part.w>=2&&part.w/part.h<1.1):[];
   // Country flags alone have small stars and horizontal stripes. Several
   // actual letter-height glyphs on one baseline prove the manager text is
   // present, even when OCR cannot establish the precise username spelling.
   if(letters.length>=3&&letters.some(part=>letters.filter(other=>Math.abs(part.bottom-other.bottom)<image.height*.01).length>=3)){
    match.human=true;if(!meta._headerFields.includes('human'))meta._headerFields.push('human');
    meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'human-visible-manager-line',club:proof.club,confidence:.95,box}];
   }else delete match.human;
  }
 }
 if(parsed._nicknameArea&&(!match.rivalNickname||parsed._weakHeaderFields?.includes('rivalNickname'))){
  const raw=parsed._nicknameArea,area={...raw,left:raw.left*sx,right:raw.right*sx,top:raw.top*sy,bottom:raw.bottom*sy};
  const emptyName=visiblyEmptyManagerLine(image,area);
  if(emptyName){
   match.human=false;
   if(parsed._weakHeaderFields?.includes('rivalNickname'))delete match.rivalNickname;
   meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'cpu-empty-manager-line',club:area.club,...emptyName}];
  }
 }
 const rows=matchOcrRows(ocr).map(row=>({...row,left:row.left*sx,right:row.right*sx,top:row.top*sy,bottom:row.bottom*sy}));
 let label=rows.find(row=>/analista de dados/.test(normalize(row.text))&&row.left>image.width*.65&&row.right<image.width*.83&&row.top>image.height*.20&&row.bottom<image.height*.37);
 // The rival scout lock has a fixed panel location in this identified header.
 // Its actual three-part glyph can remain visible when OCR misses its label.
 if(!label&&parsed._rivalHeader)label={left:image.width*.694,right:image.width*.81,top:image.height*.306,bottom:image.height*.335};
 if(label&&match.rivalName&&(!context.myTeam||normalize(match.rivalName)!==normalize(context.myTeam))){
  const lock=padlock(image,label);if(lock){match.secretTraining='Sim';meta.rivalReportLocked=true;meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'padlock',...lock}];}
 }
 // OCR sometimes groups the label and neighbouring text into one row. Locate
 // the label word itself; the colour is still established by the thermometer.
 const wordLabels=rows.flatMap(row=>(row.words||[]).map(word=>({text:String(word.WordText||''),left:Number(word.Left)*sx,right:(Number(word.Left)+Number(word.Width))*sx,top:Number(word.Top)*sy,bottom:(Number(word.Top)+Number(word.Height))*sy})));
 let referee=wordLabels.find(word=>/^arbitro\s*[:.;]?$/i.test(normalize(word.text))&&word.left>image.width*.35&&word.right<image.width*.72&&word.top>image.height*.30&&word.bottom<image.height*.68);
 const refereeIcon=(referee?thermometer(image,referee):null)||refereeBesideAvatar(image,parsed);
 if(refereeIcon){match.referee=refereeIcon.color;meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'referee-thermometer',confidence:refereeIcon.confidence,box:refereeIcon.box}];}
 return {match,meta};
}
export function detectMatchIcons(canvas,evidence={}){
 try{return detectMatchPixels(canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),evidence);}catch{return {match:{},meta:{}};}
}
