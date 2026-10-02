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
