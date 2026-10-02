import {NI,normalize} from './utils.js';
import {parseSquadMeta} from './ocr-layout.js';
const lines=text=>String(text).split(/\r?\n/).map(s=>s.trim()).filter(Boolean);
export function parseReferee(text=''){
 for(const line of lines(text)){const m=normalize(line).match(/(?:arbitro|referee|juiz)\s*[:\-]?\s*(verde|azul|amarelo|laranja|vermelho)\b/);if(m)return m[1][0].toUpperCase()+m[1].slice(1);}return NI;
}
export function parseFormation(text=''){
 for(const line of lines(text)){const m=line.match(/(?:forma[cç][aã]o(?:\s+(?:do\s+)?rival)?|rival|t[aá]tica)\s*[:\-]?\s*([345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?)(?:\s*([AB]))?\b/i);if(m)return m[1].replace(/\s/g,'')+(m[2]?' '+m[2].toUpperCase():'');}return NI;
}
// The squad header also contains a formation. Only a label naming the rival,
// or the actual scout report wording, can associate it with rivalFormation.
function parseExplicitRivalFormation(text=''){
 for(const line of lines(text)){const m=line.match(/^(?:forma[cç][aã]o\s+(?:(?:do\s+)?rival|(?:do\s+)?advers[aá]rio)|rival|t[aá]tica\s+(?:do\s+)?rival)\s*[:\-]?\s*([345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?)(?:\s*([AB]))?\b/i);if(m)return m[1].replace(/\s/g,'')+(m[2]?' '+m[2].toUpperCase():'');}return NI;
}
export function parseRivalReportText(text='',context={}){
 const n=normalize(text).replace(/\s+/g,' ');
 const isReport=/\bpelo que pude ver\b/.test(n)&&/\b(?:deu ordens aos jogadores|consegui descobrir a formacao)\b/.test(n);
 if(!isReport)return {};
 const out={};
 const club=String(text).replace(/\s+/g,' ').match(/pelo que pude ver,\s*(.+?)\s+deu ordens aos jogadores/i);if(club){if(context.myTeam&&normalize(context.myTeam)===normalize(club[1]))return {};out.rivalName=club[1].trim();}
 if(club){
  const preceding=lines(text).slice(0,lines(text).findIndex(line=>/pelo que pude ver/i.test(line)));
  const nameIndex=preceding.findIndex(line=>normalize(line)===normalize(club[1]));
  const nickname=nameIndex>=0?preceding[nameIndex+1]:null;
  if(nickname&&nickname.length<=60&&normalize(nickname)!==normalize(context.username||'leandrozzy')&&!/formacao|marcacao|jogar|estadio|relatorio/i.test(normalize(nickname)))out.rivalNickname=nickname;
 }
 const formation=n.match(/\bparece que vao jogar num\s+([345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?)(?:\s*([ab]))?\b/);
 if(formation)out.rivalFormation=formation[1].replace(/\s/g,'')+(formation[2]?' '+formation[2].toUpperCase():'');
 const tackling=n.match(/\bentradas\s+(cauteloso|normal|agressivo|imprudente)\b/);if(tackling)out.rivalTackling=tackling[1][0].toUpperCase()+tackling[1].slice(1);
 const stadium=n.match(/\bnivel do estadio\s*:?\s*(\d{1,2})\b/);if(stadium)out.stadium=+stadium[1];
 if(/\beles nao foram em estagio\b/.test(n))out.trainingCamp='Não';else if(/\beles foram em estagio\b/.test(n))out.trainingCamp='Sim';
 const plan=parsePlan(text);if(plan!==NI)out.rivalPlan=plan;
 const marking=n.match(/\bmarcacao\s*:?\s*(a zona|zona|individual)\b/);if(marking)out.rivalMarking=marking[1]==='individual'?'Individual':'À zona';
 const offside=n.match(/\b(?:fazer\s+)?(?:fora[- ]de[- ]jogo|impedimento)\s*:?\s*(sim|nao)\b/);if(offside)out.rivalOffside=offside[1]==='sim'?'Sim':'Não';
 return out;
}
export function parsePlan(text=''){
 const n=normalize(text);for(const [key,value] of [['jogo de passes','Jogo de passes'],['jogar pelas alas','Jogar pelas alas'],['contra-ataque','Contra-ataque'],['contra ataque','Contra-ataque'],['remate a vista','Remate à vista'],['bola longa','Bola longa']])if(n.includes(key))return value;return NI;
}
export function parseMarking(text=''){
 const n=normalize(text).replace(/\s+/g,' ');const m=n.match(/marcacao(?:\s+(?:(?:do\s+)?rival|adversario))?\s*[:\-]?\s*(a zona|zona|individual)\b/);return m?(m[1]==='individual'?'Individual':'À zona'):NI;
}
export function parseOffside(text=''){
 const n=normalize(text).replace(/\s+/g,' ');const m=n.match(/(?:impedimento|fora[- ]de[- ]jogo)(?:\s+(?:(?:do\s+)?rival|adversario))?\s*[:\-]?\s*(sim|nao)\b/);return m?(m[1]==='sim'?'Sim':'Não'):NI;
}
export function parseYesNoNear(text,label){
 for(const line of lines(text)){const n=normalize(line),l=normalize(label);if(!n.startsWith(l))continue;const m=n.slice(l.length).match(/^\s*[:\-]?\s*(sim|nao)\b/);if(m)return m[1]==='sim'?'Sim':'Não';}return NI;
}
export function parseStrengthPair(text=''){
 const my=pick(text,['minha força','força do meu time','meu time força']),rival=pick(text,['força rival','força do rival']);return {my:integer(my),rival:integer(rival)};
}
function pick(text,labels){
 for(const line of lines(text)){for(const label of labels){const n=normalize(line),l=normalize(label);if(n.startsWith(l)&&/^[\s:\-]/.test(n.slice(l.length))){const rest=line.slice(label.length).replace(/^\s*[:\-]?\s*/,'').trim();if(rest)return rest;}}}return NI;
}
function integer(v){if(v===NI)return null;const m=String(v).match(/^\d{1,3}$/);return m?+m[0]:null;}
export function parseMatchText(text='',context={}){
 const strengths=parseStrengthPair(text);
 const out={_schemaVersion:3,myName:pick(text,['meu time','minha equipe','minha equipa']),rivalName:pick(text,['time rival','adversário','rival']),rivalNickname:pick(text,['nickname rival','treinador rival','nickname']),myStrength:strengths.my??NI,rivalStrength:strengths.rival??NI,referee:parseReferee(text),rivalFormation:parseExplicitRivalFormation(text),rivalPlan:parsePlan(pick(text,['plano rival'])),rivalMarking:parseMarking(lines(text).filter(l=>/marcacao\s+(?:(?:do\s+)?rival|adversario)/.test(normalize(l))).join('\n')),rivalOffside:parseOffside(lines(text).filter(l=>/(?:impedimento|fora de jogo)\s+(?:(?:do\s+)?rival|adversario)/.test(normalize(l))).join('\n')),secretTraining:parseYesNoNear(text,'treino secreto'),trainingCamp:parseYesNoNear(text,'campo de treinamento'),myTrainingCamp:parseYesNoNear(text,'meu campo de treinamento')};
 for(const [key,labels] of [['mySquadValue',['valor do meu elenco','valor da minha equipa','valor do meu plantel']],['rivalSquadValue',['valor do elenco rival','valor do plantel rival','valor da equipa rival']],['myPlayers',['meus jogadores','jogadores do meu elenco']],['rivalPlayers',['jogadores rival','jogadores do rival']],['stadium',['estádio','nível do estádio']],['myBonus',['meu bônus','bônus do meu time']],['rivalBonus',['bônus rival','bônus do rival']]])out[key]=pick(text,labels);
 for(const [key,label] of [['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA']]){out['my'+key]=integer(pick(text,['meu '+label]))??NI;out['rival'+key]=integer(pick(text,['rival '+label]))??NI;}
 const loc=normalize(pick(text,['local','casa/fora']));out.location=loc==='casa'?'Casa':loc==='fora'?'Fora':NI;
 out.rivalTackling=pick(text,['desarme rival','entrada rival','entradas rival']);
 const human=normalize(pick(text,['humano/cpu','tipo do rival','rival humano']));if(human==='humano'||human==='sim')out.human=true;else if(human==='cpu'||human==='nao')out.human=false;
 for(const key of ['myPlayers','rivalPlayers']){const count=integer(out[key]);out[key]=count!==null&&count>=1&&count<=100?out[key]:NI;}
 if(normalize(out.rivalNickname)===normalize(context.username||'leandrozzy'))out.rivalNickname=NI;
 return {...out,...parseRivalReportText(text,context)};
}
export function mergeMatchTexts(texts=[],context={}){const out={_schemaVersion:3,_sources:{}};for(let i=0;i<texts.length;i++){const obj=parseMatchText(texts[i],context);for(const [k,v] of Object.entries(obj)){if(k.startsWith('_')||v==null||v===NI)continue;out[k]=v;out._sources[k]='OCR tela '+(i+1);}}return out;}

export function matchOcrRows(ocr={}){
 const words=(ocr.lines||[]).flatMap(line=>(line.Words||[]).map(word=>({...word,Left:Number(word.Left),Top:Number(word.Top??line.MinTop),Width:Number(word.Width),Height:Number(word.Height??line.MaxHeight)}))).filter(word=>String(word.WordText||'').trim()&&[word.Left,word.Top,word.Width,word.Height].every(Number.isFinite)&&word.Width>0&&word.Height>0);
 const bands=[];
 for(const word of words.sort((a,b)=>a.Top+a.Height/2-b.Top-b.Height/2)){
  const center=word.Top+word.Height/2;
  let band=bands.find(line=>Math.abs(line.center-center)<Math.max(line.height,word.Height)*.5);
  if(!band){band={center,height:word.Height,words:[]};bands.push(band);}
  band.words.push(word);band.height=Math.max(band.height,word.Height);
 }
 const rows=[];
 for(const band of bands){
  let segment=[];
  const append=()=>{if(!segment.length)return;const left=Math.min(...segment.map(word=>word.Left)),top=Math.min(...segment.map(word=>word.Top)),right=Math.max(...segment.map(word=>word.Left+word.Width)),bottom=Math.max(...segment.map(word=>word.Top+word.Height));rows.push({text:segment.map(word=>word.WordText).join(' '),left,top,right,bottom,words:segment});segment=[];};
  for(const word of band.words.sort((a,b)=>a.Left-b.Left)){
   const prior=segment.at(-1);if(prior&&word.Left-prior.Left-prior.Width>Math.max(48,band.height*3.5))append();
   segment.push(word);
  }
  append();
 }
 return rows;
}
export function parseMatchOverlay(ocr={},width,height,context={}){
 const out=mergeMatchTexts([ocr.text||''],context),headerFields=new Set();
 out._headerFields=[];
 const setHeader=(field,value)=>{if(value==null||value===NI||value==='')return;out[field]=value;headerFields.add(field);out._headerFields=[...headerFields];};
 if(!width||!height||width/height<1.8||width/height>2.6)return out;
 const rows=matchOcrRows(ocr),words=rows.flatMap(row=>row.words.map(word=>({text:String(word.WordText),x:Number(word.Left),y:Number(word.Top??row.top),w:Number(word.Width),h:Number(word.Height??row.bottom-row.top)})));
 const identity=value=>normalize(value).replace(/[^\p{L}\p{N}]/gu,''),ownTeam=context.myTeam&&context.myTeam!==NI?identity(context.myTeam):'',rivalTeam=context.rivalName&&context.rivalName!==NI?identity(context.rivalName):'',username=identity(context.username||'leandrozzy');
 const isOwnNickname=value=>identity(value)===username;
 const vs=words.find(word=>normalize(word.text)==='vs'&&word.x>width*.44&&word.x<width*.56&&word.y>height*.20&&word.y<height*.39);
 if(vs){
  const sameSide=(row,left)=>left?row.left>width*.14&&row.right<width*.44:row.left>width*.66&&row.right<width*.94;
  const nicknameRows=rows.filter(row=>row.top>height*.34&&row.bottom<height*.47&&row.text.length<=60);
  const ownUser=nicknameRows.find(row=>isOwnNickname(row.text));
  const club=left=>{
   const nickname=nicknameRows.find(row=>sameSide(row,left)&&row.top>height*.375);
   const candidates=rows.filter(row=>sameSide(row,left)&&row.top>height*.30&&row.bottom<height*.405&&/[\p{L}]/u.test(row.text)&&!isOwnNickname(row.text)&&(!nickname||row.bottom<nickname.top));
   const anchor=candidates.sort((a,b)=>b.bottom-a.bottom)[0];if(!anchor)return null;
   const line=candidates.filter(row=>Math.abs(row.top-anchor.top)<Math.max(anchor.bottom-anchor.top,row.bottom-row.top)*.55).sort((a,b)=>a.left-b.left);
   return {...anchor,text:line.map(row=>row.text).join(' '),left:Math.min(...line.map(row=>row.left)),right:Math.max(...line.map(row=>row.right))};
  };
  const clubs=[club(true),club(false)];
  // A known club owns its side even when the nickname is missing or contradictory.
  // Username can establish ownership only before this slot has an identified club.
  let ownLeft=null;
  if(ownTeam){const index=clubs.findIndex(row=>row&&identity(row.text)===ownTeam);if(index>=0)ownLeft=index===0;}
  else if(ownUser)ownLeft=ownUser.left<width*.5;
  const sidePrefix=left=>ownLeft!==null?(left===ownLeft?'my':'rival'):rivalTeam&&identity(clubs[left?0:1]?.text)===rivalTeam?'rival':null;
  for(const left of [true,false]){
   const prefix=sidePrefix(left),team=clubs[left?0:1];if(!prefix)continue;
   if(team)setHeader(prefix+'Name',team.text);
   if(prefix==='rival'){
    const nickname=nicknameRows.filter(row=>sameSide(row,left)&&(!team||row.top>team.bottom)&&!isOwnNickname(row.text)).sort((a,b)=>a.top-b.top)[0];
    if(nickname)setHeader('rivalNickname',nickname.text);
   }
  }
  if(ownLeft!==null)setHeader('location',ownLeft?'Casa':'Fora');
  for(const word of words){
   if(word.y<height*.20||word.y>height*.31)continue;
   const left=word.x>width*.28&&word.x<width*.38,right=word.x>width*.62&&word.x<width*.72;if(!left&&!right)continue;
   const prefix=sidePrefix(left);if(!prefix)continue;
   if(/^\+?\d{1,2}%$/.test(word.text))setHeader(prefix+'Bonus',+word.text.replace(/[^\d]/g,''));
   else if(/^\d{1,3}$/.test(word.text)&&+word.text>0&&+word.text<=400){
    const isSplitBonus=words.some(other=>/^[+%]$/.test(other.text)&&Math.abs(other.y-word.y)<Math.max(other.h,word.h)&&Math.abs(other.x-word.x)<width*.025);
    if(!isSplitBonus)setHeader(prefix+'Strength',+word.text);
   }
  }
  return out;
 }
 const validTeam=row=>row.left>width*.025&&row.left<width*.4&&row.top>height*.18&&row.bottom<height*.27&&/[\p{L}]/u.test(row.text)&&!isOwnNickname(row.text)&&!/(?:nota mais|jogador|marcador|idade|posi[cg]ao|objetivo|analista)/.test(normalize(row.text));
 const ownUser=rows.find(row=>isOwnNickname(row.text)&&row.left<width*.4&&row.top>height*.16&&row.bottom<height*.32);
 const header=parseSquadMeta(ocr.lines||[],width,height);
 const lockedLabel=rows.find(row=>/analista de dados/.test(normalize(row.text))&&row.left>width*.65&&row.top>height*.20&&row.bottom<height*.37);
 const candidates=rows.filter(validTeam).sort((a,b)=>a.top-b.top);
 let teamRow=null;
 if(header.teamVerified){
  const matching=candidates.filter(row=>identity(header.team).includes(identity(row.text)));
  const anchor=words.find(word=>word.x>width*.033&&word.x<width*.053&&word.y>height*.20&&word.y<height*.245&&/[\p{L}]/u.test(word.text)&&header.team.includes(word.text));
  teamRow=matching[0]?{...matching[0],text:header.team}:anchor?{text:header.team,left:anchor.x,top:anchor.y,right:anchor.x+anchor.w,bottom:anchor.y+anchor.h}:null;
 }
 if(!teamRow){
  // OCR can put each word of a club on a separate line. Merge only the same physical title.
  const anchor=candidates.find(row=>row.left<width*.07);
  if(anchor){const line=candidates.filter(row=>Math.abs(row.top-anchor.top)<Math.max(anchor.bottom-anchor.top,row.bottom-row.top)*.55).sort((a,b)=>a.left-b.left);teamRow={...anchor,text:line.map(row=>row.text).join(' '),right:Math.max(...line.map(row=>row.right))};}
 }
 if(!teamRow)return out;
 const clubIdentity=identity(teamRow.text),reportRival=out.rivalName&&identity(out.rivalName)===clubIdentity;
 let prefix=null;
 if(ownTeam&&clubIdentity===ownTeam)prefix='my';
 else if(rivalTeam&&clubIdentity===rivalTeam||reportRival||lockedLabel)prefix='rival';
 else if(ownTeam&&header.teamVerified&&clubIdentity!==ownTeam)prefix='rival';
 else if(!ownTeam&&ownUser&&teamRow.bottom<ownUser.top&&ownUser.top-teamRow.bottom<height*.08)prefix='my';
 if(!prefix)return out;
 setHeader(prefix+'Name',teamRow.text);
 if(prefix==='rival'){
  const nickname=rows.filter(row=>row.left<width*.4&&row.top>teamRow.bottom&&row.top-teamRow.bottom<height*.065&&row.text.length<60&&!isOwnNickname(row.text)&&!/(?:nota mais|jogador|marcador|posi[cg]ao)/.test(normalize(row.text))).sort((a,b)=>a.top-b.top)[0];
  if(nickname)setHeader('rivalNickname',nickname.text);
 }
 // The money at the very top is cash. Only the squad-value header is accepted.
 const value=words.find(word=>word.x>width*.91&&word.y>height*.105&&word.y<height*.20&&/^\d+(?:[.,]\d+)?\s*[MKB]$/i.test(word.text));if(value)setHeader(prefix+'SquadValue',value.text);
 const sectorMap={gol:'GK',gr:'GK',gk:'GK',def:'DEF',med:'MID',mei:'MID',ata:'ATT'};
 for(const label of words){
  const sector=sectorMap[normalize(label.text).replace(/[^a-z]/g,'')];if(!sector||label.x<width*.58||label.x>width*.8||label.y<height*.12||label.y>height*.32)continue;
  const number=words.filter(word=>/^\d{1,3}$/.test(word.text)&&Math.abs(word.x+word.w/2-label.x-label.w/2)<width*.018&&word.y>label.y&&word.y-label.y<height*.06).sort((a,b)=>a.y-b.y)[0];
  if(number&&+number.text>0&&+number.text<=400)setHeader(prefix+sector,+number.text);
 }
 if(header.teamVerified&&identity(header.team)===clubIdentity){
  for(const [metaKey,field] of [['GK','GK'],['DEF','DEF'],['MID','MID'],['ATT','ATT']])if(header[metaKey]!=null)setHeader(prefix+field,header[metaKey]);
  if(header.strength!=null)setHeader(prefix+'Strength',header.strength);
 }
 const force=words.find(word=>normalize(word.text)==='equipa'&&word.x>width*.79&&word.x<width*.92&&word.y>height*.18&&word.y<height*.35);
 if(force){const number=words.filter(word=>/^\d{1,3}$/.test(word.text)&&Math.abs(word.x+word.w/2-force.x-force.w/2)<width*.026&&word.y>force.y&&word.y-force.y<height*.10).sort((a,b)=>a.y-b.y)[0];if(number&&+number.text>0&&+number.text<=400)setHeader(prefix+'Strength',+number.text);}
 // Position, objective, round and formation numbers never supply the player count.
 for(const label of words){
  if(!/^jogadores[:.]?$/i.test(normalize(label.text))||label.y<height*.09||label.y>height*.50)continue;
  const count=words.find(word=>/^\d{1,3}$/.test(word.text)&&+word.text>=1&&+word.text<=100&&word.x>=label.x+label.w&&word.x-label.x-label.w<width*.06&&Math.abs(word.y+word.h/2-label.y-label.h/2)<height*.018);
  if(count)setHeader(prefix+'Players',+count.text);
 }
 if(prefix==='rival'){
  const formation=rows.find(row=>row.left>width*.67&&row.left<width*.78&&row.top>height*.105&&row.bottom<height*.20&&/^[345]\s*-\s*[1-5]\s*-\s*[1-5](?:\s*-\s*[1-5])?(?:\s*[AB])?$/i.test(row.text));
  if(formation)setHeader('rivalFormation',formation.text.replace(/\s*([AB])$/i,' $1').replace(/\s*-\s*/g,'-'));
 }
 return out;
}
