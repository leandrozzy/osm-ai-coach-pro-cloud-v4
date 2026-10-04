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
 const textWords=(ocr.lines||[]).flatMap(line=>(line.Words||[]).map(word=>({...word,Top:Number(word.Top??line.MinTop),Height:Number(word.Height??line.MaxHeight)}))).filter(word=>Number(word.Left)>=width*.50&&word.Height>=height*.008&&word.Height<=height*.06);
 // A focused recovery pass overlaps the first pass. Keep one instance of
 // each literal word at that position, otherwise "À zona À zona" stops
 // matching the value even though both OCR readings were correct.
 const distinct=[];
 for(const word of textWords.sort((a,b)=>(Number(b.Confidence)||0)-(Number(a.Confidence)||0))){
  const duplicate=distinct.some(other=>{
   if(normalize(other.WordText)!==normalize(word.WordText))return false;
   const overlapX=Math.max(0,Math.min(Number(other.Left)+Number(other.Width),Number(word.Left)+Number(word.Width))-Math.max(Number(other.Left),Number(word.Left)));
   const overlapY=Math.max(0,Math.min(other.Top+other.Height,word.Top+word.Height)-Math.max(other.Top,word.Top));
   return overlapX*overlapY>=Math.min(Number(other.Width)*other.Height,Number(word.Width)*word.Height)*.6;
  });
  if(!duplicate)distinct.push(word);
 }
 const textLines=distinct.map(word=>({Words:[word],MinTop:word.Top,MaxHeight:word.Height}));
 const rows=matchOcrRows({...ocr,lines:textLines}).filter(row=>row.left>=width*.50&&row.right<=width&&row.top>=0&&row.bottom<=height);
 const out={};
 const clean=text=>normalize(text).replace(/[.:;]+$/g,'').trim();
 const valueBelow=(caption,accept)=>{
  const center=(caption.left+caption.right)/2;
  const candidates=rows.filter(row=>row!==caption&&row.top>=caption.bottom-height*.012&&row.top-caption.bottom<height*.10&&Math.abs((row.left+row.right)/2-center)<width*.075&&accept(clean(row.text).replace(/\s+/g,'')));
  candidates.sort((a,b)=>a.top-b.top);
  const values=[...new Set(candidates.map(row=>accept(clean(row.text).replace(/\s+/g,''))))];
  return values.length===1?values[0]:null;
 };
 // OCR engines can put a caption and its value in one row, or split the
 // longer offside caption in two rows. Read those variants in their own
 // column; joining the complete panel's text crosses into the other control.
 const captions=pattern=>rows.flatMap(row=>{
  const match=clean(row.text).match(pattern);
  if(match)return [{...row,inline:match[1]||''}];
  return rows.filter(next=>next!==row&&next.top>=row.bottom-height*.008&&next.top-row.bottom<height*.035&&Math.abs((next.left+next.right-row.left-row.right)/2)<width*.025).flatMap(next=>{
   const joined=clean(row.text+' '+next.text).match(pattern);
   return joined?[{...row,bottom:next.bottom,left:Math.min(row.left,next.left),right:Math.max(row.right,next.right),inline:joined[1]||''}]:[];
  });
 });
 const readControl=(field,pattern,accept)=>{
  const values=[...new Set(captions(pattern).map(caption=>caption.inline?accept(caption.inline.replace(/\s+/g,'')):valueBelow(caption,accept)).filter(Boolean))];
  if(values.length===1)out[field]=values[0];
 };
 readControl('rivalMarking',/^marca[cg¢]ao\s*[:\-]?\s*(.*)$/,text=>/^(?:azona|zona)$/.test(text)?'À zona':text==='individual'?'Individual':null);
 readControl('rivalOffside',/^(?:fazer\s+)?(?:fora[- ]de[- ]jogo|impedimento)\s*[:\-]?\s*(.*)$/,text=>text==='sim'?'Sim':text==='nao'?'Não':null);
 const plans=[...new Set(rows.map(row=>parsePlan(row.text)).filter(value=>value!==NI))];
 if(plans.length===1)out.rivalPlan=plans[0];
 return out;
}

/** A faint caption needs another OCR pass, not a guessed tactical value.
 * These bounded areas contain only the report's actual two control captions.
 * The caller retains the original narrative to keep ownership on this frame. */
export function reportControlRegions(ocr={},width,height,context={}){
 if(!(width>0&&height>0&&width/height>=1.8&&width/height<=2.6)||!parseRivalReportText(ocr.text||'',context).rivalName)return [];
 const found=parseReportControls(ocr,width,height,context);
 return [['rivalMarking','marcação do relatório',.62,.73],['rivalOffside','impedimento do relatório',.91,.995]].filter(([field])=>!found[field]).map(([field,name,left,right])=>({field,name,left,top:.63,right,bottom:.77,gamma:2,pageSegMode:6,scale:3,x:Math.floor(left*width),y:Math.floor(.63*height),width:Math.ceil((right-left)*width),height:Math.ceil(.14*height)}));
}
