import {normalize} from './utils.js';
import {blankExtraction} from './extraction.js';
import {parseCalendarWords} from './calendar-ocr.js';
const mapped={PL:'ATA',AV:'ATA',EE:'ATA',ED:'ATA',MC:'MEI',MCD:'MEI',MCO:'MEI',MD:'MEI',ME:'MEI',DE:'DEF',DD:'DEF',DC:'DEF',GR:'GOL',GK:'GOL'};
export function overlayWords(lines=[]){return lines.flatMap(line=>(line.Words||[]).map(w=>({text:String(w.WordText||''),x:Number(w.Left),y:Number(w.Top??line.MinTop),w:Number(w.Width),h:Number(w.Height??line.MaxHeight)}))).filter(w=>w.text.trim()&&[w.x,w.y,w.w,w.h].every(Number.isFinite));}
export function parseSquadOverlay(lines,width,height){
 if(width/height<1.8||width/height>2.6)return [];
 const words=overlayWords(lines),money=/^\d+(?:[.,]\d+)?(?:M|MM|K|B)$/i;
 // A value can anchor a row when the age was missed. Missing age never discards a name.
 const anchors=words.filter(w=>w.y/height>.16&&((w.x/width>=.535&&w.x/width<.58&&/^\d{2}$/.test(w.text)&&+w.text>=15&&+w.text<=60)||(w.x/width>=.91&&money.test(w.text)))).sort((a,b)=>a.y-b.y);
 const sections=words.filter(w=>w.x/width>.44&&w.x/width<.57&&/^(avancados|medios|defesas|guarda[- ]?redes)$/i.test(normalize(w.text))).map(w=>({y:w.y,position:/^avancados/.test(normalize(w.text))?'ATA':/^medios/.test(normalize(w.text))?'MEI':/^defesas/.test(normalize(w.text))?'DEF':'GOL'}));
 const rows=[];
 for(const anchor of anchors){
 const center=anchor.y+anchor.h/2;if(rows.some(r=>Math.abs(r._rowY*height-center)<Math.max(12,anchor.h*.7)))continue;
 const row=words.filter(w=>Math.abs(w.y+w.h/2-center)<Math.max(12,anchor.h*.7));
 const at=(min,max)=>row.filter(w=>w.x/width>=min&&w.x/width<max).sort((a,b)=>a.x-b.x);
 const name=at(.035,.49).filter(w=>/[\p{L}]/u.test(w.text)).map(w=>w.text).join(' ').replace(/^\d+\s*/,'').trim();
 if(!name||/^(jogador|medios|defesas|avancados|guarda)/.test(normalize(name)))continue;
 const positionToken=at(.605,.65).map(w=>w.text).join('').toUpperCase();
 const section=sections.filter(s=>s.y<center).sort((a,b)=>b.y-a.y)[0];const position=mapped[positionToken]||section?.position||'NI';
 const sector=position==='ATA'?[.65,.683]:position==='MEI'?[.72,.75]:['DEF','GOL'].includes(position)?[.687,.715]:null;
 const strength=sector?at(...sector).find(w=>/^\d{1,3}$/.test(w.text))?.text:null;
 const age=at(.535,.58).find(w=>/^\d{2}$/.test(w.text)&&+w.text>=15&&+w.text<=60)?.text;
 const value=at(.91,1).map(w=>w.text).join('').replace(/^[^\d]+/,'');
 rows.push({id:crypto.randomUUID(),name,position,age:age?+age:null,strength:strength?+strength:null,value:money.test(value)?value:'NI',training:null,forSale:null,_rowY:center/height});
 }
 return rows;
}
export function parseSquadMeta(lines,width,height){
 if(width/height<1.8||width/height>2.6)return {};
 const words=overlayWords(lines),money=/^\d+(?:[.,]\d+)?(?:M|MM|K|B)$/i,out={};
 const cash=words.find(w=>w.y/height<.085&&w.x/width>.12&&w.x/width<.26&&money.test(w.text));if(cash)out.cash=cash.text;
 const value=words.find(w=>w.y/height>.10&&w.y/height<.18&&w.x/width>.91&&money.test(w.text));if(value)out.squadValue=value.text;
 const club=words.filter(w=>w.y/height>.20&&w.y/height<.235&&w.x/width>.035&&w.x/width<.44&&/\p{L}/u.test(w.text)).sort((a,b)=>a.x-b.x);
 if(club.length)out.team=club.map(w=>w.text).join(' ');
 const circles=[['GK',.62,.647],['DEF',.665,.691],['MID',.708,.735],['ATT',.752,.778]].map(([key,min,max])=>({key,word:words.find(w=>w.y/height>.245&&w.y/height<.32&&w.x/width>min&&w.x/width<max&&/^\d{1,3}$/.test(w.text)&&+w.text>0&&+w.text<=400)}));
 if(circles.every(c=>c.word)&&Math.max(...circles.map(c=>c.word.y))-Math.min(...circles.map(c=>c.word.y))<height*.025)for(const c of circles)out[c.key]=+c.word.text;
 const strength=words.find(w=>w.y/height>.28&&w.y/height<.39&&w.x/width>.81&&w.x/width<.88&&/^\d{1,3}$/.test(w.text)&&+w.text>0&&+w.text<=400);if(strength&&out.GK)out.strength=+strength.text;
 const total=words.find(w=>/^\d+$/.test(w.text)&&words.some(label=>/^jogadores$/i.test(label.text)&&Math.abs(label.y-w.y)<Math.max(label.h,w.h)&&w.x>label.x&&w.x<label.x+width*.12));if(total&&+total.text>=1&&+total.text<=100)out.expectedPlayers=+total.text;
 out.visibleNames=parseSquadOverlay(lines,width,height).map(r=>r.name);return out;
}
export function parseCalendarOverlay(lines,width,height,layout={}){
 return parseCalendarWords(overlayWords(lines),width,height,layout);
}
export function overlayExtraction(type,ocr,width,height,layout={}){
 const out=blankExtraction();
 if(type==='squad'){out.players=parseSquadOverlay(ocr.lines,width,height).map(p=>({...p,_source:'OCR.space posição da linha'}));out.meta=parseSquadMeta(ocr.lines,width,height);}
 if(type==='calendar')out.calendar=parseCalendarOverlay(ocr.lines,width,height,layout).map(r=>({...r,_source:'OCR.space card'}));
 out.sources=['OCR.space'];return out;
}
export function structuredOcr(type,ocr,width,height,layout={}){
 const rows=type==='squad'?parseSquadOverlay(ocr.lines,width,height):type==='calendar'?parseCalendarOverlay(ocr.lines,width,height,layout):[];
 return JSON.stringify({layout:'paisagem OSM',rows,...(type==='squad'?{meta:parseSquadMeta(ocr.lines,width,height)}:{}),ocrText:ocr.text});
}
