import {normalize} from './utils.js';
import {matchOcrRows,parseMatchOverlay} from './parser-match.js';
const yellow=(r,g,b)=>r>160&&g>110&&b<120&&r>b*1.6&&g>b*1.3;
const white=(r,g,b)=>Math.min(r,g,b)>175&&Math.max(r,g,b)-Math.min(r,g,b)<65;
const dark=(r,g,b)=>Math.max(r,g,b)<150;
function components(image,box,predicate){
 const points=new Set(),left=Math.max(0,Math.floor(box.left)),right=Math.min(image.width,Math.ceil(box.right)),top=Math.max(0,Math.floor(box.top)),bottom=Math.min(image.height,Math.ceil(box.bottom));
 for(let y=top;y<bottom;y++)for(let x=left;x<right;x++){const offset=(y*image.width+x)*4;if(image.data[offset+3]>200&&predicate(image.data[offset],image.data[offset+1],image.data[offset+2]))points.add(y*image.width+x);}
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
function thermometer(image,label){
 const box={left:label.left-image.width*.039,right:label.left-image.width*.001,top:label.top-image.height*.052,bottom:label.bottom+image.height*.035};
 const predicates=[['Verde',(r,g,b)=>g>110&&g>r*1.4&&g>b*1.3],['Azul',(r,g,b)=>b>140&&b>r*1.6&&b>g*1.12],['Amarelo',(r,g,b)=>yellow(r,g,b)&&g>r*.73],['Laranja',(r,g,b)=>r>190&&g>80&&g<r*.73&&b<90],['Vermelho',(r,g,b)=>r>160&&r>g*1.7&&r>b*1.5]];
 for(const [color,predicate] of predicates)for(const part of components(image,box,predicate)){
  if(part.w<image.width*.005||part.w>image.width*.016||part.h<image.height*.029||part.h>image.height*.095||part.h/part.w<1.7||part.h/part.w>7||part.pixels.size/(part.w*part.h)<.5)continue;
  const widths=[];for(let y=part.top;y<part.bottom;y++){let left=image.width,right=-1;for(let x=part.left;x<part.right;x++)if(part.pixels.has(y*image.width+x)){left=Math.min(left,x);right=Math.max(right,x);}widths.push(right>=left?right-left+1:0);}
  const upper=Math.max(...widths.slice(0,Math.max(1,Math.floor(part.h*.4)))),lower=Math.max(...widths.slice(Math.floor(part.h*.65)));
  if(upper>part.w*.7||lower<part.w*.85)continue;
  return {color,confidence:.94,box:{left:part.left,right:part.right,top:part.top,bottom:part.bottom}};
 }
 return null;
}
export function detectMatchPixels(image,evidence={}){
 const empty={match:{},meta:{}};
 if(!image?.data||!image.width||!image.height||image.width/image.height<1.8||image.width/image.height>2.6||evidence.region&&evidence.region!=='full')return empty;
 const width=Number(evidence.width||image.width),height=Number(evidence.height||image.height),ocr=evidence.ocr||{},context=evidence.context||{};
 const parsed=parseMatchOverlay(ocr,width,height,context),match={};for(const [field,value] of Object.entries(parsed))if(!field.startsWith('_')&&value!==null&&value!=='NI'&&value!=='')match[field]=value;
 const meta={_ocrFields:Object.keys(match)},sx=image.width/width,sy=image.height/height;
 const rows=matchOcrRows(ocr).map(row=>({...row,left:row.left*sx,right:row.right*sx,top:row.top*sy,bottom:row.bottom*sy}));
 const label=rows.find(row=>/analista de dados/.test(normalize(row.text))&&row.left>image.width*.65&&row.right<image.width*.83&&row.top>image.height*.20&&row.bottom<image.height*.37);
 if(label&&match.rivalName&&(!context.myTeam||normalize(match.rivalName)!==normalize(context.myTeam))){
  const lock=padlock(image,label);if(lock){match.secretTraining='Sim';meta.rivalReportLocked=true;meta.iconEvidence=[{kind:'padlock',...lock}];}
 }
 const referee=rows.find(row=>/^arbitro$/.test(normalize(row.text))&&row.left>image.width*.47&&row.right<image.width*.57&&row.top>image.height*.40&&row.bottom<image.height*.54);
 if(referee){const found=thermometer(image,referee);if(found){match.referee=found.color;meta.iconEvidence=[...(meta.iconEvidence||[]),{kind:'referee-thermometer',confidence:found.confidence,box:found.box}];}}
 return {match,meta};
}
export function detectMatchIcons(canvas,evidence={}){
 try{return detectMatchPixels(canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),evidence);}catch{return {match:{},meta:{}};}
}
