import {matchOcrRows,parseRivalReportText,parsePlan} from './parser-match.js';
import {normalize,NI} from './utils.js';

/** Restore OCR crop coordinates before pairing captions with their values. */
export function mapOcrRegion(ocr={},image={}){
 const crop=image.crop,width=Number(image.width),height=Number(image.height);
 if(!(width>0&&height>0))return null;
 const nativeWidth=Number(crop?.sourceWidth)||width,nativeHeight=Number(crop?.sourceHeight)||height;
 const left=crop?Number(crop.left):0,top=crop?Number(crop.top):0;
 const regionWidth=crop?Number(crop.width):width,regionHeight=crop?Number(crop.height):height;
 if(![nativeWidth,nativeHeight,left,top,regionWidth,regionHeight].every(Number.isFinite)||nativeWidth<=0||nativeHeight<=0||left<0||top<0||regionWidth<=0||regionHeight<=0||left+regionWidth>nativeWidth+1||top+regionHeight>nativeHeight+1)return null;
 const sx=regionWidth/width,sy=regionHeight/height;
 const lines=(ocr.lines||[]).map(line=>{
  const Words=(line.Words||[]).map(word=>{
   const x=Number(word.Left),y=Number(word.Top??line.MinTop),w=Number(word.Width),h=Number(word.Height??line.MaxHeight);
   if(![x,y,w,h].every(Number.isFinite)||w<=0||h<=0||x<0||y<0||x+w>width+2||y+h>height+2)return null;
   return {...word,Left:left+x*sx,Top:top+y*sy,Width:w*sx,Height:h*sy};
  }).filter(Boolean);
  return {...line,Words,MinTop:Words.length?Math.min(...Words.map(word=>word.Top)):0,MaxHeight:Words.length?Math.max(...Words.map(word=>word.Height)):0};
 }).filter(line=>line.Words.length);
 return {text:ocr.text||'',lines,width:nativeWidth,height:nativeHeight};
}

/** Report controls use two columns: reading order alone swaps or loses values. */
export function parseReportControls(ocr={},width,height,context={}){
 if(!(width>0&&height>0&&width/height>=1.8&&width/height<=2.6))return {};
 const report=parseRivalReportText(ocr.text||'',context);
 if(!report.rivalName)return {};
 // Graphic shapes can be recognised as a huge word and merge unrelated
 // captions into one OCR row. Only text-sized words in this panel are labels.
 const textLines=(ocr.lines||[]).map(line=>({...line,Words:(line.Words||[]).filter(word=>Number(word.Left)>=width*.50&&Number(word.Height??line.MaxHeight)>=height*.008&&Number(word.Height??line.MaxHeight)<=height*.06)}));
 const rows=matchOcrRows({...ocr,lines:textLines}).filter(row=>row.left>=width*.50&&row.right<=width&&row.top>=0&&row.bottom<=height);
 const out={};
 const valueBelow=(caption,accept)=>{
  const center=(caption.left+caption.right)/2;
  const candidates=rows.filter(row=>row.top>=caption.bottom-height*.012&&row.top-caption.bottom<height*.10&&Math.abs((row.left+row.right)/2-center)<width*.075&&accept(normalize(row.text).replace(/\s+/g,'')));
  candidates.sort((a,b)=>a.top-b.top);
  const values=[...new Set(candidates.map(row=>accept(normalize(row.text).replace(/\s+/g,''))))];
  return values.length===1?values[0]:null;
 };
 const marking=rows.find(row=>/^marcacao\s*:?$/.test(normalize(row.text)));
 if(marking){const value=valueBelow(marking,text=>/^(?:azona|zona)$/.test(text)?'À zona':text==='individual'?'Individual':null);if(value)out.rivalMarking=value;}
 const offside=rows.find(row=>/^(?:fazer\s+)?(?:fora[- ]de[- ]jogo|impedimento)\s*:?$/.test(normalize(row.text)));
 if(offside){const value=valueBelow(offside,text=>text==='sim'?'Sim':text==='nao'?'Não':null);if(value)out.rivalOffside=value;}
 const plans=[...new Set(rows.map(row=>parsePlan(row.text)).filter(value=>value!==NI))];
 if(plans.length===1)out.rivalPlan=plans[0];
 return out;
}
