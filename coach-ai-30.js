'use strict';

/*
  OSM AI Coach Pro — Coach IA 3.0
  Camada não destrutiva carregada DEPOIS de app.js + clean-fix-v25.js.
  Objetivos:
  - transformar histórico em aprendizado contextual compartilhado entre slots;
  - diferenciar humano de CPU e aumentar a exigência contra humanos;
  - acompanhar ritmo de evolução do próprio time e rivais já analisados;
  - manter um plano de mercado persistente;
  - reordenar candidatos táticos por contexto real, sem prometer probabilidade;
  - explicar derrota e próxima ação.
*/
(function(){
  const COACH_VERSION='3.0.0';
  const MAX_SNAPSHOTS=36;
  const MAX_LESSONS=80;

  function h(v){
    return String(v ?? 'NI').replace(/[&<>"']/g,m=>({
      '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'
    }[m]));
  }
  function norm(v){
    return String(v||'').trim().toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/\s+/g,' ');
  }
  function finite(v){
    const n=Number(v); return Number.isFinite(n)?n:null;
  }
  function clamp(v,min,max){ return Math.max(min,Math.min(max,v)); }
  function filled(v){ return !(v===null||v===undefined||v===''||v==='NI'); }
  function arr(v){ return Array.isArray(v)?v:[]; }
  function cloneSafe(v){ try{return JSON.parse(JSON.stringify(v))}catch{return v} }
  function sameManagerAsUser(name){
    const a=norm(name),b=norm(typeof settings!=='undefined'?settings?.userNick:'leandrozzy');
    return !!a && !!b && a===b;
  }
  function isHumanOpponent(s){
    if(s?.opponent?.human===true)return true;
    if(s?.opponent?.human===false)return false;
    const manager=s?.opponent?.manager;
    if(filled(manager) && !sameManagerAsUser(manager))return true;
    return false;
  }
  function overallDiff(s){
    const a=finite(s?.myTeam?.overall),b=finite(s?.opponent?.overall);
    return a===null||b===null?null:a-b;
  }
  function sectorDiffs(s){
    const pairs=[['goalkeeper','GOL'],['defence','DEF'],['midfield','MEI'],['attack','ATA']];
    const out={};
    for(const [k,label] of pairs){
      const a=finite(s?.myTeam?.[k]),b=finite(s?.opponent?.[k]);
      out[label]=a===null||b===null?null:a-b;
    }
    return out;
  }
  function ensureCoach(s){
    if(!s)return null;
    if(!s.coachAI || typeof s.coachAI!=='object')s.coachAI={};
    const c=s.coachAI;
    c.version=COACH_VERSION;
    c.ownSnapshots=arr(c.ownSnapshots);
    c.rivalSnapshots=arr(c.rivalSnapshots);
    c.lessons=arr(c.lessons);
    c.processedResults=Number.isInteger(c.processedResults)?c.processedResults:0;
    c.marketPlan=c.marketPlan&&typeof c.marketPlan==='object'?c.marketPlan:null;
    c.lastTacticReview=c.lastTacticReview&&typeof c.lastTacticReview==='object'?c.lastTacticReview:null;
    return c;
  }
  function saveQuiet(){
    try{
      if(typeof STATE_KEY!=='undefined' && typeof state!=='undefined')localStorage.setItem(STATE_KEY,JSON.stringify(state));
    }catch(_){ }
  }
  function snapshotKey(s){
    const round=finite(s?.round);
    const date=s?.match?.nextMatchAt||'';
    return `${round??'NI'}|${date||'NI'}`;
  }
  function snapshotChanged(last,next){
    if(!last)return true;
    return ['overall','goalkeeper','defence','midfield','attack','squadValue','playerCount'].some(k=>String(last[k]??'')!==String(next[k]??''));
  }
  function captureSnapshots(s){
    if(!s || s.status!=='active')return;
    const c=ensureCoach(s),key=snapshotKey(s),at=new Date().toISOString();
    const own={
      key,at,round:finite(s.round),overall:finite(s.myTeam?.overall),goalkeeper:finite(s.myTeam?.goalkeeper),
      defence:finite(s.myTeam?.defence),midfield:finite(s.myTeam?.midfield),attack:finite(s.myTeam?.attack),
      squadValue:finite(s.myTeam?.squadValue),playerCount:finite(s.myTeam?.playerCount)
    };
    if(own.overall!==null){
      const last=c.ownSnapshots.at(-1);
      if(!last || last.key!==key || snapshotChanged(last,own)){
        c.ownSnapshots.push(own); c.ownSnapshots=c.ownSnapshots.slice(-MAX_SNAPSHOTS);
      }
    }

    const rivalName=s.opponent?.teamName;
    const rivalOverall=finite(s.opponent?.overall);
    if(filled(rivalName) && rivalOverall!==null){
      const rival={
        key,at,round:finite(s.round),teamName:rivalName,manager:s.opponent?.manager||null,human:isHumanOpponent(s),
        overall:rivalOverall,goalkeeper:finite(s.opponent?.goalkeeper),defence:finite(s.opponent?.defence),
        midfield:finite(s.opponent?.midfield),attack:finite(s.opponent?.attack),squadValue:finite(s.opponent?.squadValue),
        playerCount:finite(s.opponent?.playerCount),formation:s.opponent?.formation||null,style:s.opponent?.style||null
      };
      const last=[...c.rivalSnapshots].reverse().find(x=>norm(x.teamName)===norm(rivalName));
      if(!last || last.key!==key || snapshotChanged(last,rival)){
        c.rivalSnapshots.push(rival); c.rivalSnapshots=c.rivalSnapshots.slice(-MAX_SNAPSHOTS);
      }
    }
  }
  function growth(rows,teamName=null){
    let r=arr(rows);
    if(teamName)r=r.filter(x=>norm(x.teamName)===norm(teamName));
    r=r.filter(x=>finite(x.overall)!==null);
    if(r.length<2)return {delta:null,rounds:null,rate:null,first:r[0]||null,last:r.at(-1)||null};
    const first=r[0],last=r.at(-1),delta=finite(last.overall)-finite(first.overall);
    const roundDelta=(finite(last.round)!==null&&finite(first.round)!==null)?Math.max(1,finite(last.round)-finite(first.round)):Math.max(1,r.length-1);
    return {delta,rounds:roundDelta,rate:delta/roundDelta,first,last};
  }
  function competitiveness(s){
    const c=ensureCoach(s),own=growth(c.ownSnapshots),oppName=s.opponent?.teamName,opp=growth(c.rivalSnapshots,oppName);
    const diff=overallDiff(s);
    let evolutionGap=null;
    if(own.rate!==null&&opp.rate!==null)evolutionGap=own.rate-opp.rate;
    let level='ok',title='Ritmo competitivo sob controle',detail='Continue atualizando elenco e adversários para a IA medir o ritmo da liga.';
    if(isHumanOpponent(s) && diff!==null && diff<=-8){
      level='danger';title='Humano abriu vantagem de força';detail=`Você está ${Math.abs(diff)} ponto(s) abaixo do adversário atual.`;
    }else if(isHumanOpponent(s) && evolutionGap!==null && evolutionGap<=-1.5){
      level='danger';title='Você está evoluindo mais devagar';detail=`Ritmo estimado: você ${own.rate.toFixed(1)}/rodada × rival ${opp.rate.toFixed(1)}/rodada.`;
    }else if(isHumanOpponent(s) && diff!==null && diff<0){
      level='warn';title='Atenção ao humano';detail=`Diferença atual: ${diff} ponto(s). O Diretor deve priorizar titulares.`;
    }
    return {own,opp,evolutionGap,diff,level,title,detail};
  }

  function resultOutcome(r){
    const gf=finite(r?.gf),ga=finite(r?.ga);
    if(gf===null||ga===null)return 0;
    return gf>ga?1:gf===ga?0:-1;
  }
  function resultHuman(r){
    if(r?.context?.human===true)return true;
    if(r?.context?.human===false)return false;
    return !!r?.context?.manager;
  }
  function similarity(s,r,candidate){
    let w=1;
    const ctx=r?.context||{};
    if(candidate?.formation && r?.tactic?.formation===candidate.formation)w+=3.5;
    if(filled(s?.opponent?.formation)&&ctx.oppFormation===s.opponent.formation)w+=3;
    if(filled(s?.opponent?.style)&&ctx.oppStyle===s.opponent.style)w+=1.2;
    if(filled(s?.match?.venue)&&ctx.venue===s.match.venue)w+=1.2;
    const dNow=overallDiff(s),dThen=(finite(ctx.myOverall)!==null&&finite(ctx.oppOverall)!==null)?finite(ctx.myOverall)-finite(ctx.oppOverall):null;
    if(dNow!==null&&dThen!==null){
      const dd=Math.abs(dNow-dThen);
      w+=dd<=4?3:dd<=9?1.5:dd<=15?.5:0;
    }
    const nowHuman=isHumanOpponent(s),thenHuman=resultHuman(r);
    if(nowHuman===thenHuman)w+=2;
    else if(nowHuman)w-=.5;
    if(filled(s?.match?.refereeColor)&&ctx.referee===s.match.refereeColor)w+=.6;
    return Math.max(.2,w);
  }
  function sharedLearningScore(s,candidate){
    const all=(typeof state!=='undefined'?state.slots:[]).flatMap(x=>arr(x.results));
    let num=0,den=0,n=0;
    for(const r of all){
      const w=similarity(s,r,candidate);
      if(w<3)continue;
      const outcome=resultOutcome(r);
      let val=outcome*5;
      const gf=finite(r.gf),ga=finite(r.ga);
      if(gf!==null&&ga!==null)val+=clamp((gf-ga)*.8,-2.4,2.4);
      const reds=finite(r?.stats?.oppRedCards);
      const myReds=finite(r?.stats?.myRedCards);
      let reliability=1;
      if(reds!==null&&reds>0)reliability*=.55;
      if(myReds!==null&&myReds>0)reliability*=.55;
      num+=val*w*reliability;den+=w*reliability;n++;
    }
    return {adjustment:den?clamp(num/den,-12,12):0,samples:n};
  }
  function tackleSafetyScore(tackling,ref){
    const r=norm(ref),t=norm(tackling);
    if(!r||!t)return 0;
    if(r.includes('vermelh'))return t.includes('cuidad')?10:t.includes('agress')?-18:-8;
    if(r.includes('laranj'))return t.includes('cuidad')?7:t.includes('agress')?-12:-3;
    if(r.includes('amarel'))return t.includes('agress')?-5:t.includes('normal')?3:1;
    if(r.includes('azul'))return t.includes('normal')?3:t.includes('agress')?2:1;
    if(r.includes('verde'))return t.includes('agress')?4:t.includes('normal')?3:1;
    return 0;
  }
  function formationBaseScore(s,c){
    let score=50;
    const form=String(c?.formation||''),plan=norm(c?.gamePlan),opp=String(s?.opponent?.formation||''),style=norm(s?.opponent?.style);
    const d=overallDiff(s),sec=sectorDiffs(s),human=isHumanOpponent(s),away=norm(s?.match?.venue).includes('fora');

    if(human)score+=2;
    if(d!==null){
      if(d>=13){
        if(form.startsWith('4-3-3'))score+=9;
        if(['5-4-1 A','5-4-1 B','6-3-1 A','6-3-1 B'].includes(form))score-=6;
      }else if(d>=5){
        if(['4-3-3 A','4-3-3 B','4-4-2 B','4-2-3-1'].includes(form))score+=5;
      }else if(d>-6){
        if(['4-2-3-1','4-5-1','4-4-2 B','4-3-3 B'].includes(form))score+=5;
      }else if(d>-14){
        if(['4-5-1','4-2-3-1','5-3-2','5-3-1-1'].includes(form))score+=10;
        if(form.startsWith('4-3-3'))score-=human?7:3;
      }else{
        if(['5-3-2','5-4-1 A','5-4-1 B','4-5-1','5-3-1-1'].includes(form))score+=12;
        if(form.startsWith('4-3-3'))score-=12;
      }
    }
    if(away && d!==null && d<5 && ['4-5-1','5-3-2','5-3-1-1','4-2-3-1'].includes(form))score+=4;
    if(sec.MEI!==null&&sec.MEI<=-6 && ['4-5-1','4-2-3-1','3-5-2'].includes(form))score+=7;
    if(sec.DEF!==null&&sec.DEF<=-7 && ['5-3-2','5-3-1-1','5-4-1 A','5-4-1 B'].includes(form))score+=7;
    if(sec.ATA!==null&&sec.ATA>=7 && ['4-3-3 A','4-3-3 B','4-2-4 A','4-2-4 B'].includes(form))score+=4;

    if(opp.startsWith('4-3-3')){
      if(['4-5-1','4-2-3-1','5-3-2'].includes(form))score+=human?8:5;
      if(plan.includes('contra'))score+=3;
    }
    if(opp.startsWith('3-')){
      if(plan.includes('ala'))score+=5;
      if(['4-3-3 A','4-3-3 B','4-2-3-1'].includes(form))score+=3;
    }
    if(opp.startsWith('5-')){
      if(plan.includes('passe')||plan.includes('vista'))score+=3;
      if(d!==null&&d>0&&form.startsWith('4-3-3'))score+=4;
    }
    if(style.includes('contra') && finite(c?.pressure)!==null && finite(c.pressure)>78)score-=6;
    if(style.includes('bola longa') && ['5-3-2','5-3-1-1'].includes(form))score+=3;
    score+=tackleSafetyScore(c?.tackling,s?.match?.refereeColor);

    const learn=sharedLearningScore(s,c);score+=learn.adjustment;
    return {score,learn};
  }
  function slidersContextScore(s,c){
    let score=0;
    const d=overallDiff(s),p=finite(c?.pressure),m=finite(c?.mentality),t=finite(c?.tempo);
    if(p===null||m===null||t===null)return -30;
    if(d!==null&&d<=-10){
      if(p>=45&&p<=68)score+=4; else if(p>82)score-=7;
      if(m>=30&&m<=58)score+=4; else if(m>78)score-=7;
      if(t>=55&&t<=78)score+=3;
    }else if(d!==null&&d>=13){
      if(p>=68&&p<=86)score+=4;
      if(m>=68&&m<=88)score+=4;
      if(t>=70&&t<=90)score+=4;
    }else{
      if(p>=55&&p<=78)score+=3;
      if(m>=48&&m<=75)score+=3;
      if(t>=60&&t<=84)score+=3;
    }
    return score;
  }
  function reviewCandidates(s){
    if(!s)return null;
    const raw=arr(s.tacticCandidates).length?arr(s.tacticCandidates):s.tactic?[s.tactic]:[];
    if(!raw.length)return null;
    const ranked=raw.map((c,i)=>{
      const base=formationBaseScore(s,c),slider=slidersContextScore(s,c);
      return {...cloneSafe(c),coachScore:Math.round((base.score+slider)*10)/10,coachLearningSamples:base.learn.samples,_idx:i};
    }).sort((a,b)=>b.coachScore-a.coachScore);
    s.tacticCandidates=ranked;
    const best=ranked[0];
    if(best){
      const prev=s.tactic;
      s.tactic={...cloneSafe(best)};
      delete s.tactic._idx;
      s.tactic.engine='Coach IA 3.0 · multi-contexto';
      const reasons=[];
      const d=overallDiff(s),sec=sectorDiffs(s);
      if(isHumanOpponent(s))reasons.push('adversário humano recebeu peso maior');
      if(d!==null)reasons.push(`diferença geral ${d>=0?'+':''}${d}`);
      const weak=Object.entries(sec).filter(([,v])=>v!==null&&v<=-5).map(([k,v])=>`${k} ${v}`).join(', ');
      if(weak)reasons.push(`setores em desvantagem: ${weak}`);
      if(best.coachLearningSamples)reasons.push(`${best.coachLearningSamples} resultado(s) semelhante(s) influenciaram o ranking`);
      if(filled(s.match?.refereeColor))reasons.push(`árbitro ${s.match.refereeColor}`);
      s.tactic.reason=`${best.reason?best.reason+' ':''}Revisão Coach IA 3.0: ${reasons.join('; ')}.`.trim();
      ensureCoach(s).lastTacticReview={at:new Date().toISOString(),previous:prev?cloneSafe(prev):null,selected:cloneSafe(s.tactic),top:ranked.slice(0,5).map(x=>({formation:x.formation,gamePlan:x.gamePlan,coachScore:x.coachScore,samples:x.coachLearningSamples}))};
    }
    return ranked;
  }

  function playerPos(p){
    if(typeof normalizePos==='function' && typeof playerPosValue==='function')return normalizePos(playerPosValue(p));
    const x=norm(p?.position||p?.pos||p?.role).toUpperCase();
    return x;
  }
  function playerRating(p){
    if(typeof playerRatingValue==='function')return finite(playerRatingValue(p));
    return finite(p?.rating??p?.overall??p?.strength);
  }
  function playerAge(p){
    if(typeof playerAgeValue==='function')return finite(playerAgeValue(p));
    return finite(p?.age);
  }
  function playerName(p){
    if(typeof playerNameValue==='function')return playerNameValue(p)||'Jogador';
    return p?.name||'Jogador';
  }
  function marketFingerprint(s){
    return JSON.stringify({overall:s?.myTeam?.overall,round:s?.round,roster:arr(s?.roster).map(p=>[playerName(p),playerPos(p),playerRating(p),playerAge(p),!!p.training,!!p.forSale])});
  }
  function makeMarketPlan(s,force=false){
    const c=ensureCoach(s),fp=marketFingerprint(s);
    if(!force && c.marketPlan?.fingerprint===fp)return c.marketPlan;
    const roster=arr(s.roster),by={ATA:[],MEI:[],DEF:[],GOL:[]},targets={ATA:4,MEI:6,DEF:6,GOL:2};
    for(const p of roster){const pos=playerPos(p),rating=playerRating(p);if(by[pos])by[pos].push({...p,_rating:rating??-1,_age:playerAge(p),_pos:pos})}
    for(const list of Object.values(by))list.sort((a,b)=>b._rating-a._rating);
    const comp=competitiveness(s),overall=finite(s.myTeam?.overall);
    let desiredGain=6;
    if(comp.diff!==null&&comp.diff<=-8)desiredGain=10;
    if(comp.evolutionGap!==null&&comp.evolutionGap<=-1.5)desiredGain=Math.max(desiredGain,9);
    const targetOverall=overall===null?null:overall+desiredGain;

    const needs=[];
    for(const [pos,targetCount] of Object.entries(targets)){
      const list=by[pos],count=list.length;
      const starters=pos==='GOL'?1:pos==='ATA'?3:pos==='MEI'?3:4;
      const starterSlice=list.slice(0,Math.min(starters,list.length)).filter(x=>x._rating>=0);
      const avg=starterSlice.length?starterSlice.reduce((a,x)=>a+x._rating,0)/starterSlice.length:null;
      const weakest=list.filter(x=>x._rating>=0).at(-1)||null;
      let priority=count<targetCount?100+(targetCount-count)*10:0;
      if(avg!==null&&overall!==null)priority+=Math.max(0,overall-avg)*3;
      if(weakest&&overall!==null)priority+=Math.max(0,overall-weakest._rating)*1.2;
      const targetRating=Math.max(overall||0,(avg||0)+5,(weakest?weakest._rating+8:0));
      needs.push({position:pos,count,targetCount,avg:avg===null?null:Math.round(avg*10)/10,priority,targetRating:Math.ceil(targetRating)});
    }
    needs.sort((a,b)=>b.priority-a.priority);

    const sell=[];
    for(const [pos,targetCount] of Object.entries(targets)){
      const list=by[pos].filter(x=>!x.training);
      const excess=Math.max(0,list.length-targetCount);
      const ranked=[...list].sort((a,b)=>a._rating-b._rating);
      for(let i=0;i<excess;i++)if(ranked[i])sell.push(ranked[i]);
    }
    if(sell.length<4){
      const protectedNames=new Set(Object.values(by).flatMap(list=>list.slice(0,Math.max(1,Math.ceil(list.length*.55))).map(playerName)));
      const candidates=roster.filter(p=>!p.training&&!p.forSale&&!protectedNames.has(playerName(p))&&playerRating(p)!==null).sort((a,b)=>playerRating(a)-playerRating(b));
      for(const p of candidates){if(sell.length>=4)break;if(!sell.some(x=>playerName(x)===playerName(p)))sell.push(p)}
    }
    const training=[...roster].filter(p=>!p.forSale&&playerRating(p)!==null).sort((a,b)=>{
      const aa=playerAge(a),ab=playerAge(b),youngA=aa!==null?Math.max(0,26-aa):0,youngB=ab!==null?Math.max(0,26-ab):0;
      return (youngB*3+playerRating(b))-(youngA*3+playerRating(a));
    }).slice(0,4);

    const actions=[];
    if(comp.level==='danger')actions.push('PRIORIDADE MÁXIMA: recuperar ritmo de evolução antes da próxima sequência de jogos humanos.');
    if(sell.length)actions.push(`Manter até ${Math.min(4,sell.length)} jogador(es) de menor impacto à venda para financiar upgrades.`);
    const topNeed=needs[0];
    if(topNeed)actions.push(`Próxima compra: ${topNeed.position} de força ${topNeed.targetRating}+; substituir reserva/fraco antes de ampliar o elenco.`);
    if(training.length)actions.push(`Treino prioritário: ${training.map(playerName).join(', ')}.`);
    if(targetOverall!==null)actions.push(`Meta operacional: força ${overall} → ${targetOverall}; recalcular a cada alteração do elenco.`);

    c.marketPlan={
      version:COACH_VERSION,generatedAt:new Date().toISOString(),fingerprint:fp,baselineOverall:overall,targetOverall,
      desiredGain,competitiveness:comp,needs,sellCandidates:sell.slice(0,4).map(p=>({name:playerName(p),position:playerPos(p),rating:playerRating(p),age:playerAge(p)})),
      trainingPriority:training.map(p=>({name:playerName(p),position:playerPos(p),rating:playerRating(p),age:playerAge(p)})),actions
    };
    return c.marketPlan;
  }

  function diagnoseResult(s,r){
    const out=resultOutcome(r),ctx=r?.context||{},stats=r?.stats||{},t=r?.tactic||{};
    const causes=[],changes=[];
    const d=(finite(ctx.myOverall)!==null&&finite(ctx.oppOverall)!==null)?finite(ctx.myOverall)-finite(ctx.oppOverall):null;
    if(d!==null&&d<=-8)causes.push(`diferença de força de ${Math.abs(d)} ponto(s) contra`);
    if(resultHuman(r))causes.push('partida contra humano, contexto de maior variabilidade');
    const myShots=finite(stats.myShots),oppShots=finite(stats.oppShots);
    if(myShots!==null&&oppShots!==null&&myShots+2<=oppShots)causes.push(`produção ofensiva inferior (${myShots} × ${oppShots} remates)`);
    const myPoss=finite(stats.myPossession),oppPoss=finite(stats.oppPossession);
    if(myPoss!==null&&oppPoss!==null&&myPoss+8<=oppPoss)causes.push(`posse inferior (${myPoss} × ${oppPoss})`);
    if(finite(stats.myRedCards)>0)causes.push('expulsão própria reduziu a confiabilidade para avaliar a tática');
    if(finite(stats.oppRedCards)>0)causes.push('expulsão rival reduz a utilidade deste jogo para aprender a tática');
    if(out<0){
      changes.push(`reduzir peso de ${t.formation||'formação NI'} neste contexto`);
      if(filled(ctx.oppFormation))changes.push(`testar família tática diferente contra ${ctx.oppFormation}`);
      changes.push('comparar sliders e plano com jogos similares dos quatro slots');
    }else if(out>0){
      changes.push('aumentar peso apenas em contextos semelhantes; não transformar em receita fixa');
    }else changes.push('manter como evidência neutra e exigir amostra maior');
    return {causes,changes};
  }
  function processResults(s){
    const c=ensureCoach(s),results=arr(s.results);
    while(c.processedResults<results.length){
      const r=results[c.processedResults];
      const pending=c.pendingResultContext;
      const pendingMatches=pending && (!pending.opponent || !r?.opponent || norm(pending.opponent)===norm(r.opponent));
      if(r?.context && r.context.human===undefined)r.context.human=pendingMatches?!!pending.human:isHumanOpponent(s);
      if(r?.context && !r.context.manager)r.context.manager=pendingMatches?(pending.manager||null):(s?.opponent?.manager||null);
      const diag=diagnoseResult(s,r);
      c.lessons.unshift({at:r?.createdAt||new Date().toISOString(),opponent:r?.opponent||null,score:r?.score||null,outcome:resultOutcome(r),formation:r?.tactic?.formation||null,oppFormation:r?.context?.oppFormation||null,human:resultHuman(r),causes:diag.causes,changes:diag.changes});
      c.lessons=c.lessons.slice(0,MAX_LESSONS);
      c.processedResults++;
    }
  }
  function coachCardHtml(s){
    const comp=competitiveness(s),c=ensureCoach(s),own=comp.own,opp=comp.opp;
    const label=comp.level==='danger'?'URGENTE':comp.level==='warn'?'ATENÇÃO':'COACH IA 3.0';
    const cls=comp.level==='danger'?'coach-danger':comp.level==='warn'?'coach-warn':'coach-ok';
    return `<div class="card coach30-card ${cls}">
      <div class="coach30-head"><div><span class="eyebrow">${h(label)} · SLOT ${h(s.slotNumber)}</span><h3>${h(comp.title)}</h3></div><span class="coach30-version">v${COACH_VERSION}</span></div>
      <p class="small">${h(comp.detail)}</p>
      <div class="coach30-kpis">
        <div><span>Força atual</span><b>${h(s.myTeam?.overall)}</b></div>
        <div><span>Rival</span><b>${h(s.opponent?.overall)}</b></div>
        <div><span>Sua evolução</span><b>${own.delta===null?'NI':(own.delta>=0?'+':'')+own.delta}</b></div>
        <div><span>Evolução rival</span><b>${opp.delta===null?'NI':(opp.delta>=0?'+':'')+opp.delta}</b></div>
      </div>
      <div class="actions"><button class="btn" onclick="showView('market')">Abrir Diretor IA</button><button class="btn ghost" onclick="showView('pregame')">Preparar partida</button></div>
    </div>`;
  }
  function pregameCoachHtml(s){
    const sec=sectorDiffs(s),comp=competitiveness(s),review=ensureCoach(s).lastTacticReview;
    const ranked=arr(review?.top).slice(0,3);
    return `<div class="card coach30-pregame">
      <div class="coach30-head"><div><span class="eyebrow">ANÁLISE ESTRATÉGICA ${isHumanOpponent(s)?'· HUMANO':'· CPU/NI'}</span><h3>Leitura antes da tática</h3></div></div>
      <div class="coach30-kpis sectors">
        ${Object.entries(sec).map(([k,v])=>`<div><span>${h(k)}</span><b class="${v!==null&&v<0?'neg':'pos'}">${v===null?'NI':(v>=0?'+':'')+v}</b></div>`).join('')}
      </div>
      <p class="small muted">${h(comp.detail)} ${isHumanOpponent(s)?'Contra humano, histórico semelhante e equilíbrio por setor recebem peso maior.':''}</p>
      ${ranked.length?`<div class="coach30-ranked"><b>Top candidatos após revisão:</b>${ranked.map((x,i)=>`<span>${i+1}. ${h(x.formation)} · ${h(x.gamePlan)} · índice ${h(x.coachScore)}</span>`).join('')}</div>`:''}
    </div>`;
  }
  function marketCoachHtml(s){
    const p=makeMarketPlan(s),comp=p.competitiveness;
    return `<div class="card coach30-market ${comp.level==='danger'?'coach-danger':comp.level==='warn'?'coach-warn':''}">
      <div class="coach30-head"><div><span class="eyebrow">DIRETOR IA 3.0 · PLANO PERSISTENTE</span><h3>${comp.level==='danger'?'Recuperar competitividade':'Plano de crescimento do elenco'}</h3></div><button class="btn ghost tiny" onclick="coach30RecalculateMarket()">Recalcular</button></div>
      <div class="coach30-kpis">
        <div><span>Atual</span><b>${h(p.baselineOverall)}</b></div><div><span>Meta</span><b>${h(p.targetOverall)}</b></div>
        <div><span>Ganho alvo</span><b>${p.desiredGain?`+${h(p.desiredGain)}`:'NI'}</b></div><div><span>Humano atual</span><b>${isHumanOpponent(s)?'Sim':'Não/NI'}</b></div>
      </div>
      <div class="coach30-actions-list">${p.actions.map(x=>`<div><b>${h(x)}</b></div>`).join('')}</div>
      <div class="coach30-grid2">
        <div><h4>Colocar à venda</h4>${p.sellCandidates.length?p.sellCandidates.map(x=>`<p>${h(x.name)} · ${h(x.position)} · ${h(x.rating)}</p>`).join(''):'<p class="muted small">Sem venda automática recomendada com os dados atuais.</p>'}</div>
        <div><h4>Treino prioritário</h4>${p.trainingPriority.length?p.trainingPriority.map(x=>`<p>${h(x.name)} · ${h(x.position)} · ${h(x.rating)}${x.age?' · '+h(x.age)+'a':''}</p>`).join(''):'<p class="muted small">Atualize o elenco para priorizar treino.</p>'}</div>
      </div>
      ${p.needs[0]?`<div class="reason-box"><b>Próxima compra</b><p>${h(p.needs[0].position)} · buscar força ${h(p.needs[0].targetRating)}+ · média atual da posição ${h(p.needs[0].avg)}</p></div>`:''}
    </div>`;
  }
  function learningCoachHtml(s){
    const c=ensureCoach(s),lessons=c.lessons.slice(0,6);
    const all=(typeof state!=='undefined'?state.slots:[]).flatMap(x=>arr(x.results));
    const humans=all.filter(resultHuman),humanLoss=humans.filter(r=>resultOutcome(r)<0).length;
    return `<div class="card coach30-learning">
      <div class="coach30-head"><div><span class="eyebrow">APRENDIZADO COMPARTILHADO · 4 SLOTS</span><h3>O que muda nas próximas decisões</h3></div></div>
      <div class="coach30-kpis"><div><span>Resultados totais</span><b>${all.length}</b></div><div><span>Jogos humanos identificados</span><b>${humans.length}</b></div><div><span>Derrotas humanas</span><b>${humanLoss}</b></div><div><span>Lições deste slot</span><b>${c.lessons.length}</b></div></div>
      ${lessons.length?`<div class="coach30-lessons">${lessons.map(x=>`<div><b>${h(x.score||'Resultado')} · ${h(x.opponent||'Rival')}</b><span>${h((x.causes||[]).join('; ')||'Sem causa dominante identificada.')}</span><small>${h((x.changes||[]).join('; '))}</small></div>`).join('')}</div>`:'<p class="muted small">Registre resultados para a IA comparar contexto, não apenas formação.</p>'}
    </div>`;
  }

  // Preserva o contexto do adversário ANTES de finishResult avançar o calendário.
  if(typeof window.saveResult==='function'){
    const baseSaveResult30=window.saveResult;
    window.saveResult=function(slotNo){
      const s=state?.slots?.[Number(slotNo)-1];
      if(s){
        const c=ensureCoach(s);
        c.pendingResultContext={opponent:s.opponent?.teamName||null,human:isHumanOpponent(s),manager:s.opponent?.manager||null,at:new Date().toISOString()};
      }
      const out=baseSaveResult30.apply(this,arguments);
      if(s){ensureCoach(s).pendingResultContext=null;saveQuiet()}
      return out;
    };
  }
  if(typeof v21ApplyResult==='function'){
    const baseApplyResult30=v21ApplyResult;
    v21ApplyResult=function(r){
      const s=typeof selectedSlot==='function'?selectedSlot():state?.slots?.[state.selectedSlot-1];
      const before=s?arr(s.results).length:0;
      const ctx=s?{opponent:s.opponent?.teamName||null,human:isHumanOpponent(s),manager:s.opponent?.manager||null}:null;
      const out=baseApplyResult30.apply(this,arguments);
      if(s && arr(s.results).length>before){
        const row=s.results.at(-1);row.context=row.context||{};row.context.human=!!ctx.human;row.context.manager=ctx.manager||null;
      }
      return out;
    };
    window.v21ApplyResult=v21ApplyResult;
  }

  window.coach30RecalculateMarket=function(){
    const s=typeof selectedSlot==='function'?selectedSlot():state?.slots?.[state.selectedSlot-1];
    if(!s)return;
    ensureCoach(s).marketPlan=null;makeMarketPlan(s,true);saveQuiet();
    if(typeof renderMarket==='function')renderMarket();
    if(typeof toast==='function')toast('Plano do Diretor IA recalculado');
  };

  function runCoachCycle(){
    if(typeof state==='undefined'||!Array.isArray(state.slots))return;
    for(const s of state.slots){if(s?.status==='active'){ensureCoach(s);captureSnapshots(s);processResults(s);makeMarketPlan(s)}}
    saveQuiet();
  }

  // Reforça o ranking depois que o motor existente gerar candidatos (locais + Gemini).
  if(typeof generateTactic==='function'){
    const baseGenerateTactic=generateTactic;
    generateTactic=async function(n){
      const s=state.slots[n-1];
      if(s){ensureCoach(s);captureSnapshots(s);processResults(s)}
      const out=await baseGenerateTactic(n);
      if(s && arr(s.tacticCandidates).length){reviewCandidates(s);saveQuiet();if(typeof renderPregame==='function')renderPregame()}
      return out;
    };
    window.generateTactic=generateTactic;
  }

  // Se algum fluxo externo gerar candidatos e apenas renderizar, revisa uma vez por conjunto.
  function opportunisticTacticReview(s){
    if(!s||!arr(s.tacticCandidates).length)return;
    const signature=JSON.stringify(arr(s.tacticCandidates).map(x=>[x.formation,x.gamePlan,x.pressure,x.mentality,x.tempo]));
    const c=ensureCoach(s);
    if(c._candidateSignature===signature)return;
    c._candidateSignature=signature;reviewCandidates(s);saveQuiet();
  }

  if(typeof renderDashboard==='function'){
    const baseRenderDashboard=renderDashboard;
    renderDashboard=function(){
      runCoachCycle();baseRenderDashboard();
      const s=typeof selectedSlot==='function'?selectedSlot():null;
      const target=document.getElementById('heroAction');
      if(target&&s?.status==='active')target.insertAdjacentHTML('afterend',`<div id="coach30Dashboard">${coachCardHtml(s)}</div>`);
    };
    window.renderDashboard=renderDashboard;
  }
  if(typeof renderPregame==='function'){
    const baseRenderPregame=renderPregame;
    renderPregame=function(){
      runCoachCycle();
      const s=typeof selectedSlot==='function'?selectedSlot():null;
      if(s)opportunisticTacticReview(s);
      baseRenderPregame();
      const target=document.getElementById('pregameContent');
      if(target&&s?.status==='active')target.insertAdjacentHTML('afterbegin',pregameCoachHtml(s));
    };
    window.renderPregame=renderPregame;
  }
  if(typeof renderMarket==='function'){
    const baseRenderMarket=renderMarket;
    renderMarket=function(){
      runCoachCycle();baseRenderMarket();
      const s=typeof selectedSlot==='function'?selectedSlot():null,target=document.getElementById('marketContent');
      if(target&&s?.status==='active')target.insertAdjacentHTML('afterbegin',marketCoachHtml(s));
    };
    window.renderMarket=renderMarket;
  }
  if(typeof renderLearning==='function'){
    const baseRenderLearning=renderLearning;
    renderLearning=function(){
      runCoachCycle();baseRenderLearning();
      const s=typeof selectedSlot==='function'?selectedSlot():null,target=document.getElementById('learningContent');
      if(target&&s?.status==='active')target.insertAdjacentHTML('afterbegin',learningCoachHtml(s));
    };
    window.renderLearning=renderLearning;
  }

  // Aprimora o radar/urgência sem remover os alertas atuais.
  if(typeof renderRadar==='function'){
    const baseRenderRadar=renderRadar;
    renderRadar=function(){
      runCoachCycle();baseRenderRadar();
      const s=typeof selectedSlot==='function'?selectedSlot():null,el=document.getElementById('radarPanel');
      if(!el||!s||s.status!=='active')return;
      const comp=competitiveness(s);
      if(comp.level==='danger'){
        el.insertAdjacentHTML('beforeend',`<div class="card coach30-radar coach-danger"><b>⚠ Coach IA: ${h(comp.title)}</b><p class="small">${h(comp.detail)}</p><div class="actions"><button class="btn" onclick="showView('market')">Abrir ação recomendada</button></div></div>`);
      }
    };
    window.renderRadar=renderRadar;
  }

  // Marca contexto humano no momento em que novos resultados forem processados e força atualização inicial.
  try{runCoachCycle()}catch(err){console.warn('[Coach IA 3.0]',err)}
  try{if(typeof renderAll==='function')renderAll()}catch(err){console.warn('[Coach IA 3.0 render]',err)}

  window.OSM_COACH_AI_30={version:COACH_VERSION,reviewCandidates,makeMarketPlan,competitiveness,captureSnapshots,sharedLearningScore};
})();
