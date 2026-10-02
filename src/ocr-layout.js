import {normalize} from './utils.js';
import {blankExtraction} from './extraction.js';
import {parseCalendarWords} from './calendar-ocr.js';
const mapped={PL:'ATA',AV:'ATA',EE:'ATA',ED:'ATA',MC:'MEI',MCD:'MEI',MCO:'MEI',MD:'MEI',ME:'MEI',DE:'DEF',DD:'DEF',DC:'DEF',GR:'GOL',GK:'GOL'};
export function overlayWords(lines=[]){return lines.flatMap(line=>(line.Words||[]).map(w=>({text:String(w.WordText||''),x:Number(w.Left),y:Number(w.Top??line.MinTop),w:Number(w.Width),h:Number(w.Height??line.MaxHeight)}))).filter(w=>w.text.trim()&&[w.x,w.y,w.w,w.h].every(Number.isFinite));}
export function parseSquadOverlay(lines,width,height){
 if(width/height<1.8||width/height>2.6)return [];
 const words=overlayWords(lines);const ages=words.filter(w=>w.x/width>=.535&&w.x/width<.58&&/^\d{2}$/.test(w.text)&&+w.text>=15&&+w.text<=60);
 const rows=[];
 for(const age of ages){
 const center=age.y+age.h/2;const row=words.filter(w=>Math.abs(w.y+w.h/2-center)<Math.max(12,age.h*.7));
 const at=(min,max)=>row.filter(w=>w.x/width>=min&&w.x/width<max).sort((a,b)=>a.x-b.x);
 const name=at(.035,.49).map(w=>w.text).join(' ').replace(/^\d+\s*/,'').trim();
 if(!name||/^(jogador|medios|defesas|avancados|guarda)/.test(normalize(name)))continue;
 const positionToken=at(.605,.65).map(w=>w.text).join('').toUpperCase();
 const position=mapped[positionToken]||'NI';
 const sector=position==='ATA'?[.65,.683]:position==='MEI'?[.72,.75]:['DEF','GOL'].includes(position)?[.687,.715]:null;
 const strength=sector?at(...sector).find(w=>/^\d{1,3}$/.test(w.text))?.text:null;
 const value=at(.91,1).map(w=>w.text).join('').replace(/^[^\d]+/,'');
 rows.push({id:crypto.randomUUID(),name,position,age:+age.text,strength:strength?+strength:null,value:/^\d+(?:[.,]\d+)?(?:M|MM|K|B)$/i.test(value)?value:'NI',training:null,forSale:null,_rowY:center/height});
 }
 return rows;
}
export function parseCalendarOverlay(lines,width,height,layout={}){
 return parseCalendarWords(overlayWords(lines),width,height,layout);
}
export function overlayExtraction(type,ocr,width,height,layout={}){
 const out=blankExtraction();
 if(type==='squad')out.players=parseSquadOverlay(ocr.lines,width,height).map(p=>({...p,_source:'OCR.space posição da linha'}));
 if(type==='calendar')out.calendar=parseCalendarOverlay(ocr.lines,width,height,layout).map(r=>({...r,_source:'OCR.space card'}));
 out.sources=['OCR.space'];return out;
}
export function structuredOcr(type,ocr,width,height,layout={}){
 const rows=type==='squad'?parseSquadOverlay(ocr.lines,width,height):type==='calendar'?parseCalendarOverlay(ocr.lines,width,height,layout):[];
 return JSON.stringify({layout:'paisagem OSM',rows,ocrText:ocr.text});
}
