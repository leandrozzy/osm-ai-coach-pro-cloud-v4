import {recognizeDigitNumber} from './osm-digits.js';

const COLUMNS={ATA:[.65,.683],DEF:[.687,.715],MEI:[.72,.75]};
// OSM prints the player's main attribute in black and the other two in pale gray.
// The selected column is visual evidence; numeric magnitude is never used to choose a role.
export function detectSquadAttributePixels(image,row={}){
 const result={position:null,strength:null},width=image?.width,height=image?.height;
 if(!image?.data||!width||!height||width/height<1.8||width/height>2.6||!Number.isFinite(Number(row._rowY)))return result;
 const y=Number(row._rowY)*height,half=Math.max(height*.009,Math.min(height*.024,Number(row._rowTextHeight||.018)*height*.75));
 if(y-half<height*.10||y+half>=height)return result;
 const readings=Object.entries(COLUMNS).map(([key,[left,right]])=>{
  let dark=0,white=0,total=0,minY=Infinity,maxY=-Infinity,minX=Infinity,maxX=-Infinity;
  for(let yy=Math.floor(y-half);yy<=Math.ceil(y+half);yy++)for(let x=Math.floor(width*left);x<Math.ceil(width*right);x++){
   const index=(yy*width+x)*4,r=image.data[index],g=image.data[index+1],b=image.data[index+2];
   if(image.data[index+3]<240)return {key,dark:0,valid:false};
   total++;
   if(Math.min(r,g,b)>220)white++;
   if(Math.max(r,g,b)<95&&Math.max(r,g,b)-Math.min(r,g,b)<35){dark++;minY=Math.min(minY,yy);maxY=Math.max(maxY,yy);minX=Math.min(minX,x);maxX=Math.max(maxX,x);}
  }
  return {key,dark,valid:white/total>.70&&dark>=Math.max(12,width*height*.000012)&&maxY-minY>=height*.006&&maxX-minX>=width*.003};
 }).sort((a,b)=>b.dark-a.dark);
 const selected=readings[0];
 if(!selected.valid||selected.dark<Math.max(1,readings[1].dark)*3)return result;
 // Defenders and goalkeepers share Def. Their role remains the literal OCR evidence.
 if(selected.key!=='DEF')result.position=selected.key;
 // A perfectly visible numeral can be missed by text OCR (72 was returned as
 // "Te" in the supplied recording). Read only the proven black attribute cell.
 // This small synchronous glyph check never fills an unreadable or clipped cell.
 const [left,right]=COLUMNS[selected.key];
 const observed=recognizeDigitNumber(image,{left:width*left,right:width*right,top:y-half,bottom:y+half},'dark');
 const value=row._attributes?.[selected.key];
 const agrees=Number.isFinite(value)&&observed?.value===value;
 if(observed&&observed.value>=0&&observed.value<=400&&(agrees||observed.confidence>=(Number.isFinite(value) ? .88 : .8)))result.strength=observed.value;
 else if(Number.isFinite(value)&&value>=0&&value<=400)result.strength=value;
 return result;
}
