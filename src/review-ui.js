import {matchFields,autoMatchFields,optionalMatchFields,manualMatchFields,known,positions} from './domain.js';
import {coverage,relevantConflicts,cleanCalendarBoundaryProof} from './extraction.js';
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function shirt(color='#3279e6',number=''){return '<svg class="shirt" viewBox="0 0 80 90" aria-hidden="true"><path fill="'+color+'" stroke="#ffffff" stroke-width="3" d="M24 9 8 18 2 38 18 45 22 33 22 80 58 80 58 33 62 45 78 38 72 18 56 9Q40 22 24 9Z"/><path fill="#fff" opacity=".25" d="M36 18h8v62h-8z"/><text x="40" y="55" text-anchor="middle" fill="white" font-size="25" font-weight="bold">'+esc(number)+'</text></svg>';}
function control(path,label,value,type='text',options,evidence,manual=false){
 const unknown=!known(value),attrs=' data-review-path="'+esc(path)+'" data-kind="'+type+'"';
 const current=value===true?'true':value===false?'false':known(value)?String(value):'';
 const html=options?'<select'+attrs+'><option value="">NI</option>'+options.map(item=>{const [v,l]=Array.isArray(item)?item:[item,item];return '<option value="'+esc(v)+'" '+(String(v)===current?'selected':'')+'>'+esc(l)+'</option>';}).join('')+'</select>':'<input'+attrs+' type="'+(type==='number'?'number':'text')+'" value="'+esc(current)+'" placeholder="NI" '+(type==='number'?(path.endsWith('.shirtNumber')?'min="1" max="99"':'min="0"'):'')+'>';
 const status=manual?' · Ajuste manual':unknown?' · NI':evidence?.kind==='manual'?' · Revisado por você':(evidence?.rank||0)>=2?' · Texto/ícone nas telas':' · Conferir na tela';
 return '<label class="'+(unknown&&!manual?'review-ni':'')+'"><span>'+esc(label)+status+'</span>'+html+'</label>';
}
const yn=[['true','Sim'],['false','Não']];
function calendarReviewRow(r,path){
 const field=(key,label,type='text',options)=>control(path+'.'+key,label,r[key],type,options,r._fieldSources?.[key]);
 return '<article class="calendar-review"><header><span class="round-badge">'+esc(r.round??'?')+'</span><div><b>'+esc(r.opponent)+'</b><span>'+esc(r.stage==='NI'?'':r.stage)+' · '+(r.home===true?'Casa':r.home===false?'Fora':'Local NI')+'</span></div><strong class="result-badge result-'+esc(['V','E','D'].includes(r.result)?r.result:'NI')+'">'+esc(r.result)+'</strong></header><div class="form-grid">'+[['round','Rodada','number'],['opponent','Rival'],['nickname','Nickname'],['date','Data DD/MM/AAAA'],['time','Horário HH:MM'],['score','Placar (meu time primeiro)'],['displayedScore','Placar exibido (casa primeiro)'],['stage','Fase']].map(([key,label,type])=>field(key,label,type)).join('')+field('home','Local','boolean',[['true','Casa'],['false','Fora']])+field('cup','Competição','boolean',[['true','Copa'],['false','Liga']])+field('result','Resultado','text',['V','E','D'])+'</div></article>';
}
function conflictItem(c,index,review){
 const key=c.field.split('.').at(-1),labels={home:'Local',cup:'Competição',opponent:'Rival',nickname:'Nickname',result:'Resultado',round:'Rodada',date:'Data',time:'Horário',score:'Placar',displayedScore:'Placar exibido',name:'Jogador',position:'Posição',strength:'Força',age:'Idade',value:'Valor',training:'Treino',forSale:'Venda',...Object.fromEntries(matchFields.map(([field,label])=>[field,label]))};
 const round=c.field.match(/round:(\d+)/)?.[1],date=c.field.match(/date:(\d{2}\/\d{2}\/\d{4})/)?.[1];
 const context=round?'Rodada '+round:date||'';
 const show=value=>key==='home'&&typeof value==='boolean'?(value?'Casa':'Fora'):key==='cup'&&typeof value==='boolean'?(value?'Copa':'Liga'):typeof value==='boolean'?(value?'Sim':'Não'):value;
 const choices=review?.type==='match'&&!c.resolved?'<div class="actions"><button type="button" class="secondary" data-action="resolveConflict:'+index+':first">Usar '+esc(show(c.first))+'</button><button type="button" class="secondary" data-action="resolveConflict:'+index+':second">Usar '+esc(show(c.second))+'</button><button type="button" class="secondary" data-action="resolveConflict:'+index+':unknown">Marcar NI</button></div>':'';
 return '<li><b>'+esc((context?context+' · ':'')+(labels[key]||'Campo'))+'</b>: '+esc(show(c.first))+' / '+esc(show(c.second))+(c.resolved?' · Confirmado: '+esc(show(c.preferred)):'')+choices+'</li>';
}
function playerReviewRow(p,path,pending=false){
 const include=pending?'<label><input type="checkbox" data-review-path="'+path+'._include" data-kind="include"> Confirmo este jogador nas telas e quero incluir</label>':'';
 const field=(key,label,type='text',options)=>control(path+'.'+key,label,p[key],type,options,p._fieldSources?.[key]);
 return '<article class="player-review"><header>'+shirt(p.training===true?'#f6a42e':'#3479df',p._fieldSources?.shirtNumber?.rank===1?'?':p.shirtNumber??'?')+'<div><b>'+esc(p.name)+'</b><span>Força: '+esc(p.strength??'NI')+' · '+esc(p.position)+' · '+(p.training===true?'Treinando':p.training===false?'Disponível':'Treino NI')+(p.forSale===true?' · À venda':'')+'</span></div></header>'+include+'<div class="form-grid">'+field('name','Nome')+field('position','Posição','text',positions)+field('shirtNumber','Número da camisa','number')+field('strength','Força','number')+field('age','Idade','number')+field('value','Valor')+field('training','Treinando','boolean',yn)+field('forSale','À venda','boolean',yn)+'</div></article>';
}
function boundaryPreview(review,proof){
 const previews=(review.previews||[]).filter(preview=>typeof preview.url==='string'&&/^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/=\s]+$/.test(preview.url));
 if(proof.frameId){const matches=previews.filter(preview=>preview.frameId===proof.frameId);if(matches.length===1)return matches[0];}
 if(Number.isInteger(proof.fileIndex)&&Number.isFinite(proof.time)){
  const matches=previews.filter(preview=>preview.fileIndex===proof.fileIndex&&Number.isFinite(preview.time)&&Math.abs(preview.time-proof.time)<.04);if(matches.length===1)return matches[0];
  // Same-named uploads can share a timestamp. An explicit source file must not
  // fall back to an image belonging to a different upload.
  return null;
 }
 if(proof.name&&Number.isFinite(proof.time)){
  const matches=previews.filter(preview=>preview.name===proof.name&&Number.isFinite(preview.time)&&Math.abs(preview.time-proof.time)<.04);if(matches.length===1)return matches[0];
 }
 return null;
}
function calendarBoundaryFigure(review,field){
 const title=field==='sawTop'?'Início do calendário':'Último dia do calendário',proof=cleanCalendarBoundaryProof(review.meta?.[field+'Proof'],field);
 if(!proof)return '<div class="calendar-boundary-proof"><strong>'+title+'</strong><p class="muted">A imagem que comprova '+(field==='sawTop'?'o início':'o último dia sem partida')+' ainda não foi identificada nesta leitura.</p></div>';
 const preview=boundaryPreview(review,proof),caption=esc(proof.marker)+' · '+esc(proof.date);
 if(!preview)return '<div class="calendar-boundary-proof"><strong>'+title+'</strong><p>'+caption+'</p><p class="muted">A imagem correspondente à prova não está disponível nesta leitura.</p></div>';
 const box=proof.box,width=box.right-box.left,height=box.bottom-box.top,percent=value=>value.toFixed(3)+'%';
 const cropStyle='position:relative;overflow:hidden;aspect-ratio:'+width.toFixed(3)+' / '+height.toFixed(3)+';width:100%';
 const imageStyle='position:absolute;max-width:none;width:'+percent(proof.width/width*100)+';height:'+percent(proof.height/height*100)+';left:'+percent(-box.left/width*100)+';top:'+percent(-box.top/height*100);
 const origin=esc(preview.name||proof.name||'Tela original')+(Number.isFinite(preview.time)?' · '+preview.time.toFixed(1)+'s':'');
 return '<figure class="calendar-boundary-proof"><strong>'+title+'</strong><div style="'+cropStyle+'"><img src="'+esc(preview.url)+'" alt="Card original que comprova '+title.toLowerCase()+'" loading="lazy" style="'+imageStyle+'"></div><figcaption>'+caption+'<br><span class="muted">'+origin+'</span></figcaption><details><summary>Ver tela inteira</summary><img src="'+esc(preview.url)+'" alt="Tela original completa da prova do calendário" loading="lazy" style="max-width:100%;height:auto"></details></figure>';
}
function squadBoundaryImages(review){
 const previews=(review.previews||[]).filter(preview=>typeof preview.url==='string'&&/^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/=\s]+$/.test(preview.url)).sort((a,b)=>(a.fileIndex||0)-(b.fileIndex||0)||a.time-b.time);
 const figure=(field,fallback,label)=>{
  const reference=review.meta?.[field+'Frame'],matched=boundaryPreview(review,reference||{}),preview=matched||fallback;
  if(!preview)return '<div class="calendar-boundary-proof"><strong>'+label+'</strong><p class="muted">A imagem desta leitura não está disponível. Analise novamente a mídia original para conferir o limite da lista.</p></div>';
  const confirmed=review.meta?.[field]===true&&!!matched,origin=esc(preview.name||'Tela original')+(Number.isFinite(preview.time)?' · '+preview.time.toFixed(1)+'s':'');
  const caption=confirmed?'Limite da lista identificado ou revisado.':reference&&!matched?'A imagem do limite identificado não está disponível. Confira esta tela; ela, sozinha, não confirma que a lista está completa.':'Confira o limite da lista; esta tela, sozinha, não confirma que a lista está completa.';
  return '<figure class="calendar-boundary-proof"><strong>'+label+'</strong><img src="'+esc(preview.url)+'" alt="'+label+' do elenco" loading="lazy" style="display:block;width:100%;height:auto"><figcaption>'+origin+'<br><span class="muted">'+caption+'</span></figcaption></figure>';
 };
 return '<div class="form-grid calendar-boundary-images">'+figure('sawTop',previews[0],'Primeira tela do elenco')+figure('sawBottom',previews.at(-1),'Última tela do elenco')+'</div>';
}
function listReviewControls(review){
 const meta=review.meta||{},calendar=review.type==='calendar';
 const images=calendar?'<div class="form-grid calendar-boundary-images">'+calendarBoundaryFigure(review,'sawTop')+calendarBoundaryFigure(review,'sawBottom')+'</div>':squadBoundaryImages(review);
 const description=calendar?'Confirme somente se as telas mostram o início e o último dia com resumo da época e sem partida. A última rodada lida, sozinha, não confirma esse dia.':'Confirme somente se o vídeo ou as imagens mostraram o início e o fim da lista. A quantidade capturada não confirma isso.';
 return '<p class="muted">'+description+'</p>'+images+'<div class="form-grid">'+control('meta.sawTop',calendar?'Início do calendário':'Início da lista',meta.sawTop===true?true:null,'boolean',[['true',calendar?'Confirmei o início nas telas':'Vi o início da lista']],meta._fieldSources?.sawTop)+control('meta.sawBottom',calendar?'Último dia sem partida':'Fim da lista',meta.sawBottom===true?true:null,'boolean',[['true',calendar?'Confirmei o último dia sem partida':'Vi o fim da lista']],meta._fieldSources?.sawBottom)+'</div>';
}
export function renderReadingSummary(review){
 const quality=coverage(review.type,review),noun=review.type==='match'?'campos':review.type==='squad'?'jogadores':'jogos';
 const count=quality.count+' '+noun+(review.type==='calendar'?' com dados · '+quality.detectedCount+' rodadas detectadas':' reconhecidos');
 const pending=[quality.missing.length?quality.missing.length+' pendências de dados':null,quality.conflicts?quality.conflicts+' divergências para conferir':null].filter(Boolean).join(' · ');
 const available=quality.hidden.length?' · '+quality.hidden.length+' campos ocultos pelo treino secreto':'';
 return '<div class="reading-summary"><strong>'+esc(count)+'</strong><span>'+quality.percent+'% de cobertura da leitura · '+Math.round((review.elapsedMs||0)/1000)+'s</span></div><p class="muted">'+quality.filledFields+'/'+quality.requiredFields+' campos preenchidos'+available+'. '+(pending||'Dados disponíveis preenchidos; confira antes de salvar.')+'</p>';
}
export function currentReviewWarnings(review){
 const quality=coverage(review.type,review),warnings=(review.errors||[]).filter(warning=>!/^Leitura parcial:|^\d+ divergências entre leituras:/.test(warning));
 if(quality.missing.length)warnings.push('Leitura parcial: '+quality.missing.slice(0,12).join('; '));
 if(quality.conflicts)warnings.push(quality.conflicts+' divergências entre leituras: confira os valores na mídia.');
 return [...new Set(warnings)];
}
export function renderReview(review){
 let content='';
 if(review.type==='match'){
  const field=([key,label,type],manual=false)=>control('match.'+key,label,review.match[key],type==='number'?'number':type==='human'?'boolean':'text',type==='human'?[['true','Humano'],['false','CPU']]:Array.isArray(type)?type:undefined,review._matchFieldSources?.[key],manual);
  content='<div class="match-banner"><div>'+shirt('#357bea','')+'<b>'+esc(review.match.myName||'Meu time · NI')+'</b><strong>'+esc(review.match.myStrength??'NI')+'</strong></div><span>VS</span><div>'+shirt('#ec6354','')+'<b>'+esc(review.match.rivalName||'Rival · NI')+'</b><strong>'+esc(review.match.rivalStrength??'NI')+'</strong></div></div><div class="form-grid">'+autoMatchFields.map(item=>field(item)).join('')+'</div><p class="muted">Seu campo de treinamento é um ajuste manual e não entra nas pendências da leitura.</p><div class="form-grid">'+manualMatchFields.map(item=>field(item,true)).join('')+'</div><details><summary>Quantidade de jogadores (opcional)</summary><p class="muted">A tática usa as forças do time e de cada posição. Estas contagens não são necessárias.</p><div class="form-grid">'+optionalMatchFields.map(item=>field(item)).join('')+'</div></details>';
  if(review.meta?.pendingMatchFacts?.length){const labels=Object.fromEntries(matchFields.map(([field,label])=>[field,label]));content+='<details class="reading-warnings" open><summary>Leituras sem comprovação ('+review.meta.pendingMatchFacts.length+')</summary><p>Estes valores ficam fora dos dados confirmados. Confira na tela original e preencha o campo correto acima.</p><ul>'+review.meta.pendingMatchFacts.map(fact=>'<li><b>'+esc(labels[fact.field]||'Campo')+'</b>: '+esc(fact.value)+' · '+esc(fact.reason)+'</li>').join('')+'</ul></details>';}
 }else if(review.type==='squad'){
  content='<div class="form-grid">'+[['team','Time'],['cash','Caixa'],['squadValue','Valor do elenco'],['strength','Força geral'],['GK','GOL'],['DEF','DEF'],['MID','MEI'],['ATT','ATA'],['expectedPlayers','Quantidade total visível']].map(([key,label])=>control('meta.'+key,label,review.meta?.[key],['team','cash','squadValue'].includes(key)?'text':'number',undefined,review.meta?._fieldSources?.[key])).join('')+'</div><div class="review-list">'+review.players.map((p,i)=>playerReviewRow(p,'players.'+i)).join('')+'</div>';
  if(review.playerCandidates?.length)content+='<details class="reading-warnings" open><summary>Nomes sem linha comprovada ('+review.playerCandidates.length+')</summary><p>O OCR pode não ter lido nomes válidos. Estes nomes ficam fora da contagem e do elenco até você conferir e marcar para incluir.</p><div class="review-list">'+review.playerCandidates.map((p,i)=>playerReviewRow(p,'playerCandidates.'+i,true)).join('')+'</div></details>';
 }else{
  content='<div class="review-list">'+review.calendar.map((r,i)=>calendarReviewRow(r,'calendar.'+i)).join('')+'</div>';
  if(review.calendarFragments?.length)content+='<details class="reading-warnings"><summary>Trechos sem identificação do jogo ('+review.calendarFragments.length+')</summary><p>Esses trechos não entram na contagem de jogos. Complete a rodada, data ou horário comprovado para incluir ao confirmar.</p><div class="review-list">'+review.calendarFragments.map((r,i)=>calendarReviewRow(r,'calendarFragments.'+i)).join('')+'</div></details>';
 }
 if(review.type==='squad'||review.type==='calendar')content+=listReviewControls(review);
 if(review.type!=='match'&&!review[review.type==='squad'?'players':'calendar'].length)content+='<p class="warning">Nenhum registro reconhecido. Confira os avisos e tente completar a leitura.</p>';
 const visible=new Set(relevantConflicts(review.type,review.conflicts||[]));
 const conflicts=(review.conflicts||[]).map((c,i)=>visible.has(c)&&!c.resolved?conflictItem(c,i,review):'').join(''),resolved=(review.conflicts||[]).map((c,i)=>visible.has(c)&&c.resolved?conflictItem(c,i,review):'').join('');
 return '<form id="reviewForm" onsubmit="return false">'+content+'</form>'+(conflicts?'<details open><summary>Valores divergentes: confira nas telas</summary><ul>'+conflicts+'</ul></details>':'')+(resolved?'<details><summary>Diferenças resolvidas pela leitura comprovada</summary><ul>'+resolved+'</ul></details>':'');
}
export function readReview(review,container){
 const copy=row=>({...row,_fieldSources:{...row._fieldSources}});
 const out={match:{...review.match,_fieldSources:{...review._matchFieldSources}},players:review.players.map(copy),playerCandidates:(review.playerCandidates||[]).map(copy),calendar:review.calendar.map(copy),calendarFragments:(review.calendarFragments||[]).map(copy),meta:{...review.meta,_fieldSources:{...review.meta?._fieldSources}}};
 for(const el of container.querySelectorAll('[data-review-path]')){const path=el.dataset.reviewPath.split('.');let target=out;for(const segment of path.slice(0,-1))target=target[segment];const raw=el.value.trim(),key=path.at(-1),value=el.dataset.kind==='include'?el.checked===true:el.dataset.kind==='boolean'?raw==='true'?true:raw==='false'?false:null:el.dataset.kind==='number'?raw===''?null:Number(raw):raw||'NI';if(key!=='_include'&&known(value)&&!Object.is(target[key],value))target._fieldSources[key]={kind:'manual',rank:4,source:'Editado na revisão'};target[key]=value;}
 const included=out.playerCandidates.filter(p=>p._include===true).map(p=>{const row={...p,_fieldSources:{...p._fieldSources}};delete row._include;delete row._rosterPending;delete row._rosterReason;for(const key of ['name','position','strength','shirtNumber','age','value','training','forSale'])if(known(row[key]))row._fieldSources[key]={kind:'manual',rank:4,source:'Confirmado na revisão'};return row;});
 if(review.type==='match')return out.match;
 if(review.type==='squad')return {players:[...out.players,...included],playerCandidates:out.playerCandidates.filter(p=>p._include!==true),meta:out.meta};
 const calendar=[...out.calendar,...out.calendarFragments.filter(r=>['round','date','time'].some(key=>known(r[key])))];
 calendar.meta=out.meta;return calendar;
}
