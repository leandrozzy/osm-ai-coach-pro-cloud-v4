// Small isolated OSM numeral masks sampled from the supplied native recordings.
// This reader does not load an OCR engine or recognize names, clubs, or hidden values.
const SIZE_X=12,SIZE_Y=20;
const expand=hex=>[...hex].flatMap(n=>Number.parseInt(n,16).toString(2).padStart(4,'0').split('').map(Number));
let prepared;
function templates(mode){
 if(!prepared)prepared=Object.fromEntries(Object.entries(NUMERALS).map(([key,digits])=>[key,Object.entries(digits).flatMap(([digit,variants])=>variants.map(([mask,aspect])=>({digit,bits:expand(mask),aspect})))]));
 return prepared[mode];
}
function belongs(r,g,b,mode){return mode==='light'?Math.min(r,g,b)>145&&Math.max(r,g,b)-Math.min(r,g,b)<65:Math.max(r,g,b)<95&&Math.max(r,g,b)-Math.min(r,g,b)<35;}
function glyphScore(bits,aspect,template){
 let intersection=0,union=0;
 for(let i=0;i<bits.length;i++){if(bits[i]&&template.bits[i])intersection++;if(bits[i]||template.bits[i])union++;}
 return (union?intersection/union:0)-Math.min(.25,Math.abs(aspect-template.aspect)*.28);
}
/** Read one bounded cell. A clipped, joined, faint, or ambiguous glyph stays unknown. */
export function recognizeDigitNumber(image,box={},mode='dark'){
 if(mode==='white')mode='light';
 if(!['dark','light'].includes(mode)||!image?.data||!image.width||!image.height)return null;
 const {width,height,data}=image,left=Math.floor(box.left),right=Math.ceil(box.right),top=Math.floor(box.top),bottom=Math.ceil(box.bottom);
 if(![left,right,top,bottom].every(Number.isFinite)||left<0||top<0||right>width||bottom>height||right<=left||bottom<=top||(right-left)*(bottom-top)>30000)return null;
 const points=new Set(),columns=[];
 for(let x=left;x<right;x++){
  let count=0;
  for(let y=top;y<bottom;y++){
   const i=(y*width+x)*4;if(data[i+3]<240)return null;
   if(belongs(data[i],data[i+1],data[i+2],mode)){points.add(y*width+x);count++;}
  }
  if(count)columns.push(x);
 }
 if(!columns.length)return null;
 const runs=[];let start=columns[0],last=start;
 for(const x of columns.slice(1)){if(x>last+1){runs.push([start,last]);start=x;}last=x;}runs.push([start,last]);
 const glyphs=[];
 for(const [x0,x1] of runs){
  const ys=[];for(let y=top;y<bottom;y++)for(let x=x0;x<=x1;x++)if(points.has(y*width+x))ys.push(y);
  const y0=Math.min(...ys),y1=Math.max(...ys),w=x1-x0+1,h=y1-y0+1;
  // Small dots and compression noise are not numerals. Touching crop edges are partial.
  if(h<Math.max(7,(bottom-top)*.18)||w<2)continue;
  if(x0===left||x1===right-1||y0===top||y1===bottom-1||h>height*.10||w/h>.95||w/h<.12)return null;
  const bits=[];
  for(let yy=0;yy<SIZE_Y;yy++)for(let xx=0;xx<SIZE_X;xx++)bits.push(points.has((y0+Math.min(h-1,Math.floor((yy+.5)*h/SIZE_Y)))*width+x0+Math.min(w-1,Math.floor((xx+.5)*w/SIZE_X)))?1:0);
  const best=new Map();for(const template of templates(mode)){const score=glyphScore(bits,w/h,template);if(score>(best.get(template.digit)||0))best.set(template.digit,score);}
  const ranked=[...best].sort((a,b)=>b[1]-a[1]);
  if(!ranked.length||ranked[0][1]<.76||ranked[0][1]-(ranked[1]?.[1]||0)<.075)return null;
  glyphs.push({digit:ranked[0][0],score:ranked[0][1],left:x0,right:x1,top:y0,bottom:y1,h});
 }
 if(!glyphs.length||glyphs.length>3||glyphs.some(g=>Math.abs(g.top-glyphs[0].top)>glyphs[0].h*.25||Math.abs(g.h-glyphs[0].h)>glyphs[0].h*.25))return null;
 for(let i=1;i<glyphs.length;i++)if(glyphs[i].left-glyphs[i-1].right>glyphs[i].h*.65)return null;
 const digits=glyphs.map(g=>g.digit).join('');if(digits.length>1&&digits[0]==='0')return null;
 return {value:Number(digits),confidence:Math.min(...glyphs.map(g=>g.score)),digits};
}
const NUMERALS={"dark":{"9":[["0f07fe7fff8ff0ff0ff0ff0ff8fffffff7ff3cf00f00f00f00f7fe7fe3f0",0.526],["0f03ff7fff8ff0ff0ff0ff0ff8f7ff7ff7ff3cf00f00f00f00f7ff7fe3e0",0.526],["0fc7fe7fff8ff0ff0ff0ff0ff8fffffff7ff3cf00f00f00f00f7ff7fe3f0",0.526]],"1":[["0ff3fffffffffff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff",0.316]],"0":[["0f03fc7fcf0ef0ef0ef0ff0fe0fe0fe0fe0fe0fe0ff0ef0e70e7fe3fc1f8",0.579]],"8":[["1f07fe7fe70ff0760f70f70e7fc1fc1fc7fe70ff0ff07f07f0f7fe7fe1f0",0.579],["1f87fe7fe70ff07f0770770e3fe3fc3fc7fe70ff07e07f07f0f7ff7fe1f8",0.579],["1f87fe7fe70ff07f0770770e3fe3fc3fc7fe70ff07e07e07f0f7ff7fe1f8",0.579],["1f87fe7fe70ff07f0770770e3fe3fc3fc7fe70ff07e07f07f0f7fe7fe1f8",0.579]],"7":[["fffffffbf01e01e03e03c03c07c0700700700700f00e00e03e03c03c0780",0.526]],"5":[["ffeffeffef00f00f00f00ff8ffeffffff01f00f00f00f00f01ffffffe7f0",0.474]],"2":[["7f0ffefff01f00f00f01f01f07f3fe3fe7fc7e0f80f80f00f00fffffffff",0.526],["7f0ffeffe01f00f00f01f01f07e3fe3fe7fc7e0f80f80f00f00fffffffff",0.526]],"3":[["7e0ffcffe01e01e01e01e01e3fc3f03f03fe01e01e01f01e01effeffe7f0",0.526]],"6":[["0fc3fe7fe700700700e00e78ffeffeffef0ef0ff0ff0770f70e7fe3fe1f8",0.579]],"4":[["1f01e01e01e038038038070c70c70c70cf1ce1cfffffffff01c01c01c01c",0.579]]},"light":{"8":[["3fc7fef0fe07e07e07e07e0770e3fc3fc71ee07e07e07e07e07e077fe3fc",0.578],["3fc7fef0fe07e07e07e07e0770e3fc7fc70ee07e07e07e07e07f077fe3fc",0.578],["3fc3fe70f70ff03c03f0370e7fe7fe7fe70ef03c03c03f0f70e70e7fe0fc",0.588],["1f8ffee0fe0fe07e07e07e0effeffeffee0ee07e07807e0fe0ee0effe1f8",0.529],["3fc7fe70f70ff0ff03f0370e7fe7fe7fe70ef03f03c03f0370f70f7fe3fc",0.588],["3fc3fe703703f03f03f0370e7fe7fe7fe70ef03f03c03f037037037fe3fc",0.588],["3fc3ff703703f03f03f0370f7fe7fe7fe70ff03f03c037037037037fe3fc",0.588],["3fc7fe703703f03c03f0370f7fc7fc7fc70ff03c03f03f0370e70e7fe3fc",0.588]],"6":[["0fc7fe700700700f00f00f7cffef0ff0ff0ff03f03f0370f70e70e7fe0f0",0.588],["0fc3fe700700f00f00f00f7cffef0ff0ff0ff03f03f0370f70e70e7fe0f0",0.588]],"9":[["3fc7fcc0ec0ec0fc0fc0ff0ff0f7ff7ff3ef00f00f00f00e00e00e7fc7f0",0.588],["3f07fcf0ef0ec0ec0ec0ec0ff0f7ff7ff3ef00f00e00e00e00e00e7fc7f0",0.588]],"0":[["3fc7fe70e70ef0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0ff0e70e70e7fe3fc",0.588],["3fc7fef07e07e07e07e07e07e07e07e07e07e07e07e07e07e077bf7fe1f8",0.543]],"5":[["7fe7fee00e00e00e00600ff0ffe00f00f00f00700700700f00f00f7fe7f0",0.529],["7fe7fee00e00e00f00700ff0ffe00f00f00f00700700700f00f00f7fe7f0",0.529]],"4":[["0600e00c00c00c00c039c39c79c71c71c71cc1cffffff01c01c01c01c01c",0.588],["0600f00e01e018018030c30c70c60c60c60c60cc0cffffff00c00c00c008",0.579]],"7":[["ffffff00e00e00e01c01c03c0380380780700700f00e00e01c01c01c0380",0.511],["ffefff00e00e00e0180180180780700700700f00f01e0180180180780780",0.529]],"2":[["7f87fe00f00f00f00700700f01e0780787f0780f00f00e00e00e00ffffff",0.529]],"1":[["07f3ffffffff07f07f07f07f07f07f07f07f07f07f07f07f07f07f07f07f",0.263]],"3":[["ff8ffe00f00700700700700f1fe1f81f800e00700700700700700ff7e7f8",0.474],["ffeffe00f00700700700700f0fe1f81f800f00700700700700700ffffffe",0.474]]}};
