import {normalize} from './utils.js';
import {blankExtraction} from './extraction.js';
import {parseCalendarWords} from './calendar-ocr.js';
const mapped={PL:'ATA',AV:'ATA',EE:'ATA',ED:'ATA',MC:'MEI',MCD:'MEI',MCO:'MEI',MD:'MEI',ME:'MEI',DE:'DEF',DD:'DEF',DC:'DEF',GR:'GOL',GK:'GOL'};
export function overlayWords(lines=[]){return lines.flatMap(line=>(line.Words||[]).map(w=>({text:String(w.WordText||''),x:Number(w.Left),y:Number(w.Top??line.MinTop),w:Number(w.Width),h:Number(w.Height??line.MaxHeight)}))).filter(w=>w.text.trim()&&[w.x,w.y,w.w,w.h].every(Number.isFinite));}
function rowWords(words,center,tolerance){return words.filter(w=>Math.abs(w.y+w.h/2-center)<tolerance).sort((a,b)=>a.x-b.x);}
function sectionPosition(text){
 const value=normalize(text).replace(/\s+/g,'');
 if(/^avan[cgp]ados$/.test(value))return 'ATA';
 if(value==='medios')return 'MEI';
 if(value==='defesas')return 'DEF';
 if(/^guarda[-]?redes$/.test(value))return 'GOL';
 return null;
}
function squadSections(words,width){
 const candidates=words.filter(w=>w.x/width>.44&&w.x/width<.57),sections=[];
 for(const word of candidates){
  const center=word.y+word.h/2,line=rowWords(candidates,center,Math.max(6,word.h*.55));
  const position=sectionPosition(line.map(w=>w.text).join(' '));
  if(position&&!sections.some(section=>Math.abs(section.y-center)<Math.max(6,word.h*.55)))sections.push({y:center,position});
 }
 return sections.sort((a,b)=>a.y-b.y);
}
const sectorColumns={ATA:[.65,.683],DEF:[.687,.715],MEI:[.72,.75]};
export function parseSquadOverlay(lines,width,height){
 if(width/height<1.8||width/height>2.6)return [];
 const words=overlayWords(lines),money=/^\d+(?:[.,]\d+)?(?:M|MM|K|B)$/i;
 // A value can anchor a row when the age was missed. Missing age never discards a name.
 const anchors=words.filter(w=>w.y/height>.16&&((w.x/width>=.535&&w.x/width<.58&&/^\d{2}$/.test(w.text)&&+w.text>=15&&+w.text<=60)||(w.x/width>=.91&&money.test(w.text)))).sort((a,b)=>a.y-b.y);
 const sections=squadSections(words,width),rows=[];
 for(const anchor of anchors){
  const center=anchor.y+anchor.h/2,tolerance=Math.max(12,anchor.h*.7);
  if(rows.some(r=>Math.abs(r._rowY*height-center)<tolerance))continue;
  const row=rowWords(words,center,tolerance);
  const at=(min,max)=>row.filter(w=>w.x/width>=min&&w.x/width<max);
  const name=at(.035,.49).filter(w=>/[\p{L}]/u.test(w.text)).map(w=>w.text).join(' ').replace(/^\d+\s*/,'').trim();
  if(!name||/^(jogador|medios|defesas|avan[cgp]ados|guarda)/.test(normalize(name)))continue;
  const positionToken=at(.605,.65).map(w=>w.text).join('').replace(/[^A-Za-z]/g,'').toUpperCase();
  const section=sections.filter(s=>s.y<center).at(-1),tokenPosition=mapped[positionToken];
  const position=tokenPosition||section?.position||'NI';
  const attributes=Object.fromEntries(Object.entries(sectorColumns).map(([key,range])=>[key,at(...range).find(w=>/^\d{1,3}$/.test(w.text)&&+w.text<=400)?.text]).filter(([,value])=>value!==undefined).map(([key,value])=>[key,+value]));
  const strength=attributes[position==='GOL'?'DEF':position];
  const age=at(.535,.58).find(w=>/^\d{2}$/.test(w.text)&&+w.text>=15&&+w.text<=60)?.text;
  const value=at(.91,1).map(w=>w.text).join('').replace(/^[^\d]+/,'');
  rows.push({id:crypto.randomUUID(),name,position,age:age?+age:null,strength:strength??null,value:money.test(value)?value:'NI',training:null,forSale:null,_rowY:center/height,_rowTextHeight:anchor.h/height,_attributes:attributes,_positionSource:tokenPosition?'token':section?'section':null});
 }
 return rows;
}
export function parseSquadMeta(lines,width,height){
 if(width/height<1.8||width/height>2.6)return {};
 const words=overlayWords(lines),money=/^\d+(?:[.,]\d+)?(?:M|MM|K|B)$/i,out={};
 const cash=words.find(w=>w.y/height<.085&&w.x/width>.12&&w.x/width<.26&&money.test(w.text));if(cash)out.cash=cash.text;
 const circles=[['GK',.62,.647],['DEF',.665,.691],['MID',.708,.735],['ATT',.752,.778]].map(([key,min,max])=>({key,word:words.find(w=>w.y/height>.245&&w.y/height<.32&&w.x/width>min&&w.x/width<max&&/^\d{1,3}$/.test(w.text)&&+w.text>0&&+w.text<=400)}));
 const alignedCircles=circles.filter(c=>c.word),hasSectors=alignedCircles.length>=3&&Math.max(...alignedCircles.map(c=>c.word.y))-Math.min(...alignedCircles.map(c=>c.word.y))<height*.025;
 const headerLabel=words.some(w=>w.y/height>.11&&w.y/height<.19&&(/^(posi[cg]ao|objetiv[oa]|obyetivo)[:.]?$/i.test(normalize(w.text))));
 const club=words.filter(w=>w.y/height>.20&&w.y/height<.245&&w.x/width>.033&&w.x/width<.44&&/\p{L}/u.test(w.text)).sort((a,b)=>a.x-b.x);
 const clubStart=club[0],clubCenter=clubStart?clubStart.y+clubStart.h/2:0;
 const username=clubStart&&words.find(w=>w.x/width>.033&&w.x/width<.30&&w.y>clubCenter+height*.015&&w.y<clubCenter+height*.06&&/^[\p{L}\p{N}_][\p{L}\p{N}_.-]{2,}$/u.test(w.text));
 // Scrolled player names occupy this same Y band. Only a stationary club header can name the team.
 // Sofia alone starts farther right than the club label and is a truncated name, never a verified club.
 const headerVisible=headerLabel&&(hasSectors||username);
 if(headerVisible&&clubStart&&clubStart.x/width<.053){
  const nameWords=club.filter(w=>w.h>=clubStart.h*.5&&Math.abs(w.y+w.h/2-clubCenter)<Math.max(clubStart.h,w.h)*.7);
  out.team=nameWords.map(w=>w.text).join(' ');out.teamVerified=true;
  if(username)out.username=username.text;
 }
 if(headerVisible){
  const value=words.find(w=>w.y/height>.10&&w.y/height<.18&&w.x/width>.91&&money.test(w.text));if(value)out.squadValue=value.text;
  if(circles.every(c=>c.word)&&hasSectors)for(const c of circles)out[c.key]=+c.word.text;
  const strength=words.find(w=>w.y/height>.28&&w.y/height<.39&&w.x/width>.81&&w.x/width<.88&&/^\d{1,3}$/.test(w.text)&&+w.text>0&&+w.text<=400);if(strength&&out.GK)out.strength=+strength.text;
 }
 const total=words.find(w=>/^\d+$/.test(w.text)&&words.some(label=>/^jogadores$/i.test(label.text)&&Math.abs(label.y-w.y)<Math.max(label.h,w.h)&&w.x>label.x&&w.x<label.x+width*.12));if(total&&+total.text>=1&&+total.text<=100)out.expectedPlayers=+total.text;
 const players=parseSquadOverlay(lines,width,height);
 out.visibleNames=players.map(r=>r.name);
 // The stationary club header followed by the first (attack) section proves
 // the start. Player targets and the number of goalkeepers prove neither end.
 const firstSection=squadSections(words,width)[0];
 if(headerVisible&&out.teamVerified===true&&firstSection?.position==='ATA'&&players.some(row=>row._rowY*height>firstSection.y))out.sawTop=true;
 return out;
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
