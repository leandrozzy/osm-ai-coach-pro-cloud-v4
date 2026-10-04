const RED=['0000011000000000','0000011000000000','0000111000000000','0011111100000000','0011111111111111','0011111111111111','0111111111111111','0111111111111111','0111111111111111','1111111111111111','0111111111111111','0111111111111111','0011111100000000','0001111000000000','0000111000000000','0000011000000000'];
const GREEN=['0000000000100000','0000000001110000','0000000001111000','0000000001111000','0111111111111100','1111111111111100','1111111111111110','1111111111111111','1111111111111111','1111111111111110','1111111111111110','1111111111111100','0111111111111000','0000000001111000','0000000001110000','0000000001100000'];
function sample(image,box,predicate){
 const x0=Math.floor(box.left),x1=Math.ceil(box.right),y0=Math.floor(box.top),y1=Math.ceil(box.bottom);
 if(x0<0||x1>image.width||y0<image.height*.09||y1>image.height)return null;
 let count=0,white=0,total=0;const points=new Set();
 for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){const i=(y*image.width+x)*4,r=image.data[i],g=image.data[i+1],b=image.data[i+2];if(image.data[i+3]<200)return null;if(predicate(r,g,b)){count++;points.add(y*image.width+x);}if(r>220&&g>215&&b>180)white++;total++;}
 return {count,total,whiteFraction:white/total,points};
}
function components(points,width){
 points=new Set(points);const out=[];
 while(points.size){const seed=points.values().next().value;points.delete(seed);const queue=[seed],pixels=new Set();let left=width,right=0,top=Infinity,bottom=0;
  while(queue.length){const p=queue.pop(),x=p%width,y=Math.floor(p/width);pixels.add(p);left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);for(const next of [p-1,p+1,p-width,p+width])if(points.has(next)&&Math.abs(next%width-x)<=1){points.delete(next);queue.push(next);}}
  out.push({pixels,left,right,top,bottom,w:right-left+1,h:bottom-top+1});
 }return out;
}
function match(component,template,width){let intersection=0,union=0;for(let y=0;y<16;y++)for(let x=0;x<16;x++){const xx=component.left+Math.min(component.w-1,Math.floor((x+.5)*component.w/16)),yy=component.top+Math.min(component.h-1,Math.floor((y+.5)*component.h/16));const a=component.pixels.has(yy*width+xx),b=template[y][x]==='1';if(a&&b)intersection++;if(a||b)union++;}return union?intersection/union:0;}
export function detectSquadPixels(image,row={}){
 const out={training:null,forSale:null,confidence:{training:0,forSale:0}},w=image?.width,h=image?.height;
 if(!image?.data||!w||!h||w/h<1.8||w/h>2.6||!Number.isFinite(Number(row._rowY)))return out;
 const y=Number(row._rowY)*h;if(y<h*.12||y>h*.97)return out;
 const shirt={left:w*.007,right:w*.036,top:y-h*.028,bottom:y+h*.028};
 const orange=sample(image,shirt,(r,g,b)=>r>185&&g>80&&g<205&&b<110&&r>g*1.13);
 const foreground=sample(image,shirt,(r,g,b)=>Math.max(r,g,b)<238||Math.max(r,g,b)-Math.min(r,g,b)>35);
 if(orange&&foreground){
  const body=components(foreground.points,w).sort((a,b)=>b.pixels.size-a.pixels.size)[0];
  if(body&&body.w>w*.014&&body.w<w*.028&&body.h>h*.032&&body.h<h*.059&&body.pixels.size>shirtArea(shirt)*.13){
   if(orange.count>foreground.count*.17&&orange.count>shirtArea(shirt)*.065){out.training=true;out.confidence.training=.96;}
   else if(orange.count<foreground.count*.12&&orange.count<shirtArea(shirt)*.08){out.training=false;out.confidence.training=.94;}
  }
 }
 const slot={left:w*.024,right:w*.049,top:y+h*.007,bottom:y+h*.046};
 const red=sample(image,slot,(r,g,b)=>r>170&&g<100&&b<100),green=sample(image,slot,(r,g,b)=>g>140&&r<150&&b<110);
 if(red&&green){
  const arrows=(reading,template)=>components(reading.points,w).filter(c=>c.w>w*.006&&c.w<w*.017&&c.h>h*.006&&c.h<h*.024&&c.w/c.h>1.2&&c.w/c.h<2.7&&match(c,template,w)>.68);
  const reds=arrows(red,RED),greens=arrows(green,GREEN);
  if(reds.some(a=>greens.some(b=>b.left>a.left&&b.left-a.left<w*.017&&b.top>a.top&&b.top-a.top<h*.025))){out.forSale=true;out.confidence.forSale=.97;}
  else {const outsideShirt=reading=>[...reading.points].filter(p=>p%w>shirt.right||Math.floor(p/w)>shirt.bottom).length;if(outsideShirt(red)<red.total*.004&&outsideShirt(green)<green.total*.004&&red.whiteFraction>.77){out.forSale=false;out.confidence.forSale=.94;}}
 }
 return out;
}
function shirtArea(box){return (box.right-box.left)*(box.bottom-box.top);}
export function detectSquadIcons(canvas,row){try{return detectSquadPixels(canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),row);}catch{return {training:null,forSale:null,confidence:{training:0,forSale:0}};}}

/**
 * Make one small OCR page from the actual numerals inside the observed shirts.
 * Table attributes, ages and names never enter this page. The compact layout
 * also avoids spending an OCR call on every player.
 */
export function squadShirtOcrImage(image,players=[]){
 const width=image?.width,height=image?.height;
 if(!image?.data||!width||!height||width/height<1.8||width/height>2.6)return null;
 const rows=[],unit=height/1080,slotWidth=50,slotHeight=33,scale=2;
 for(const row of players){
  const center=Number(row._rowY)*height;
  if(!Number.isFinite(center)||center<height*.12||center+height*.01>=height)continue;
  const shirt={left:width*.007,right:width*.036,top:center-height*.028,bottom:center+height*.028};
  const foreground=sample(image,shirt,(r,g,b)=>Math.max(r,g,b)<238||Math.max(r,g,b)-Math.min(r,g,b)>35);
  const body=foreground&&components(foreground.points,width).sort((a,b)=>b.pixels.size-a.pixels.size)[0];
  if(!body||body.w<=width*.014||body.w>=width*.028||body.h<=height*.032||body.h>=height*.059||body.pixels.size<=shirtArea(shirt)*.13)continue;
  let saturated=0,samples=0;
  for(let y=Math.floor(center+height*.009);y<Math.min(height,Math.ceil(center+height*.015));y++)for(let x=Math.floor(width*.019);x<Math.ceil(width*.025);x++){
   const i=(y*width+x)*4,r=image.data[i],g=image.data[i+1],b=image.data[i+2];
   samples++;if(Math.max(r,g,b)-Math.min(r,g,b)>75)saturated++;
  }
  const threshold=saturated>samples*.6?145:205;
  const native={left:Math.ceil(width*.016),right:Math.ceil(width*.0285),top:Math.floor(center-height*.018),bottom:Math.ceil(center+height*.0085)};
  const ink=new Set();
  for(let y=native.top;y<native.bottom;y++)for(let x=native.left;x<native.right;x++){
   const i=(y*width+x)*4,r=image.data[i],g=image.data[i+1],b=image.data[i+2];
   if(image.data[i+3]<240){ink.clear();break;}
   if(Math.min(r,g,b)>threshold&&Math.max(r,g,b)-Math.min(r,g,b)<65)ink.add(y*width+x);
  }
  const parts=components(ink,width).filter(part=>!(part.h<height*.0067&&part.bottom<center-height*.010)).sort((a,b)=>a.left-b.left),joined=[];
  for(const part of parts){
   const same=joined.find(other=>part.left<=other.right&&part.right>=other.left);
   if(!same){joined.push(part);continue;}
   for(const point of part.pixels)same.pixels.add(point);
   same.left=Math.min(same.left,part.left);same.right=Math.max(same.right,part.right);same.top=Math.min(same.top,part.top);same.bottom=Math.max(same.bottom,part.bottom);same.w=same.right-same.left+1;same.h=same.bottom-same.top+1;
  }
  const glyphs=joined.filter(part=>part.h>=height*.010&&part.w>=Math.max(1,width*.00035)&&part.pixels.size>=8*unit*unit).sort((a,b)=>a.left-b.left);
  if(!glyphs.length||glyphs.length>2||glyphs.some(part=>part.left<=native.left||part.right>=native.right-1||part.top<=native.top||part.bottom>=native.bottom-1))continue;
  // A two-digit shirt has two separate, aligned glyphs. Noise and a clipped
  // neighbouring digit cannot corroborate a shorter OCR number.
  if(glyphs.some(part=>Math.abs(part.top-glyphs[0].top)>height*.005||Math.abs(part.h-glyphs[0].h)>height*.005))continue;
  const numeralInk=new Set(glyphs.flatMap(part=>[...part.pixels]));
  rows.push({name:row.name,_rowY:row._rowY,native,ink:numeralInk,glyphs});
 }
 if(!rows.length)return null;
 const outWidth=slotWidth*scale,outHeight=slotHeight*rows.length*scale,data=new Uint8ClampedArray(outWidth*outHeight*4);data.fill(255);
 for(let n=0;n<rows.length;n++){
  const row=rows[n],native=row.native,xOffset=(slotWidth-(native.right-native.left)/unit)/2,yOffset=n*slotHeight+3;
  const x0=Math.floor(xOffset*scale),y0=Math.floor(yOffset*scale),w=Math.ceil((native.right-native.left)/unit*scale),h=Math.ceil((native.bottom-native.top)/unit*scale);
  for(let y=0;y<h;y++)for(let x=0;x<w;x++){
   const xx=native.left+Math.floor(x/scale*unit),yy=native.top+Math.floor(y/scale*unit);
   if(row.ink.has(yy*width+xx)){const at=((y0+y)*outWidth+x0+x)*4;data[at]=data[at+1]=data[at+2]=0;}
  }
  const bounds={left:Math.min(...row.glyphs.map(part=>part.left)),right:Math.max(...row.glyphs.map(part=>part.right))+1,top:Math.min(...row.glyphs.map(part=>part.top)),bottom:Math.max(...row.glyphs.map(part=>part.bottom))+1};
  row.box={left:x0+(bounds.left-native.left)/unit*scale,right:x0+(bounds.right-native.left)/unit*scale,top:y0+(bounds.top-native.top)/unit*scale,bottom:y0+(bounds.bottom-native.top)/unit*scale};
  row.digitCount=row.glyphs.length;delete row.ink;delete row.glyphs;
 }
 return {image:{width:outWidth,height:outHeight,data},rows,scale};
}

/** Accept only a complete, confident OCR number covering all native shirt ink. */
export function readSquadShirtNumbers(ocr,prepared){
 if(!prepared?.rows?.length)return [];
 const words=(ocr?.lines||[]).flatMap(line=>(line.Words||[]).map(word=>({text:String(word.WordText||''),left:Number(word.Left),top:Number(word.Top??line.MinTop),width:Number(word.Width),height:Number(word.Height??line.MaxHeight),confidence:Number(word.Confidence)})));
 const result=[];
 for(const row of prepared.rows){
  const b=row.box,within=words.filter(word=>/^\d{1,2}$/.test(word.text)&&Number.isFinite(word.confidence)&&word.confidence>=50&&Number(word.text)>=1&&Number(word.text)<=99&&word.text.length===row.digitCount&&word.left>=b.left-3&&word.left+word.width<=b.right+3&&word.top>=b.top-3&&word.top+word.height<=b.bottom+3&&(word.width/(b.right-b.left))>=.8&&(word.height/(b.bottom-b.top))>=.8);
  const values=new Set(within.map(word=>Number(word.text)));
  if(values.size===1)result.push({name:row.name,shirtNumber:[...values][0]});
 }
 return result;
}
