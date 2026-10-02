// Silhouettes sampled from the user's native Tobol calendar, not inferred from score.
const HOUSE=['0000000010000000','0000001111000000','0000011111100000','0000111111110000','0001111111111000','0011111111111100','0011111111111100','0111111111111110','0111111111111110','0011111111111100','0001111001111000','0001110000111100','0001110000111100','0001110000111100','0001110000111000','0000000000011000'];
const CUP=['0000000100000000','0000111111100000','0111111111111110','1111111111111110','1111111111111110','0111111111111110','0111111111111100','0111111111111111','0001111111110000','0000111111100000','0000011111000000','0000011111000000','0000111111100000','0000111111110000','0001111111110000','0000111111110000'];
const white=(r,g,b)=>Math.min(r,g,b)>175&&Math.max(r,g,b)-Math.min(r,g,b)<55;
function area(image,box,predicate){
 const {data,width,height}=image,x0=Math.floor(box.left),x1=Math.ceil(box.right),y0=Math.floor(box.top),y1=Math.ceil(box.bottom);
 if(x0<0||y0<height*.09||x1>width||y1>height||x1<=x0||y1<=y0)return null;
 const points=new Set();let dark=0,total=0;
 for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){const offset=(y*width+x)*4,r=data[offset],g=data[offset+1],b=data[offset+2];if(data[offset+3]<200)return null;if(predicate(r,g,b))points.add(y*width+x);if(.299*r+.587*g+.114*b<170)dark++;total++;}
 const whiteCount=points.size,components=[];
 while(points.size){const seed=points.values().next().value;points.delete(seed);const queue=[seed],pixels=new Set();let left=width,right=0,top=height,bottom=0;
  while(queue.length){const point=queue.pop(),x=point%width,y=Math.floor(point/width);pixels.add(point);left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
   for(const next of [point-1,point+1,point-width,point+width])if(points.has(next)&&Math.abs(next%width-x)<=1){points.delete(next);queue.push(next);}
  }
  components.push({pixels,left,right,top,bottom,w:right-left+1,h:bottom-top+1});
 }
 return {components,whiteCount,darkFraction:dark/total,total};
}
function silhouette(component,template,width){
 let intersection=0,union=0;
 for(let y=0;y<16;y++)for(let x=0;x<16;x++){
  const px=component.left+Math.min(component.w-1,Math.floor((x+.5)*component.w/16)),py=component.top+Math.min(component.h-1,Math.floor((y+.5)*component.h/16));
  const actual=component.pixels.has(py*width+px),expected=template[y][x]==='1';if(actual&&expected)intersection++;if(actual||expected)union++;
 }
 return union?intersection/union:0;
}
function iconState(image,box,template,cardWidth){
 const found=area(image,box,white);if(!found)return {value:null,confidence:0};
 let score=0;
 for(const c of found.components){if(c.pixels.size<12||c.w<cardWidth*.025||c.w>cardWidth*.105||c.h<cardWidth*.035||c.h>cardWidth*.11)continue;score=Math.max(score,silhouette(c,template,image.width));}
 if(score>=.72)return {value:true,confidence:score};
 // Absence is only evidence when the complete icon slot has visible dark card background.
 if(found.whiteCount<=Math.max(2,found.total*.002)&&found.darkFraction>.90)return {value:false,confidence:.95};
 return {value:null,confidence:0};
}
export function detectCalendarPixels(image,bounds={}){
 const unknown={home:null,cup:null,result:'NI',confidence:{home:0,cup:0,result:0}};
 if(!image?.data||!image.width||!image.height||image.width/image.height<1.8||image.width/image.height>2.6)return unknown;
 const sx=image.width/Number(bounds.width||image.width),sy=image.height/Number(bounds.height||image.height),left=Number(bounds.left)*sx,right=Number(bounds.right)*sx,anchorY=Number(bounds.anchorY)*sy,cardWidth=right-left;
 if(![left,right,anchorY].every(Number.isFinite)||cardWidth<image.width*.14||cardWidth>image.width*.19||anchorY-cardWidth*.075<image.height*.09)return unknown;
 const iconBox=(top,bottom)=>({left:left+cardWidth*.025,right:left+cardWidth*.16,top:anchorY+cardWidth*top,bottom:anchorY+cardWidth*bottom});
 const home=iconState(image,iconBox(-.075,.05),HOUSE,cardWidth),cup=iconState(image,iconBox(.05,.16),CUP,cardWidth);
 const badgeBox={left:left+cardWidth*.84,right:right,top:anchorY-cardWidth*.075,bottom:anchorY+cardWidth*.07};let result='NI',badgeConfidence=0;
 const colors=[['V',(r,g,b)=>g>100&&g>r*1.45&&r<130&&b<100],['E',(r,g,b)=>r>185&&g>85&&g<215&&b<100],['D',(r,g,b)=>r>150&&g<75&&b<85]];
 for(const [value,predicate] of colors){const found=area(image,badgeBox,predicate);if(!found)continue;
  for(const c of found.components){const density=c.pixels.size/(c.w*c.h),ratio=c.w/c.h;if(c.w<cardWidth*.065||c.w>cardWidth*.13||c.h<cardWidth*.065||c.h>cardWidth*.13||ratio<.8||ratio>1.2||density<.5)continue;if(density>badgeConfidence){result=value;badgeConfidence=density;}}
 }
 return {home:home.value,cup:cup.value,result,confidence:{home:home.confidence,cup:cup.confidence,result:badgeConfidence}};
}
export function detectCalendarIcons(canvas,bounds){
 try{return detectCalendarPixels(canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height),bounds);}catch{return {home:null,cup:null,result:'NI',confidence:{home:0,cup:0,result:0}};}
}
