import {cleanCalendar} from './extraction.js';
import {normalize} from './utils.js';

const centerY=w=>w.y+w.h/2;
const centerX=w=>w.x+w.w/2;
const median=values=>{const sorted=[...values].sort((a,b)=>a-b);return sorted.length?sorted[Math.floor(sorted.length/2)]:null;};
const datePattern=/^\d{1,2}[-/]\d{1,2}[-/](?:\d{2}|\d{4})$/;
const timePattern=/^(?:[01]\d|2[0-3]):[0-5]\d$/;
const scorePattern=/^\d{1,2}\s*[x×:\-–—]\s*\d{1,2}$/;
const stagePattern=/^(?:meias? finais?|semi[ -]?finais?|final|quartos?(?: de)? final|oitavos?(?: de)? final|preliminar(?:es)?(?: (?:da |de )?copa)?|qualificacao)$/;
const terminalDay=text=>/^ultimodia$/.test(normalize(text).replace(/\s/g,''));
const seasonSummary=text=>/^resumo(?:da)?(?:epoca|temporada)$/.test(normalize(text).replace(/\s/g,''));
function calendarDate(text){
 const parts=String(text).match(/^(\d{1,2})[-/](\d{1,2})[-/](\d{2}|\d{4})$/);if(!parts)return null;
 const day=+parts[1],month=+parts[2],year=parts[3].length===2?2000+Number(parts[3]):+parts[3],date=new Date(Date.UTC(year,month-1,day));
 if(year<1900||year>2200||date.getUTCFullYear()!==year||date.getUTCMonth()!==month-1||date.getUTCDate()!==day)return null;
 return String(day).padStart(2,'0')+'/'+String(month).padStart(2,'0')+'/'+year;
}
const lineBox=line=>({left:Math.min(...line.words.map(w=>w.x)),right:Math.max(...line.words.map(w=>w.x+w.w)),top:Math.min(...line.words.map(w=>w.y)),bottom:Math.max(...line.words.map(w=>w.y+w.h))});
const confidentLine=line=>line.words.every(word=>!Number.isFinite(word.confidence)||word.confidence>=45);

function groupedLines(words){
 const groups=[];
 for(const w of [...words].sort((a,b)=>centerY(a)-centerY(b)||a.x-b.x)){
  const group=groups.find(g=>Math.abs(g.y-centerY(w))<=Math.max(8,Math.min(g.height,w.h)*.6));
  if(group){group.words.push(w);group.y=median(group.words.map(centerY));group.height=Math.max(group.height,w.h);}
  else groups.push({y:centerY(w),height:w.h,words:[w]});
 }
 return groups.sort((a,b)=>a.y-b.y).map(g=>({...g,text:g.words.sort((a,b)=>a.x-b.x).map(w=>w.text).join(' ').trim()}));
}

function anchorsIn(words,width,columns){
 const anchors=[];
 for(const w of words){
  const combined=normalize(w.text).match(/^jornada\s*(\d{1,2})$/);
  if(combined){anchors.push({round:+combined[1],x:w.x,y:centerY(w),word:w});continue;}
  if(normalize(w.text)!=='jornada')continue;
  const number=words.filter(v=>v.x>w.x+w.w*.8&&v.x<w.x+width/columns*.45&&Math.abs(centerY(v)-centerY(w))<Math.max(14,w.h*.9)).sort((a,b)=>a.x-b.x).find(v=>/^\d{1,2}$/.test(v.text));
  if(number)anchors.push({round:+number.text,x:w.x,y:centerY(w),word:w});
 }
 // In the complete six-column grid, round1 starts at column0. Reject a truncated
 // number that contradicts its column; never supply a missing digit or round.
 return anchors.filter(a=>a.round>0&&a.round<100).map(a=>({...a,column:Math.min(columns-1,Math.max(0,Math.floor(a.x/(width/columns))))})).map(a=>({...a,round:columns===6&&(a.round-1)%columns!==a.column?null:a.round}));
}

function meaningfulName(line,cardHeight){
 const words=line.words.filter(w=>{
  const value=w.text.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}_.'’-]+$/gu,'');
  return w.h>=cardHeight*.015&&w.h<=cardHeight*.13&&(/[\p{L}]/u.test(value)||/^\d{2,4}$/.test(value))&&/[\p{L}\p{N}]{2}/u.test(value)&&!/[<>™®]/u.test(w.text);
 });
 const text=words.map(w=>w.text.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}_.'’-]+$/gu,'')).join(' ').trim();
 return (text.match(/\p{L}/gu)?.length||0)>=3||(/^[A-Z]{2}$/.test(text)&&!['FK','FC'].includes(text))?text:'';
}

function extractCard(words,{round=null,anchorY,cardHeight,left,width}){
 const relative=w=>(centerY(w)-anchorY)/cardHeight;
 const lines=groupedLines(words),compact=line=>line.text.replace(/\s/g,'');
 // OSM's dated end-of-season summary occupies a calendar cell but is not a match.
 if(lines.some(line=>/^(?:resumo(?:da)?(?:epoca|temporada)|ultimodia|fim(?:da)?(?:epoca|temporada))$/.test(normalize(line.text).replace(/\s/g,''))))return null;
 const headerLines=lines.filter(line=>(line.y-anchorY)/cardHeight>=-.04&&(line.y-anchorY)/cardHeight<.32);
 const date=headerLines.map(compact).find(text=>datePattern.test(text))||'NI';
 const time=headerLines.map(compact).find(text=>timePattern.test(text))||'NI';
 const scoreLine=lines.find(line=>(line.y-anchorY)/cardHeight>=.3&&(line.y-anchorY)/cardHeight<.65&&scorePattern.test(line.text));
 const displayedScore=scoreLine?compact(scoreLine).replace(/[×:\-–—]/,'x'):'NI';
 const badge=words.find(w=>['V','E','D'].includes(w.text.toUpperCase())&&relative(w)>=-.05&&relative(w)<.14&&(centerX(w)-left)/width>.8);
 const result=badge?.text.toUpperCase()||'NI';
 const nameLines=lines.filter(line=>(line.y-anchorY)/cardHeight>=.64&&(line.y-anchorY)/cardHeight<.835).map(line=>meaningfulName(line,cardHeight)).filter(Boolean);
 const nicknameLines=lines.filter(line=>(line.y-anchorY)/cardHeight>=.835&&(line.y-anchorY)/cardHeight<.98).map(line=>meaningfulName(line,cardHeight)).filter(Boolean);
 const visibleName=nameLines.join(' '),stage=stagePattern.test(normalize(visibleName))?visibleName:'NI';
 const placeholder=text=>['asd','a definir','por definir','tbd'].includes(normalize(text));
 const opponent=visibleName&&stage==='NI'&&!placeholder(visibleName)?visibleName:'NI';
 const nickname=nicknameLines.length&&!placeholder(nicknameLines.join(' '))?nicknameLines.join(' '):'NI';
 // OCR alone cannot establish the house and cup icons. Explicit labels/stages can.
 const labels=lines.map(line=>normalize(line.text));
 const home=labels.some(text=>/^(?:local:? )?casa$/.test(text))?true:labels.some(text=>/^(?:local:? )?fora$/.test(text))?false:null;
 const cup=stage!=='NI'||labels.some(text=>/^(?:competicao:? )?(?:copa|taca)$/.test(text))?true:labels.some(text=>/^(?:competicao:? )?liga$/.test(text))?false:null;
 return {round,date,time,opponent,nickname,home,cup,score:'NI',displayedScore,result,stage};
}

/** Parse OSM's six-column calendar or one isolated card without inventing sequence numbers. */
export function parseCalendarWords(input,width,height,layout={}){
 if(!Number.isFinite(width)||!Number.isFinite(height)||width<=0||height<=0)return [];
 const card=layout.kind==='calendar-card'||(width/height<1.35&&layout.kind!=='full');
 const columns=card?1:6;
 if(!card&&(width/height<1.8||width/height>2.6))return [];
 const words=input.filter(w=>w.text?.trim()&&[w.x,w.y,w.w,w.h].every(Number.isFinite));
 const anchors=anchorsIn(words,width,columns);
 if(!anchors.length&&!(card&&Number.isInteger(layout.round)&&layout.round>0))return [];
 const rowYs=[];
 for(const anchor of [...anchors].sort((a,b)=>a.y-b.y)){
  const row=rowYs.find(y=>Math.abs(y-anchor.y)<Math.max(18,width/columns*.08));
  if(row===undefined)rowYs.push(anchor.y);
 }
 if(!rowYs.length)rowYs.push(Number.isFinite(layout.anchorY)?layout.anchorY:height*.06);
 const differences=rowYs.slice(1).map((y,i)=>y-rowYs[i]).filter(gap=>gap>width/columns*.7&&gap<width/columns*1.4);
 const cardHeight=Number.isFinite(layout.cardHeight)&&layout.cardHeight>0?layout.cardHeight:median(differences)||width/columns*1.02;
 const rows=[];
 for(const y of rowYs)for(let column=0;column<columns;column++){
  const left=column*width/columns,right=(column+1)*width/columns;
  const anchor=anchors.find(a=>a.column===column&&Math.abs(a.y-y)<Math.max(18,cardHeight*.08));
  const anchorY=anchor?.y??y;
  const rowWords=words.filter(w=>centerX(w)>=left&&centerX(w)<right&&centerY(w)>=anchorY-cardHeight*.05&&centerY(w)<Math.min(height,anchorY+cardHeight*.98));
  const row=extractCard(rowWords,{round:anchor?.round??(card?layout.round:null)??null,anchorY,cardHeight,left,width:width/columns});
  if(!row)continue;
  // A visible round label by itself is not a recognized game.
  if(['date','time','opponent','nickname','displayedScore','result','stage'].some(key=>row[key]!=='NI'))rows.push({...row,_card:{left,right,anchorY,cardHeight,width,height}});
 }
 // Retain the home/away scoreboard as evidence; score stays NI until home is known.
 return rows.flatMap(evidence=>cleanCalendar([evidence]).map(row=>({...row,displayedScore:evidence.displayedScore,_card:evidence._card})));
}

/** Preserve visible calendar boundaries independently from games. The dated
 * season-summary cell is deliberately excluded by parseCalendarWords, but it
 * proves the final day only when both labels appear in that same native cell. */
export function parseCalendarBoundaryWords(input,width,height,layout={}){
 const out={};if(!Number.isFinite(width)||!Number.isFinite(height)||width<=0||height<=0)return out;
 const card=layout.kind==='calendar-card',columns=card?1:6;
 if(!card&&(width/height<1.8||width/height>2.6))return out;
 const words=input.filter(w=>w.text?.trim()&&[w.x,w.y,w.w,w.h].every(Number.isFinite)&&w.w>0&&w.h>0&&w.x>=0&&w.y>=0&&w.x+w.w<=width+1&&w.y+w.h<=height+1);
 const anchors=anchorsIn(words,width,columns),columnWidth=width/columns,rowYs=[];
 for(const anchor of [...anchors].sort((a,b)=>a.y-b.y))if(!rowYs.some(y=>Math.abs(y-anchor.y)<Math.max(18,columnWidth*.08)))rowYs.push(anchor.y);
 const gaps=rowYs.slice(1).map((y,index)=>y-rowYs[index]).filter(gap=>gap>columnWidth*.7&&gap<columnWidth*1.4);
 const cardHeight=Number.isFinite(layout.cardHeight)&&layout.cardHeight>0?layout.cardHeight:median(gaps)||columnWidth*1.02;
 const cellLines=(column,anchorY)=>groupedLines(words.filter(w=>centerX(w)>=column*columnWidth&&centerX(w)<(column+1)*columnWidth&&centerY(w)>=anchorY-cardHeight*.06&&centerY(w)<Math.min(height,anchorY+cardHeight*1.02)));
 const makeProof=(kind,column,anchorY,marker,dateLine,extra={})=>({kind,marker:marker.text,date:calendarDate(dateLine.text.replace(/\s/g,'')),width,height,box:{left:column*columnWidth,right:(column+1)*columnWidth,top:Math.max(0,anchorY-cardHeight*.065),bottom:Math.min(height,anchorY+cardHeight*1.02)},textBoxes:{marker:lineBox(marker),date:lineBox(dateLine)},...extra});
 const start=anchors.find(anchor=>anchor.round===1&&anchor.column===0);
 if(start){
  const lines=cellLines(start.column,start.y),label=lines.filter(line=>Math.abs(line.y-start.y)<cardHeight*.08).map(line=>{
   const header=line.words.filter(word=>word.x>=start.word.x-1&&centerX(word)<columnWidth*.8);
   return {...line,words:header,text:header.map(word=>word.text).join(' ')};
  }).find(line=>/^jornada\s*1$/.test(normalize(line.text))&&confidentLine(line));
  const dateLine=lines.find(line=>line.y>start.y+cardHeight*.05&&line.y<start.y+cardHeight*.38&&calendarDate(line.text.replace(/\s/g,''))&&confidentLine(line));
  if(label&&dateLine){out.sawTop=true;out.sawTopProof=makeProof('calendar-start-round',0,start.y,label,dateLine,{round:1});}
 }
 const terminal=[];
 for(let column=0;column<columns;column++){
  const columnWords=words.filter(w=>centerX(w)>=column*columnWidth&&centerX(w)<(column+1)*columnWidth);
  for(const marker of groupedLines(columnWords).filter(line=>terminalDay(line.text)&&confidentLine(line))){
   const anchorY=marker.y,lines=cellLines(column,anchorY);
   const summary=lines.find(line=>seasonSummary(line.text)&&line.y>anchorY+cardHeight*.45&&line.y<anchorY+cardHeight*1.02&&confidentLine(line));
   const dateLine=lines.find(line=>line.y>anchorY+cardHeight*.05&&line.y<anchorY+cardHeight*.38&&calendarDate(line.text.replace(/\s/g,''))&&confidentLine(line));
   if(!summary||!dateLine)continue;
   // A final match/round never proves the boundary. Likewise, another actual
   // match cell after this alleged terminal day contradicts a list-end claim.
   if(anchors.some(anchor=>anchor.y>anchorY+cardHeight*.12||Math.abs(anchor.y-anchorY)<cardHeight*.08&&anchor.column>=column))continue;
   terminal.push(makeProof('calendar-terminal-day',column,anchorY,marker,dateLine,{marker:marker.text+' · '+summary.text,noMatch:true,textBoxes:{marker:lineBox(marker),date:lineBox(dateLine),summary:lineBox(summary)}}));
  }
 }
 if(terminal.length===1){out.sawBottom=true;out.sawBottomProof=terminal[0];}
 return out;
}
