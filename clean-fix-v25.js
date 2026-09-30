'use strict';

/*
  OSM AI Coach Pro — CLEAN FIX 2.5.0
  Esta camada substitui apenas os fluxos problemáticos da main:
  - calendário / próximo adversário
  - resultado -> calendário
  - edição manual de partida
  - aprendizado visível
  - leitura do elenco
  - clareza de slot
  Não carrega os hotfixes 2.4.x anteriores.
*/
(function(){
  const CLEAN_VERSION='2.6.4';

  function e(v){
    return String(v ?? 'NI').replace(/[&<>"']/g,m=>({
      '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'
    }[m]));
  }
  function n(v){
    return String(v||'').trim().toLowerCase()
      .normalize('NFD').replace(/[\u0300-\u036f]/g,'')
      .replace(/\s+/g,' ');
  }
  function filled(v){
    return !(v===null||v===undefined||v===''||v==='NI');
  }
  function cloneSafe(v){
    try{return JSON.parse(JSON.stringify(v))}catch{return v}
  }
  function outcome(gf,ga){ return gf>ga?'V':gf===ga?'E':'D'; }
  function outcomeText(o){ return o==='V'?'Vitória':o==='E'?'Empate':'Derrota'; }

  function parseEventTime(x){
    if(x?.dateTime){
      const t=new Date(x.dateTime).getTime();
      if(Number.isFinite(t))return t;
    }
    const raw=String(x?.dateText||'').trim();
    let m=raw.match(/^(\d{1,2})[\/-](\d{1,2})[\/-](\d{2,4})(?:\s+(\d{1,2}):(\d{2}))?$/);
    if(!m)return null;
    let year=Number(m[3]); if(year<100)year+=2000;
    const hour=Number(m[4]||12),minute=Number(m[5]||0);
    const d=new Date(year,Number(m[2])-1,Number(m[1]),hour,minute,0,0);
    const t=d.getTime();
    return Number.isFinite(t)?t:null;
  }

  function realOpponent(v){
    const x=n(v);
    return !!x && !['ni','tbd','a definir','aguardando','?','-'].includes(x);
  }

  // "Condicional" significa que o confronto ainda não está confirmado.
  // Mesmo que OCR tenha devolvido algum texto estranho, ele não pode virar adversário automaticamente.
  function isPendingConditional(x){
    return !!x?.conditional && !x?.played;
  }

  function calendarRows(s){
    return Array.isArray(s?.schedule)?s.schedule:[];
  }

  function currentCalendarIndex(s){
    const rows=calendarRows(s);

    const pendingIndex=Number(s?.pendingFixture?.scheduleIndex);
    if(Number.isInteger(pendingIndex) && pendingIndex>=0 && pendingIndex<rows.length){
      const pendingRow=rows[pendingIndex];
      if(pendingRow && !pendingRow.played && !pendingRow.skipped){
        return pendingIndex;
      }
    }

    const opp=n(s?.opponent?.teamName);
    const round=Number(s?.round);

    if(opp){
      const matches=rows
        .map((x,i)=>({x,i}))
        .filter(({x})=>!x.played && !x.skipped && n(x.opponent)===opp);
      if(matches.length===1)return matches[0].i;
      if(matches.length>1 && Number.isFinite(round)){
        const exact=matches.find(({x})=>Number(x.round)===round);
        if(exact)return exact.i;
      }
    }

    if(Number.isFinite(round)){
      const byRound=rows
        .map((x,i)=>({x,i}))
        .filter(({x})=>!x.played && !x.skipped && Number(x.round)===round);
      if(byRound.length===1)return byRound[0].i;
    }
    return -1;
  }

  function futureEventsAfter(s,playedRow){
    const rows=calendarRows(s);
    const playedTime=parseEventTime(playedRow);
    const playedIndex=rows.indexOf(playedRow);

    return rows
      .map((x,i)=>({x,i,t:parseEventTime(x)}))
      .filter(({x,i,t})=>{
        if(!x || x.played || x.skipped)return false;
        if(playedTime!==null && t!==null)return t>playedTime;
        return i>playedIndex;
      })
      .sort((a,b)=>{
        if(a.t!==null && b.t!==null && a.t!==b.t)return a.t-b.t;
        if(a.t!==null && b.t===null)return -1;
        if(a.t===null && b.t!==null)return 1;
        return a.i-b.i;
      });
  }

  function clearRivalDetails(s){
    const keepName=s?.opponent?.teamName??null;
    s.opponent={
      teamName:keepName,
      human:null,manager:null,overall:null,goalkeeper:null,defence:null,midfield:null,attack:null,
      squadValue:null,playerCount:null,stadium:null,loginBonus:null,secretTraining:null,trainingCamp:null,
      formation:null,style:null,marking:null,offside:null,tackling:null
    };
    s.match=s.match||{};
    s.match.refereeName=null;
    s.match.refereeColor=null;
    s.tactic=null;
  }

  function setNextFromRow(s,row,index,{manual=false}={}){
    if(!row)return false;

    s.match=s.match||{};
    s.match.nextMatchAt=row.dateTime||null;
    s.match.venue=row.venue||null;
    if(Number.isFinite(Number(row.round)))s.round=Number(row.round);

    if(isPendingConditional(row) && !manual){
      // A próxima competição existe, mas o adversário ainda não.
      s.pendingFixture={
        scheduleIndex:index,
        round:row.round??null,
        competitionType:row.competitionType||'cup',
        dateTime:row.dateTime||null,
        dateText:row.dateText||null,
        venue:row.venue||null,
        status:'awaiting_opponent'
      };
      s.opponent=s.opponent||{};
      s.opponent.teamName=null;
      clearRivalDetails(s);
      return true;
    }

    if(!realOpponent(row.opponent))return false;

    s.pendingFixture=null;
    s.opponent=s.opponent||{};
    s.opponent.teamName=row.opponent;
    clearRivalDetails(s);
    s.opponent.teamName=row.opponent;
    return true;
  }

  function markPlayedFromResult(s,gf,ga,score){
    const idx=currentCalendarIndex(s);
    if(idx<0)return {row:null,index:-1};

    const row=s.schedule[idx];

    if(!realOpponent(row.opponent) && realOpponent(s?.opponent?.teamName)){
      row.opponent=s.opponent.teamName;
    }

    row.played=true;
    row.result=score||`${gf}-${ga}`;
    row.outcome=outcome(gf,ga);
    row.conditional=false;
    row.placeholder=false;

    if(s.pendingFixture && Number(s.pendingFixture.scheduleIndex)===idx){
      s.pendingFixture=null;
    }

    return {row,index:idx};
  }

  function advanceAfterResult(s,playedRow){
    const list=futureEventsAfter(s,playedRow);
    if(!list.length)return null;

    // A PRIMEIRA ocorrência cronológica é a próxima obrigação.
    // Se for Copa condicional sem rival, para nela; não pula para a liga.
    const next=list[0];
    setNextFromRow(s,next.x,next.i,{manual:false});
    return next.x;
  }

  function learnFromResult(s,entry){
    const rows=Array.isArray(s.results)?s.results:[];
    const stats=entry?.stats||{};
    const ctx=entry?.context||{};
    const t=entry?.tactic||{};
    const resultCode=outcome(entry.gf,entry.ga);
    const form=t?.formation||ctx?.myFormation||'NI';
    const oppForm=ctx?.oppFormation||'NI';
    const strength=ctx?.strengthBucket||'NI';

    const sameForm=rows.filter(r=>(r?.tactic?.formation||r?.context?.myFormation||'NI')===form);
    const formW=sameForm.filter(r=>Number(r.gf)>Number(r.ga)).length;
    const formD=sameForm.filter(r=>Number(r.gf)===Number(r.ga)).length;
    const formL=sameForm.filter(r=>Number(r.gf)<Number(r.ga)).length;

    const sameOppForm=rows.filter(r=>(r?.context?.oppFormation||'NI')===oppForm);
    const oppW=sameOppForm.filter(r=>Number(r.gf)>Number(r.ga)).length;
    const oppD=sameOppForm.filter(r=>Number(r.gf)===Number(r.ga)).length;
    const oppL=sameOppForm.filter(r=>Number(r.gf)<Number(r.ga)).length;

    const facts=[];
    facts.push(`Resultado: ${entry.score} (${outcomeText(resultCode)}).`);
    if(form!=='NI')facts.push(`Minha formação: ${form}.`);
    if(oppForm!=='NI')facts.push(`Formação rival: ${oppForm}.`);
    if(filled(ctx.venue))facts.push(`Local: ${ctx.venue}.`);
    if(filled(ctx.referee))facts.push(`Árbitro: ${ctx.referee}.`);
    if(filled(ctx.myOverall)||filled(ctx.oppOverall))facts.push(`Forças registradas: ${filled(ctx.myOverall)?ctx.myOverall:'NI'} × ${filled(ctx.oppOverall)?ctx.oppOverall:'NI'}.`);

    const statFacts=[];
    const pushStat=(label,a,b)=>{
      if(filled(a)||filled(b))statFacts.push(`${label}: ${filled(a)?a:'NI'} × ${filled(b)?b:'NI'}.`);
    };
    pushStat('Remates',stats.myShots,stats.oppShots);
    pushStat('Posse',stats.myPossession,stats.oppPossession);
    pushStat('Cantos',stats.myCorners,stats.oppCorners);
    pushStat('Faltas',stats.myFouls,stats.oppFouls);
    pushStat('Amarelos',stats.myYellowCards,stats.oppYellowCards);
    pushStat('Vermelhos',stats.myRedCards,stats.oppRedCards);

    const patterns=[];
    if(form!=='NI')patterns.push(`${form}: ${sameForm.length} jogo(s) neste slot — ${formW}V/${formD}E/${formL}D.`);
    if(oppForm!=='NI')patterns.push(`Contra ${oppForm}: ${sameOppForm.length} jogo(s) — ${oppW}V/${oppD}E/${oppL}D.`);

    const increaseWeight=[];
    const decreaseWeight=[];
    const avoid=[];
    const nextUse=[];

    if(form!=='NI'){
      if(formW>=2 && formW>formL)increaseWeight.push(`${form} ganha peso quando o contexto for semelhante.`);
      if(formL>=2 && formL>formW){
        decreaseWeight.push(`${form} perde peso em contexto semelhante.`);
        avoid.push(`Evitar repetir automaticamente ${form} nas mesmas condições sem mudar plano/sliders.`);
      }
    }

    if(oppForm!=='NI'){
      if(oppW>=2 && oppW>oppL)increaseWeight.push(`As respostas usadas contra ${oppForm} estão funcionando melhor.`);
      if(oppL>=2 && oppL>oppW)decreaseWeight.push(`Histórico contra ${oppForm} está ruim; buscar alternativa.`);
    }

    if(filled(stats.myShots)&&filled(stats.oppShots)){
      const ms=Number(stats.myShots),os=Number(stats.oppShots);
      if(Number.isFinite(ms)&&Number.isFinite(os)){
        if(resultCode==='D' && ms>os){
          decreaseWeight.push('Mais remates sem conversão não serão tratados como sucesso tático.');
          nextUse.push('Priorizar eficiência ofensiva/mentalidade/ritmo em cenário semelhante.');
        }
        if(resultCode==='V' && ms>os)increaseWeight.push('Superioridade em remates acompanhou vitória.');
        if(resultCode==='V' && ms<os)nextUse.push('Vitória eficiente com menos remates; não aumentar pressão automaticamente.');
      }
    }

    if(filled(stats.myPossession)&&filled(stats.oppPossession)){
      const mp=parseFloat(String(stats.myPossession).replace(',','.'));
      const op=parseFloat(String(stats.oppPossession).replace(',','.'));
      if(Number.isFinite(mp)&&Number.isFinite(op)){
        if(resultCode==='D' && mp>op)decreaseWeight.push('Mais posse sem resultado: posse isolada perde importância.');
        if(resultCode==='V' && mp<op)increaseWeight.push('Vitória sem dominar posse: posse alta não é prioridade absoluta.');
      }
    }

    if(filled(stats.myRedCards) && Number(stats.myRedCards)>0){
      decreaseWeight.push('Houve vermelho do meu time; este jogo terá peso menor para avaliar a tática.');
    }
    if(filled(stats.oppRedCards) && Number(stats.oppRedCards)>0){
      decreaseWeight.push('Rival teve vermelho; este jogo terá peso menor para avaliar a tática.');
    }

    if(resultCode==='V')nextUse.push('Reutilizar apenas se força, local, árbitro e formação rival forem semelhantes.');
    else if(resultCode==='D')nextUse.push('No próximo contexto semelhante, testar formação/plano/sliders diferentes.');
    else nextUse.push('Empate mantém a solução como neutra; exigir mais evidência antes de priorizar.');

    const lesson={
      at:new Date().toISOString(),
      opponent:entry.opponent||null,
      score:entry.score,
      outcome:resultCode,
      formation:form,
      opponentFormation:oppForm,
      strengthBucket:strength,
      tactic:cloneSafe(t),
      context:cloneSafe(ctx),
      stats:cloneSafe(stats),
      events:cloneSafe(Array.isArray(entry?.events)?entry.events:[]),
      facts,
      statFacts,
      patterns,
      increaseWeight,
      decreaseWeight,
      avoid,
      nextUse,
      notes:[
        ...facts,
        ...statFacts,
        ...patterns,
        ...increaseWeight.map(x=>'Ganha peso: '+x),
        ...decreaseWeight.map(x=>'Perde peso: '+x),
        ...avoid.map(x=>'Evitar: '+x),
        ...nextUse.map(x=>'Próxima decisão: '+x)
      ],
      sampleSize:rows.length
    };

    s.lastLearning=lesson;
    s.learningLog=Array.isArray(s.learningLog)?s.learningLog:[];
    s.learningLog.unshift(lesson);
    s.learningLog=s.learningLog.slice(0,40);
    return lesson;
  }

  function finishResult(s,r){
    const gf=Number(r?.gf),ga=Number(r?.ga);
    if(!Number.isFinite(gf)||!Number.isFinite(ga))throw new Error('Não consegui identificar o placar final.');

    const currentIdx=currentCalendarIndex(s);
    const currentRow=currentIdx>=0?s.schedule[currentIdx]:null;

    // Nunca usa nome extraído do vídeo para trocar o slot.
    const opponent=currentRow?.opponent || s.opponent?.teamName || null;
    const score=r?.score||`${gf}-${ga}`;

    const entry={
      createdAt:new Date().toISOString(),
      opponent,
      gf,ga,score,
      tactic:s.tactic?cloneSafe(s.tactic):null,
      stats:r?.stats||{},
      events:r?.events||[],
      context:{
        myOverall:s.myTeam?.overall,
        oppOverall:s.opponent?.overall,
        oppFormation:r?.oppFormation||s.opponent?.formation||null,
        myFormation:r?.myFormation||s.tactic?.formation||null,
        oppStyle:s.opponent?.style||null,
        venue:currentRow?.venue||s.match?.venue||null,
        referee:s.match?.refereeColor||null,
        strengthBucket:typeof strengthBucket==='function'?strengthBucket(s):null
      }
    };

    s.results=Array.isArray(s.results)?s.results:[];
    s.results.push(entry);

    const marked=markPlayedFromResult(s,gf,ga,score);
    const lesson=learnFromResult(s,entry);
    s.tactic=null;

    let next=null;
    if(marked.row){
      next=advanceAfterResult(s,marked.row);
    }else{
      s.lastCalendarSyncWarning='Resultado salvo, mas o calendário não confirmou qual partida terminou. O adversário não foi alterado.';
    }

    s.lastAnalysisAt=new Date().toISOString();
    return {entry,lesson,next,marked};
  }

  function postResultHtml(s,done){
    const lesson=done.lesson;
    const pending=done.next && isPendingConditional(done.next);
    const nextText=pending
      ? 'Próxima partida: Copa/Taça aguardando definição do adversário.'
      : done.next
        ? `Próxima partida: ${e(done.next.opponent)}${done.next.dateTime?' · '+e(fmtDate(done.next.dateTime)):''}`
        : 'Próxima partida ainda não definida no calendário.';

    return `<div class="card" style="margin-top:12px">
      <span class="eyebrow">RESULTADO · SLOT ${e(s.slotNumber)}</span>
      <h3 style="margin:6px 0">${e(outcomeText(lesson.outcome))} · ${e(lesson.score)}</h3>
      <p class="small muted">${e(s.teamName)} × ${e(lesson.opponent)}</p>
      <div class="reason-box">
        <b>O que a IA registrou no aprendizado</b>
        ${(lesson.notes||[]).map(x=>`<p class="small">${e(x)}</p>`).join('')}
      </div>
      <div class="next-match-note"><b>${nextText}</b></div>
      <div class="actions">
        <button class="btn" onclick="showView('info')">Ver calendário</button>
        <button class="btn ghost" onclick="showView('learning')">Ver aprendizado</button>
      </div>
    </div>`;
  }

  // ------------------------------------------------------------
  // FORÇA: NI nunca equivale a zero
  // ------------------------------------------------------------
  strengthDiff=function(s){
    if(!filled(s?.myTeam?.overall)||!filled(s?.opponent?.overall))return null;
    const a=Number(s.myTeam.overall),b=Number(s.opponent.overall);
    return Number.isFinite(a)&&Number.isFinite(b)?a-b:null;
  };
  strengthBucket=function(s){
    const d=strengthDiff(s);
    if(d===null)return 'NI';
    if(d>=20)return 'muito_mais_forte';
    if(d>=8)return 'mais_forte';
    if(d>-8)return 'equilibrado';
    if(d>-20)return 'mais_fraco';
    return 'muito_mais_fraco';
  };

  // ------------------------------------------------------------
  // RESULTADO automático e manual
  // ------------------------------------------------------------
  v21ApplyResult=function(r){
    const s=selectedSlot();
    const done=finishResult(s,r);
    s._lastResultUi=done;
  };

  window.saveResult=function(slotNo){
    const s=state.slots[slotNo-1];
    const gf=Number(document.getElementById('rGF')?.value);
    const ga=Number(document.getElementById('rGA')?.value);
    if(!Number.isFinite(gf)||!Number.isFinite(ga)){toast('Informe o placar');return}
    const done=finishResult(s,{gf,ga,score:`${gf}-${ga}`});
    saveState();
    closeModal();
    showView('learning');
    toast(`Resultado salvo no Slot ${slotNo}`);
  };

  // ------------------------------------------------------------
  // CALENDÁRIO: leitura não destrói slot correto
  // ------------------------------------------------------------
  const baseCalendarNormalizer=typeof normalizeCalendarOutcomeRow==='function'
    ? normalizeCalendarOutcomeRow
    : x=>({...x});

  v21ApplyCalendar=function(data){
    const s=selectedSlot();
    const incoming=Array.isArray(data?.matches)?data.matches:[];
    s.schedule=incoming.map(x=>({...baseCalendarNormalizer(x),skipped:false}));

    // Reaplica resultados históricos já conhecidos no calendário novo.
    for(const r of (s.results||[])){
      const ropp=n(r.opponent);
      if(!ropp)continue;
      const candidates=s.schedule
        .map((x,i)=>({x,i}))
        .filter(({x})=>n(x.opponent)===ropp);
      const target=candidates.find(({x})=>!x.played) || candidates.at(-1);
      if(target && Number.isFinite(Number(r.gf)) && Number.isFinite(Number(r.ga))){
        target.x.played=true;
        target.x.result=r.score||`${r.gf}-${r.ga}`;
        target.x.outcome=outcome(Number(r.gf),Number(r.ga));
        target.x.conditional=false;
        target.x.placeholder=false;
      }
    }

    // Só atualiza adversário automaticamente em duas situações seguras:
    // 1) existe pendingFixture aguardando rival e a mesma partida agora veio definida;
    // 2) o slot não possui adversário atual.
    if(s.pendingFixture){
      const p=s.pendingFixture;
      const same=s.schedule.find((x,i)=>
        (p.scheduleIndex===i) ||
        (p.dateTime && x.dateTime && p.dateTime===x.dateTime) ||
        (p.round!==null && p.round!==undefined && Number(x.round)===Number(p.round) && x.competitionType===p.competitionType)
      );
      if(same && !isPendingConditional(same) && realOpponent(same.opponent)){
        const idx=s.schedule.indexOf(same);
        setNextFromRow(s,same,idx,{manual:false});
      }
    }else if(!realOpponent(s.opponent?.teamName)){
      // Não adivinha: só preenche se houver uma primeira partida FUTURA claramente definida.
      const future=s.schedule
        .map((x,i)=>({x,i,t:parseEventTime(x)}))
        .filter(({x})=>!x.played&&!x.skipped&&!isPendingConditional(x)&&realOpponent(x.opponent))
        .sort((a,b)=>(a.t??Number.MAX_SAFE_INTEGER)-(b.t??Number.MAX_SAFE_INTEGER));
      if(future[0])setNextFromRow(s,future[0].x,future[0].i,{manual:false});
    }

    s.lastAnalysisAt=new Date().toISOString();
  };

  // Prompt do calendário: conditional=true SOMENTE se adversário ainda não estiver definido.
  const oldPrompt=typeof v21Prompt==='function'?v21Prompt:null;
  if(oldPrompt){
    v21Prompt=function(ocr,mode){
      let p=oldPrompt(ocr,mode);
      if(mode==='calendar'){
        p+=`
REGRA CRÍTICA DE CALENDÁRIO:
- conditional=true SOMENTE quando a partida/copa ainda não tem adversário confirmado.
- Se um adversário real estiver claramente visível, conditional=false.
- Não invente nomes para cards vazios. Se não houver adversário, opponent=null.
- Preserve a ordem/data real mostrada no calendário.`;
      }
      if(mode==='result'){
        p+=`
REGRA CRÍTICA DE RESULTADO:
- O nome do adversário é apenas informativo.
- Se não estiver perfeitamente legível, use null.
- Nunca invente nome de time.`;
      }
      return p;
    };
  }

  // ------------------------------------------------------------
  // EDITAR CALENDÁRIO — botão real em cada linha
  // ------------------------------------------------------------
  window.editCalendarMatch=function(index){
    const s=selectedSlot();
    const x=s.schedule?.[index];
    if(!x){toast('Partida não encontrada');return}

    const dt=x.dateTime
      ? new Date(new Date(x.dateTime).getTime()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,16)
      : '';

    openModal(`<h2>Editar partida · Slot ${s.slotNumber}</h2>
      <div class="field-edit">
        <label>Adversário
          <input id="ecOpp" value="${e(x.opponent||'')}" placeholder="A definir">
        </label>
        <label>Local
          <select id="ecVenue">
            <option value="">NI</option>
            <option>Casa</option>
            <option>Fora</option>
          </select>
        </label>
        <label>Data/hora
          <input id="ecDate" type="datetime-local" value="${e(dt)}">
        </label>
        <label class="check">
          <input id="ecConditional" type="checkbox" ${x.conditional?'checked':''}>
          <span>Aguardando definição / condicional</span>
        </label>
        <div class="actions">
          <button class="btn" onclick="saveCalendarMatch(${index},false)">Salvar linha</button>
          <button class="btn ghost" onclick="saveCalendarMatch(${index},true)">Usar como próximo jogo</button>
        </div>
      </div>`);
    const sel=document.getElementById('ecVenue');
    if(sel)sel.value=x.venue||'';
  };

  window.saveCalendarMatch=function(index,useAsNext){
    const s=selectedSlot();
    const x=s.schedule?.[index];
    if(!x)return;

    const opp=document.getElementById('ecOpp')?.value?.trim()||null;
    const venue=document.getElementById('ecVenue')?.value||null;
    const dt=document.getElementById('ecDate')?.value||'';
    const conditional=!!document.getElementById('ecConditional')?.checked;

    x.opponent=opp;
    x.venue=venue;
    x.dateTime=dt?new Date(dt).toISOString():x.dateTime||null;
    x.conditional=conditional;
    x.placeholder=!opp;

    if(useAsNext){
      if(!realOpponent(opp)){
        toast('Informe um adversário válido para usar como próximo jogo');
        return;
      }
      x.conditional=false;
      x.placeholder=false;
      setNextFromRow(s,x,index,{manual:true});
    }

    localStorage.setItem(STATE_KEY,JSON.stringify(state));
    closeModal();
    renderAll();
    toast(useAsNext?'Próximo jogo atualizado':'Linha do calendário salva');
  };

  calendarTableHtml=function(rows){
    return `<div class="calendar-wrap">
      <table class="simple-table calendar-table clean-calendar">
        <thead>
          <tr><th>Rod.</th><th>Local</th><th>Adversário</th><th>Data/hora</th><th>Status</th><th>Ação</th></tr>
        </thead>
        <tbody>${(rows||[]).map((x,i)=>`
          <tr>
            <td>${e(x.round)}</td>
            <td>${e(x.venue)}</td>
            <td>${e(realOpponent(x.opponent)?x.opponent:'A definir')}</td>
            <td>${e(x.dateTime?fmtDate(x.dateTime):`${x.dateText||'NI'} ${x.timeText||''}`)}</td>
            <td>${x.played
              ? `<span class="result-badge ${String(x.outcome||'').toUpperCase()==='V'?'win':String(x.outcome||'').toUpperCase()==='E'?'draw':'loss'}">${e(calendarOutcomeLabel(x))}</span>${x.result?` · ${e(x.result)}`:''}`
              : x.skipped?'Ignorado':x.conditional?'Condicional':'Futuro'
            }</td>
            <td><button class="btn ghost tiny calendar-edit-btn" onclick="editCalendarMatch(${i})">Editar</button></td>
          </tr>`).join('')}</tbody>
      </table>
    </div>`;
  };

  // ------------------------------------------------------------
  // ELENCO — não perde linhas durante rolagem
  // ------------------------------------------------------------
  const originalExtract=v21ExtractVideoFrames;
  v21ExtractVideoFrames=async function(file,maxFrames=20){
    if(analysisMode!=='market')return originalExtract(file,maxFrames);

    const url=URL.createObjectURL(file),v=document.createElement('video');
    v.src=url;v.muted=true;v.playsInline=true;v.preload='metadata';
    await new Promise((res,rej)=>{
      v.onloadedmetadata=res;
      v.onerror=()=>rej(new Error(`Não consegui abrir ${file.name}`));
      try{v.load()}catch{}
    });

    const dur=Math.max(.2,v.duration||1);
    const total=32;
    const frames=[];
    for(let i=0;i<total;i++){
      const t=Math.min(dur-.08,Math.max(.08,dur*(i+.5)/total));
      await v21SeekVideo(v,t);
      const f=v21CaptureVideoFrame(v,t,file.name);
      f.score=100;
      frames.push(f);
    }
    URL.revokeObjectURL(url);
    return frames;
  };

  function marketEvidence(frames,n=18){
    const sorted=[...(frames||[])].sort((a,b)=>(a.time||0)-(b.time||0));
    if(sorted.length<=n)return sorted;
    const out=[];
    for(let i=0;i<n;i++){
      const idx=Math.round(i*(sorted.length-1)/Math.max(1,n-1));
      if(sorted[idx]&&!out.includes(sorted[idx]))out.push(sorted[idx]);
    }
    return out;
  }

  const originalAnalyze=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='market')return originalAnalyze(files);

    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
    state.selectedSlot=slotNo;
    const video=files.find(f=>String(f.type||'').startsWith('video/'));
    const images=files.filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];

    setProgress(5,'Capturando toda a rolagem do elenco…');
    if(video)frames=await v21ExtractVideoFrames(video,32);
    else if(images.length){
      for(const f of images)frames.push(await v21ImageToFrame(f));
    }else throw new Error('Selecione um vídeo ou imagens do OSM.');

    v21RenderEvidence(frames);
    setProgress(18,`OCR em ${frames.length} quadro(s)…`);
    const ocr=await v21RunLocalOcr(frames);
    const evidence=marketEvidence(frames,18);

    setProgress(58,'Consolidando jogadores…');
    const result=await v21AnalyzePackage(ocr,evidence,'market');
    v21ApplyRoster(result);
    saveState();
    renderMarket();

    const s=selectedSlot();
    const count=(s.roster||[]).length;
    const expected=Number(s.myTeam?.playerCount);
    const warning=Number.isFinite(expected)&&expected>0&&count<expected;

    if(document.getElementById('analysisContent')){
      document.getElementById('analysisContent').innerHTML=
        `<div class="card" style="margin-top:12px"><h3>${warning?'Leitura incompleta':'Elenco atualizado'}</h3>
        <p class="small muted">${warning?`${count} de ${expected} jogadores reconhecidos.`:`${count} jogadores reconhecidos.`}</p></div>`;
    }

    setAnalysisRun(s,'market',warning?'warning':'success',
      warning?`${count} de ${expected} jogadores reconhecidos.`:`${count} jogadores reconhecidos.`,
      {rosterCount:count,expected:Number.isFinite(expected)?expected:null});
    setProgress(100,warning?'Revisar elenco':'Elenco atualizado');
    job(warning?'Elenco incompleto; revise antes de usar o plano.':'Elenco atualizado.','done');
  };
  window.v21Analyze=v21Analyze;

  // ------------------------------------------------------------
  // SLOTS / HOJE — simples, sem faixa extra
  // ------------------------------------------------------------
  renderSlotSwitcher=function(){
    const el=document.getElementById('slotSwitcher');
    if(!el)return;
    el.innerHTML=state.slots.map(s=>`
      <button class="slot-chip clean-slot-chip ${s.slotNumber===state.selectedSlot?'active':''}" onclick="selectSlot(${s.slotNumber})">
        <b>S${s.slotNumber}</b><span>${e(s.teamName||'Livre')}</span>
      </button>`).join('');
  };

  nextAction=function(){
    const s=selectedSlot();
    if(!s||s.status!=='active'){
      return {
        priority:`SLOT ${state.selectedSlot}`,
        title:'Slot livre',
        detail:'Configure este slot ou envie uma análise.',
        buttons:`<button class="btn" onclick="competitionModal(${state.selectedSlot})">Criar competição</button>`
      };
    }

    const pending=s.pendingFixture?.status==='awaiting_opponent';
    if(pending){
      return {
        priority:`SLOT ${s.slotNumber} · PRÓXIMA PARTIDA`,
        title:`${s.teamName||'Meu time'} · adversário a definir`,
        detail:'Há uma partida condicional/Copa antes do próximo jogo confirmado. Releia o calendário quando o adversário aparecer ou edite manualmente.',
        buttons:`<button class="btn" onclick="showView('info')">Abrir calendário</button><button class="btn ghost" onclick="showView('analyze');setAnalysisMode('calendar')">Reler calendário</button>`
      };
    }

    const miss=missingRequired(s);
    return {
      priority:`SLOT ${s.slotNumber} · ${s.competitionType||'COMPETIÇÃO'}`,
      title:`${s.teamName||'Meu time'} × ${s.opponent?.teamName||'Adversário NI'}`,
      detail:`${s.match?.venue||'Local NI'} · ${s.match?.nextMatchAt?fmtDate(s.match.nextMatchAt):'Horário NI'}${miss.length?` · ${miss.length} campo(s) pendente(s)`:''}`,
      buttons:`<button class="btn" onclick="showView('pregame')">${s.tactic?'Ver plano':'Preparar'}</button><button class="btn ghost" onclick="showView('analyze')">Analisar</button>`
    };
  };

  renderDashboard=function(){
    const s=selectedSlot(),action=nextAction();
    const hero=document.getElementById('heroAction');
    const grid=document.getElementById('slotsGrid');
    if(hero)hero.innerHTML=`<div class="priority">${e(action.priority)}</div><h2>${e(action.title)}</h2><p class="muted">${e(action.detail)}</p><div class="actions">${action.buttons}</div>`;
    if(grid){
      grid.innerHTML=s?slotCard(s):'';
      grid.classList.add('single-slot-grid');
    }
    renderRadar();
  };

  // ------------------------------------------------------------
  // APRENDIZADO por slot
  // ------------------------------------------------------------
  renderLearning=function(){
    const s=selectedSlot();
    const rows=Array.isArray(s?.results)?s.results:[];
    const w=rows.filter(r=>Number(r.gf)>Number(r.ga)).length;
    const d=rows.filter(r=>Number(r.gf)===Number(r.ga)).length;
    const l=rows.filter(r=>Number(r.gf)<Number(r.ga)).length;
    const last=s?.lastLearning||null;

    const byForm={};
    for(const r of rows){
      const f=r?.tactic?.formation||r?.context?.myFormation||'NI';
      byForm[f]??={j:0,w:0,d:0,l:0};
      byForm[f].j++;
      if(Number(r.gf)>Number(r.ga))byForm[f].w++;
      else if(Number(r.gf)===Number(r.ga))byForm[f].d++;
      else byForm[f].l++;
    }

    const section=(title,items,cls='')=>items?.length
      ? `<div class="learning-section ${cls}"><b>${e(title)}</b>${items.map(x=>`<div class="learning-row">${e(x)}</div>`).join('')}</div>`
      : '';

    const target=document.getElementById('learningContent');
    if(!target)return;

    target.innerHTML=`
      <div class="card">
        <div class="section-head compact-head">
          <div><span class="eyebrow">SLOT ${e(s.slotNumber)}</span><h3>${e(s.teamName||'Sem time')}</h3></div>
        </div>
        <div class="kpis">
          <div class="kpi"><span>Jogos aprendidos</span><b>${rows.length}</b></div>
          <div class="kpi"><span>Vitórias</span><b>${w}</b></div>
          <div class="kpi"><span>Empates</span><b>${d}</b></div>
          <div class="kpi"><span>Derrotas</span><b>${l}</b></div>
        </div>
        <p class="small muted">A IA usa contexto + estatísticas + padrões, não apenas o placar.</p>
      </div>

      ${last?`<div class="card learning-detail-card" style="margin-top:12px">
        <div class="learning-detail-head">
          <div>
            <span class="eyebrow">ÚLTIMO JOGO APRENDIDO</span>
            <h3>${e(outcomeText(last.outcome))} · ${e(last.score)} contra ${e(last.opponent||'Adversário')}</h3>
          </div>
          <span class="result-badge ${last.outcome==='V'?'win':last.outcome==='E'?'draw':'loss'}">${e(last.outcome)}</span>
        </div>
        ${section('Contexto usado pela IA',last.facts)}
        ${section('Estatísticas consideradas',last.statFacts)}
        ${section('Padrões detectados',last.patterns,'learning-patterns')}
        ${section('O que ganhou peso',last.increaseWeight,'learning-positive')}
        ${section('O que perdeu peso',last.decreaseWeight,'learning-negative')}
        ${section('O que evitar',last.avoid,'learning-avoid')}
        ${section('Como isso será usado na próxima tática',last.nextUse,'learning-next')}
      </div>`:''}

      ${Object.keys(byForm).length?`<div class="card" style="margin-top:12px">
        <h3>Histórico por formação</h3>
        <table class="simple-table">
          <tr><th>Formação</th><th>J</th><th>V</th><th>E</th><th>D</th></tr>
          ${Object.entries(byForm).map(([f,x])=>`<tr><td>${e(f)}</td><td>${x.j}</td><td>${x.w}</td><td>${x.d}</td><td>${x.l}</td></tr>`).join('')}
        </table>
      </div>`:''}`;
  };

  // Depois de uma análise automática de resultado, mostra o aprendizado na própria análise.
  const originalSaveState=saveState;
  // Não substitui saveState; apenas o fluxo de análise usará _lastResultUi abaixo.

  // Corrige a conclusão do modo resultado para exibir o card.
  const originalRunPending=runPendingAnalysis;
  // O original continua; o conteúdo será reconstruído depois pelo Mutation/timeout abaixo.
  document.addEventListener('click',function(ev){
    const btn=ev.target.closest?.('#analyzeNowBtn');
    if(!btn || analysisMode!=='result')return;
    setTimeout(()=>{
      const s=selectedSlot();
      if(!s?._lastResultUi)return;
      const ac=document.getElementById('analysisContent');
      if(ac)ac.innerHTML=postResultHtml(s,s._lastResultUi);
      s._lastResultUi=null;
      try{localStorage.setItem(STATE_KEY,JSON.stringify(state))}catch{}
    },1800);
  });



  // ------------------------------------------------------------
  // 2.5.1 — RESULTADO MANUAL SEM DEPENDER DE TÁTICA ATIVA
  // ------------------------------------------------------------
  window.resultModal=function(slotNo){
    const s=state.slots[slotNo-1];
    if(!s || s.status!=='active'){toast('Configure o slot primeiro');return}
    openModal(`<h2>Registrar resultado · Slot ${slotNo}</h2>
      <div class="field-edit">
        <label>Adversário
          <input id="rOpp" value="${e(s.opponent?.teamName||'')}" readonly>
        </label>
        <div class="kpis">
          <label>Meus gols<input id="rGF" type="number" min="0"></label>
          <label>Gols rival<input id="rGA" type="number" min="0"></label>
        </div>
        <label>Observação<textarea id="rNote"></textarea></label>
        <button class="btn" onclick="saveResult(${slotNo})">Salvar resultado</button>
      </div>`);
  };

  window.saveResult=function(slotNo){
    const s=state.slots[slotNo-1];
    const gf=Number(document.getElementById('rGF')?.value);
    const ga=Number(document.getElementById('rGA')?.value);
    if(!Number.isFinite(gf)||!Number.isFinite(ga)){toast('Informe o placar');return}
    const note=document.getElementById('rNote')?.value?.trim()||null;
    const done=finishResult(s,{gf,ga,score:`${gf}-${ga}`});
    if(done.entry)done.entry.note=note;
    saveState();
    clearGeminiJobBanner();
    closeModal();
    showView('learning');
    renderLearning();
    toast(`Resultado ${gf}-${ga} registrado e aprendizado atualizado no Slot ${slotNo}`);
  };

  // ------------------------------------------------------------
  // 2.5.1 — CORES DO CALENDÁRIO ROBUSTAS
  // ------------------------------------------------------------
  function calendarOutcomeCode(x){
    const raw=n(x?.outcome);
    if(['v','vitoria','vitória','win','victory'].includes(raw))return 'V';
    if(['e','empate','draw'].includes(raw))return 'E';
    if(['d','derrota','loss','defeat'].includes(raw))return 'D';

    const result=String(x?.result||'').trim();
    const m=result.match(/(\d+)\s*[-x:]\s*(\d+)/i);
    if(m){
      const a=Number(m[1]),b=Number(m[2]);
      if(a>b)return 'V';
      if(a===b)return 'E';
      return 'D';
    }
    return null;
  }
  function calendarOutcomeClass(x){
    const c=calendarOutcomeCode(x);
    return c==='V'?'win':c==='E'?'draw':c==='D'?'loss':'';
  }
  function calendarOutcomeTextSafe(x){
    const c=calendarOutcomeCode(x);
    return c==='V'?'Vitória':c==='E'?'Empate':c==='D'?'Derrota':(x?.played?'Jogado':'Futuro');
  }

  // Substitui a tabela novamente, desta vez normalizando outcome antes da cor.
  calendarTableHtml=function(rows){
    return `<div class="calendar-wrap">
      <table class="simple-table calendar-table clean-calendar">
        <thead>
          <tr><th>Rod.</th><th>Local</th><th>Adversário</th><th>Data/hora</th><th>Status</th><th>Ação</th></tr>
        </thead>
        <tbody>${(rows||[]).map((x,i)=>`
          <tr>
            <td>${e(x.round)}</td>
            <td>${e(x.venue)}</td>
            <td>${e(realOpponent(x.opponent)?x.opponent:'A definir')}</td>
            <td>${e(x.dateTime?fmtDate(x.dateTime):`${x.dateText||'NI'} ${x.timeText||''}`)}</td>
            <td>${x.played
              ? `<span class="result-badge ${calendarOutcomeClass(x)}">${e(calendarOutcomeTextSafe(x))}</span>${x.result?` · ${e(x.result)}`:''}`
              : x.skipped?'Ignorado':x.conditional?'Condicional':'Futuro'
            }</td>
            <td><button class="btn ghost tiny calendar-edit-btn" onclick="editCalendarMatch(${i})">Editar</button></td>
          </tr>`).join('')}</tbody>
      </table>
    </div>`;
  };

  // ------------------------------------------------------------
  // 2.5.1 — HOJE: botão Registrar resultado sempre disponível
  // ------------------------------------------------------------
  const _cleanNextAction251=nextAction;
  nextAction=function(){
    const s=selectedSlot();
    const a=_cleanNextAction251();
    if(!s || s.status!=='active')return a;
    a.buttons += `<button class="btn ghost result-now-btn" onclick="resultModal(${s.slotNumber})">Registrar resultado</button>`;
    return a;
  };

  // ------------------------------------------------------------
  // 2.5.1 — RADAR: urgência depende do tempo até o jogo
  // ------------------------------------------------------------
  function hoursUntilMatch(s){
    const raw=s?.match?.nextMatchAt;
    if(!raw)return null;
    const t=new Date(raw).getTime()-Date.now();
    return Number.isFinite(t)?t/3600000:null;
  }

  renderRadar=function(){
    const s=selectedSlot();
    const el=document.getElementById('radarPanel');
    if(!el)return;
    if(!s || s.status!=='active'){
      el.innerHTML='';
      return;
    }

    const rows=[];
    const hrs=hoursUntilMatch(s);
    const miss=missingRequired(s);

    // Mais de 48h: não chama de urgente.
    if(miss.length){
      if(hrs!==null && hrs<=18){
        rows.push([`Completar preparação do Slot ${s.slotNumber}`,`${miss.length} campo(s) essencial(is) · jogo em ${Math.max(0,Math.round(hrs))}h`,'danger','Urgente']);
      }else if(hrs!==null && hrs<=48){
        rows.push([`Preparar Slot ${s.slotNumber}`,`${miss.length} campo(s) pendente(s) · ainda há ${Math.max(1,Math.round(hrs))}h`,'warn','Atenção']);
      }else{
        rows.push([`Preparação futura do Slot ${s.slotNumber}`,`${miss.length} campo(s) ainda podem ser completados antes do jogo${hrs!==null?` · faltam cerca de ${Math.round(hrs/24)} dia(s)`:''}`,'','Planejar']);
      }
    }else if(!s.tactic && hrs!==null && hrs<=48){
      rows.push([`Gerar tática do Slot ${s.slotNumber}`,'Dados essenciais disponíveis.','warn','Atenção']);
    }

    if(s.analysisRuns?.market?.status==='warning'){
      rows.push(['Revisar elenco',s.analysisRuns.market.message,'warn','Atenção']);
    }

    el.innerHTML=`<div class="section-head"><div><span class="eyebrow">RADAR DO SLOT ${s.slotNumber}</span><h2>Próximas ações</h2></div></div>
      <div class="card radar-list">${rows.length
        ? rows.map(r=>`<div class="radar-item"><div><b>${e(r[0])}</b><span>${e(r[1])}</span></div><span class="status ${r[2]}">${e(r[3])}</span></div>`).join('')
        : '<p class="muted">Nada urgente neste slot agora.</p>'
      }</div>`;
  };

  // ------------------------------------------------------------
  // 2.5.1 — DIRETOR: registrar compra e venda
  // ------------------------------------------------------------
  const _cleanRenderMarket251=renderMarket;
  renderMarket=function(){
    _cleanRenderMarket251();
    const s=selectedSlot();
    if(!s || s.status!=='active')return;
    const target=document.getElementById('marketContent');
    if(!target)return;

    const tx=Array.isArray(s.marketTransactions)?s.marketTransactions:[];
    target.insertAdjacentHTML('afterbegin',`
      <div class="card market-moves-card">
        <div class="section-head compact-head">
          <div><span class="eyebrow">MOVIMENTAÇÕES · SLOT ${e(s.slotNumber)}</span><h3>Atualizar elenco manualmente</h3></div>
        </div>
        <p class="small muted">Registre uma compra ou venda assim que acontecer. O Diretor recalcula o plano usando o elenco atualizado.</p>
        <div class="actions">
          <button class="btn" onclick="addBoughtPlayerModal()">+ Jogador comprado</button>
          <button class="btn ghost" onclick="sellPlayerModal()">− Jogador vendido</button>
        </div>
        ${tx.length?`<div class="market-transactions">${tx.slice(0,6).map(t=>`
          <div><b>${t.type==='buy'?'Compra':'Venda'} · ${e(t.name)}</b><span>${e(t.position||'')} ${filled(t.rating)?`· força ${e(t.rating)}`:''}</span></div>`).join('')}</div>`:''}
      </div>`);
  };

  window.addBoughtPlayerModal=function(){
    const s=selectedSlot();
    openModal(`<h2>Jogador comprado · Slot ${s.slotNumber}</h2>
      <div class="field-edit">
        <label>Nome<input id="buyName" placeholder="Nome do jogador"></label>
        <label>Posição
          <select id="buyPos">
            <option value="ATA">ATA</option>
            <option value="MEI">MEI</option>
            <option value="DEF">DEF</option>
            <option value="GOL">GOL</option>
          </select>
        </label>
        <div class="kpis">
          <label>Força<input id="buyRating" type="number" min="1" max="200"></label>
          <label>Idade<input id="buyAge" type="number" min="15" max="50"></label>
        </div>
        <label>Valor/preço (opcional)<input id="buyValue" placeholder="Ex.: 12.5M"></label>
        <button class="btn" onclick="saveBoughtPlayer()">Adicionar ao elenco e recalcular</button>
      </div>`);
  };

  window.saveBoughtPlayer=function(){
    const s=selectedSlot();
    const name=document.getElementById('buyName')?.value?.trim();
    const position=document.getElementById('buyPos')?.value;
    const rating=Number(document.getElementById('buyRating')?.value);
    const age=Number(document.getElementById('buyAge')?.value);
    const value=document.getElementById('buyValue')?.value?.trim()||null;
    if(!name){toast('Informe o nome do jogador');return}
    if(!Number.isFinite(rating)){toast('Informe a força do jogador');return}

    s.roster=Array.isArray(s.roster)?s.roster:[];
    s.roster.push({
      name,position,rating,
      age:Number.isFinite(age)?age:null,
      value,
      training:false,forSale:false,
      manualTransaction:true
    });
    s.myTeam=s.myTeam||{};
    s.myTeam.playerCount=s.roster.length;
    s.marketTransactions=Array.isArray(s.marketTransactions)?s.marketTransactions:[];
    s.marketTransactions.unshift({type:'buy',name,position,rating,at:new Date().toISOString()});
    buildMarketPlan(s);
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
    closeModal();renderMarket();renderDashboard();
    toast(`${name} adicionado ao elenco`);
  };

  window.sellPlayerModal=function(){
    const s=selectedSlot();
    const roster=Array.isArray(s.roster)?s.roster:[];
    if(!roster.length){toast('Nenhum jogador no elenco');return}
    openModal(`<h2>Jogador vendido · Slot ${s.slotNumber}</h2>
      <div class="field-edit">
        <label>Jogador
          <select id="sellPlayerIndex">
            ${roster.map((p,i)=>`<option value="${i}">${e(playerNameValue(p)||'Sem nome')} · ${e(normalizePos(playerPosValue(p))||playerPosValue(p)||'NI')} · ${e(playerRatingValue(p))}</option>`).join('')}
          </select>
        </label>
        <button class="btn danger" onclick="saveSoldPlayer()">Remover do elenco e recalcular</button>
      </div>`);
  };

  window.saveSoldPlayer=function(){
    const s=selectedSlot();
    const idx=Number(document.getElementById('sellPlayerIndex')?.value);
    if(!Number.isInteger(idx)||idx<0||idx>=s.roster.length){toast('Selecione um jogador');return}
    const [p]=s.roster.splice(idx,1);
    const name=playerNameValue(p)||'Jogador';
    const position=normalizePos(playerPosValue(p))||playerPosValue(p)||null;
    const rating=playerRatingValue(p);
    s.myTeam=s.myTeam||{};
    s.myTeam.playerCount=s.roster.length;
    s.marketTransactions=Array.isArray(s.marketTransactions)?s.marketTransactions:[];
    s.marketTransactions.unshift({type:'sell',name,position,rating,at:new Date().toISOString()});
    buildMarketPlan(s);
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
    closeModal();renderMarket();renderDashboard();
    toast(`${name} removido do elenco`);
  };

  // ------------------------------------------------------------
  // 2.5.1 — CALENDÁRIO: mais imagens e datas somente quando legíveis
  // ------------------------------------------------------------
  const _cleanPrompt251=v21Prompt;
  v21Prompt=function(ocr,mode){
    let p=_cleanPrompt251(ocr,mode);
    if(mode==='calendar'){
      p+=`
VALIDAÇÃO FORTE DE DATAS:
- A imagem é a fonte principal para DIA/MÊS; OCR é somente apoio.
- Leia cada data visualmente, dígito por dígito.
- Não suponha sequência de dias.
- Não transforme 28 em 27, 26 em 25 etc. por padrão de sequência.
- Se o primeiro dígito da data estiver ambíguo, use dateText=null/dateTime=null em vez de adivinhar.
- Preserve exatamente a primeira data real visível no vídeo.
- Revise especialmente a PRIMEIRA partida futura antes de responder.`;
    }
    return p;
  };

  const _cleanAnalyze251=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='calendar')return _cleanAnalyze251(files);

    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
    state.selectedSlot=slotNo;
    const video=files.find(f=>String(f.type||'').startsWith('video/'));
    const images=files.filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];

    setProgress(5,'Capturando calendário com mais quadros…');
    if(video){
      // Mais cobertura para datas pequenas no calendário.
      frames=await v21ExtractVideoFrames(video,28);
    }else if(images.length){
      for(const f of images)frames.push(await v21ImageToFrame(f));
    }else throw new Error('Selecione um vídeo ou imagens do calendário.');

    v21RenderEvidence(frames);
    setProgress(18,'Lendo datas e adversários…');
    const ocr=await v21RunLocalOcr(frames);

    // 10 evidências distribuídas cronologicamente, não só as mais diferentes.
    const sorted=[...frames].sort((a,b)=>(a.time||0)-(b.time||0));
    const evidence=[];
    const wanted=Math.min(10,sorted.length);
    for(let i=0;i<wanted;i++){
      const idx=Math.round(i*(sorted.length-1)/Math.max(1,wanted-1));
      if(sorted[idx]&&!evidence.includes(sorted[idx]))evidence.push(sorted[idx]);
    }

    setProgress(58,'Validando calendário…');
    const result=await v21AnalyzePackage(ocr,evidence,'calendar');
    v21ApplyCalendar(result);
    saveState();
    renderInfo();

    const cr=(selectedSlot().schedule||[]).length;
    document.getElementById('analysisContent').innerHTML=
      `<div class="card" style="margin-top:12px"><h3>Calendário atualizado</h3><p class="small muted">${cr} partida(s) reconhecida(s). Confira a primeira data futura; se alguma data ficar NI, use Editar em vez de aceitar uma data inventada.</p></div>`;
    setAnalysisRun(selectedSlot(),'calendar',cr?'success':'warning',`${cr} partida(s) reconhecida(s).`,{count:cr});
    setProgress(100,'Calendário atualizado');
    job('Calendário atualizado.','done');
  };
  window.v21Analyze=v21Analyze;



  // ------------------------------------------------------------
  // 2.5.2 — RESULTADO CENTRALIZADO EM HOJE
  // ------------------------------------------------------------
  let pendingResultVideoFile=null;

  window.resultModal=function(slotNo){
    const s=state.slots[slotNo-1];
    if(!s || s.status!=='active'){toast('Configure o slot primeiro');return}

    pendingResultVideoFile=null;

    openModal(`<h2>Registrar resultado · Slot ${slotNo}</h2>
      <p class="small muted">${e(s.teamName||'Meu time')} × ${e(s.opponent?.teamName||'Adversário NI')}</p>

      <div class="result-register-tabs">
        <div class="result-register-block">
          <span class="eyebrow">OPÇÃO 1 · AUTOMÁTICA</span>
          <h3>Enviar vídeo do resultado</h3>
          <p class="small muted">Mostre o placar final e, se possível, estatísticas e formações. Ao escolher a mídia, a análise começa automaticamente e o resultado é registrado no slot.</p>
          <input id="resultVideoInput" type="file" accept="video/*,image/*" hidden>
          <div class="actions">
            <button class="btn" onclick="chooseResultVideo(${slotNo})">Escolher vídeo/imagem</button>
            <button id="analyzeResultVideoBtn" class="btn ghost" onclick="analyzeResultVideo(${slotNo})" disabled>Analisar e registrar</button>
          </div>
          <div id="resultVideoInfo" class="small muted">Nenhuma mídia selecionada.</div>
          <div id="resultVideoProgress" class="small muted"></div>
        </div>

        <div class="result-register-divider"><span>ou</span></div>

        <div class="result-register-block manual-result-block">
          <div class="manual-result-head">
            <div>
              <span class="eyebrow">OPÇÃO 2 · MANUAL</span>
              <h3>Informar placar</h3>
            </div>
            <span class="manual-result-team">${e(s.teamName||'Meu time')} × ${e(s.opponent?.teamName||'Adversário')}</span>
          </div>

          <div class="score-input-grid">
            <label class="score-input-card">
              <span>Meus gols</span>
              <input id="rGF" type="number" min="0" inputmode="numeric" placeholder="0">
            </label>
            <div class="score-x">×</div>
            <label class="score-input-card">
              <span>Gols rival</span>
              <input id="rGA" type="number" min="0" inputmode="numeric" placeholder="0">
            </label>
          </div>

          <label class="manual-note-field">
            <span>Observação <small>(opcional)</small></span>
            <textarea id="rNote" rows="3" placeholder="Ex.: bom desempenho, rival mudou formação..."></textarea>
          </label>

          <button class="btn manual-save-result" onclick="saveResult(${slotNo})">
            Salvar resultado manualmente
          </button>
        </div>
      </div>`);
  };

  window.chooseResultVideo=function(slotNo){
    const input=document.getElementById('resultVideoInput');
    if(!input)return;
    input.value='';
    input.onchange=()=>{
      const f=input.files?.[0]||null;
      pendingResultVideoFile=f;
      const info=document.getElementById('resultVideoInfo');
      const btn=document.getElementById('analyzeResultVideoBtn');
      const prog=document.getElementById('resultVideoProgress');

      if(f){
        if(info)info.textContent=`${f.name} · ${(f.size/1024/1024).toFixed(1)} MB`;
        if(btn){
          btn.disabled=false;
          btn.textContent='Analisar e registrar';
        }
        if(prog)prog.textContent='Mídia selecionada. Iniciando análise automaticamente…';

        // No registro de resultado, selecionar a mídia já deve iniciar a análise.
        // Pequeno atraso para o Android concluir o retorno do seletor antes de processar o arquivo.
        setTimeout(()=>{
          if(pendingResultVideoFile===f){
            analyzeResultVideo(slotNo);
          }
        },250);
      }else{
        if(info)info.textContent='Nenhuma mídia selecionada.';
        if(btn)btn.disabled=true;
        if(prog)prog.textContent='';
      }
    };
    try{
      if(typeof input.showPicker==='function')input.showPicker();
      else input.click();
    }catch{
      input.click();
    }
  };

  let resultMediaAnalysisBusy=false;

  window.analyzeResultVideo=async function(slotNo){
    if(resultMediaAnalysisBusy)return;
    const s=state.slots[slotNo-1];
    const file=pendingResultVideoFile;
    if(!file){toast('Escolha um vídeo ou imagem do resultado');return}
    if(!localStorage.getItem(API_KEY_STORAGE)){apiModal('Configure a API Gemini antes de analisar o resultado.');return}

    resultMediaAnalysisBusy=true;
    const btn=document.getElementById('analyzeResultVideoBtn');
    const prog=document.getElementById('resultVideoProgress');
    if(btn){btn.disabled=true;btn.textContent='Analisando…'}
    if(prog)prog.textContent='Capturando o resultado…';

    try{
      let frames=[];
      if(String(file.type||'').startsWith('video/')){
        frames=await v21ExtractVideoFrames(file,18);
      }else if(String(file.type||'').startsWith('image/')){
        frames=[await v21ImageToFrame(file)];
      }else{
        throw new Error('Formato de mídia não suportado.');
      }

      if(prog)prog.textContent='Lendo placar e estatísticas…';
      const ocr=await v21RunLocalOcr(frames);

      // Resultado precisa de cobertura do início ao fim, sem depender apenas dos quadros "mais diferentes".
      const sorted=[...frames].sort((a,b)=>(a.time||0)-(b.time||0));
      const evidence=[];
      const wanted=Math.min(8,sorted.length);
      for(let i=0;i<wanted;i++){
        const idx=Math.round(i*(sorted.length-1)/Math.max(1,wanted-1));
        if(sorted[idx]&&!evidence.includes(sorted[idx]))evidence.push(sorted[idx]);
      }

      if(prog)prog.textContent='Interpretando resultado…';
      const data=await v21AnalyzePackage(ocr,evidence,'result');

      const gf=Number(data?.gf),ga=Number(data?.ga);
      if(!Number.isFinite(gf)||!Number.isFinite(ga)){
        throw new Error('Não consegui confirmar o placar no vídeo. Tente outro vídeo ou informe manualmente.');
      }

      const done=finishResult(s,{
        ...data,
        gf,ga,
        score:data?.score||`${gf}-${ga}`
      });

      localStorage.setItem(STATE_KEY,JSON.stringify(state));
      resultMediaAnalysisBusy=false;
      clearGeminiJobBanner();
      closeModal();
      renderAll();
      showView('learning');
      renderLearning();
      toast(`Resultado ${gf}-${ga} registrado e aprendizado atualizado no Slot ${slotNo}`);
    }catch(err){
      resultMediaAnalysisBusy=false;
      clearGeminiJobBanner();
      if(prog)prog.textContent=`Falha: ${err?.message||String(err)}`;
      toast(err?.message||'Falha ao analisar resultado');
      if(btn){btn.disabled=false;btn.textContent='Tentar analisar novamente'}
    }
  };

  // ------------------------------------------------------------
  // 2.5.2 — TÁTICA OFENSIVA PADRÃO PARA VANTAGEM MUITO GRANDE
  // ------------------------------------------------------------
  function offensiveDefaultTactic(){
    return {
      formation:'4-3-3 A',
      gamePlan:'Jogar pelas alas',
      pressure:78,
      mentality:82,
      tempo:84,
      marking:'À zona',
      offside:'Sim',
      tackling:'Normal',
      attackInstruction:'Atacar apenas',
      midfieldInstruction:'Pressionar na frente',
      defenceInstruction:'Apoiar o meio-campo',
      reason:'Tática ofensiva padrão para cenário em que o seu time é muito superior em força.',
      confidenceScore:.72,
      generatedAt:new Date().toISOString(),
      engine:'Padrão ofensivo 2.5.2'
    };
  }

  function shouldShowOffensiveDefault(s){
    const d=strengthDiff(s);
    return d!==null && d>=13;
  }

  window.toggleOffensiveDefault=function(slotNo){
    const box=document.getElementById(`offensiveDefaultBox-${slotNo}`);
    if(!box)return;
    box.classList.toggle('hidden');
  };

  window.applyOffensiveDefault=function(slotNo){
    const s=state.slots[slotNo-1];
    if(!s || !shouldShowOffensiveDefault(s)){
      toast('A tática ofensiva padrão só fica disponível quando a vantagem de força é de pelo menos 13.');
      return;
    }
    s.tactic=offensiveDefaultTactic();
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
    renderAll();
    showView('pregame');
    toast('Tática ofensiva padrão aplicada');
  };

  function offensiveDefaultHtml(s){
    if(!s || !shouldShowOffensiveDefault(s))return '';
    const t=offensiveDefaultTactic();
    return `<div class="offensive-default-wrap">
      <button class="btn offensive-default-toggle" onclick="toggleOffensiveDefault(${s.slotNumber})">
        ⚡ Gerar tática forte 4-3-3
      </button>
      <div id="offensiveDefaultBox-${s.slotNumber}" class="card offensive-default-box hidden">
        <div class="section-head compact-head">
          <div>
            <span class="eyebrow">VANTAGEM DE FORÇA ${e('+'+strengthDiff(s))}</span>
            <h3>Tática forte 4-3-3</h3>
          </div>
        </div>
        <table class="tactic-table">
          <tr><td>Formação</td><td>${e(t.formation)}</td></tr>
          <tr><td>Estilo de jogo</td><td>${e(t.gamePlan)}</td></tr>
          <tr><td>Pressão</td><td>${t.pressure}</td></tr>
          <tr><td>Estilo / Mentalidade</td><td>${t.mentality}</td></tr>
          <tr><td>Temporização / Ritmo</td><td>${t.tempo}</td></tr>
          <tr><td>Marcação</td><td>${e(t.marking)}</td></tr>
          <tr><td>Impedimento</td><td>${e(t.offside)}</td></tr>
          <tr><td>Desarme</td><td>${e(t.tackling)}</td></tr>
          <tr><td>Ataque</td><td>${e(t.attackInstruction)}</td></tr>
          <tr><td>Meio</td><td>${e(t.midfieldInstruction)}</td></tr>
          <tr><td>Defesa</td><td>${e(t.defenceInstruction)}</td></tr>
        </table>
        <p class="small muted">Disponível porque sua vantagem de força é de pelo menos 13 pontos. Você pode aplicar esta 4-3-3 ofensiva diretamente.</p>
        <button class="btn" onclick="applyOffensiveDefault(${s.slotNumber})">Aplicar esta tática</button>
      </div>
    </div>`;
  }

  // Injeta o botão no Pré-jogo, imediatamente após a renderização normal.
  const _renderPregame252=renderPregame;
  renderPregame=function(){
    _renderPregame252();
    const s=selectedSlot();
    const target=document.getElementById('pregameContent');
    if(!target || !s || s.status!=='active')return;
    const html=offensiveDefaultHtml(s);
    if(html)target.insertAdjacentHTML('beforeend',html);
  };

  // Injeta também na tela Analisar depois de concluir a leitura da partida.
  const _runPending252=runPendingAnalysis;
  runPendingAnalysis=async function(){
    await _runPending252();
    if(analysisMode!=='tactic')return;
    const s=selectedSlot();
    const target=document.getElementById('analysisContent');
    if(!target || !s)return;
    const html=offensiveDefaultHtml(s);
    if(html && !document.getElementById(`offensiveDefaultBox-${s.slotNumber}`)){
      target.insertAdjacentHTML('beforeend',html);
    }
  };
  window.runPendingAnalysis=runPendingAnalysis;


  // ------------------------------------------------------------
  // 2.5.6 — APRENDIZADO É ENVIADO PARA AS PRÓXIMAS TÁTICAS
  // ------------------------------------------------------------
  const _prompt256=v21Prompt;
  v21Prompt=function(ocr,mode){
    let p=_prompt256(ocr,mode);
    if(mode==='tactic'){
      const s=selectedSlot();
      const learn=(Array.isArray(s?.learningLog)?s.learningLog:[]).slice(0,12).map((x,i)=>({
        jogo:i+1,
        opponent:x.opponent,
        score:x.score,
        outcome:x.outcome,
        formation:x.formation,
        opponentFormation:x.opponentFormation,
        strengthBucket:x.strengthBucket,
        context:x.context,
        stats:x.stats,
        patterns:x.patterns,
        increaseWeight:x.increaseWeight,
        decreaseWeight:x.decreaseWeight,
        avoid:x.avoid,
        nextUse:x.nextUse
      }));
      p+=`

APRENDIZADO REAL DESTE SLOT:
${JSON.stringify(learn,null,2)}

REGRAS PARA USAR O APRENDIZADO:
- Use apenas como evidência histórica, nunca como verdade absoluta.
- Dê mais peso a partidas com contexto semelhante: diferença de força, formação rival, local, árbitro e estilo rival.
- Considere resultado E estatísticas. Não trate vitória isolada como prova de que a tática é sempre boa.
- Se uma combinação perdeu repetidamente em contexto semelhante, reduza o peso dela.
- Se venceu repetidamente em contexto semelhante e as estatísticas também foram favoráveis, aumente o peso dela.
- Nunca invente dado ausente.`;
    }
    return p;
  };

  // Fecha qualquer banner "Consultando Gemini..." ao terminar fluxo de resultado.
  function clearGeminiJobBanner(){
    const b=document.getElementById('jobBanner');
    if(!b)return;
    if(/consultando gemini/i.test(String(b.textContent||''))){
      b.classList.add('hidden');
      b.textContent='';
      b.className='job-banner hidden';
    }
  }


  // ------------------------------------------------------------
  // 2.5.7 — CAMPOS MANUAIS COM CONTROLES CORRETOS
  // ------------------------------------------------------------
  const FIELD_NUMBER_PATHS=new Set([
    'myTeam.overall','opponent.overall',
    'myTeam.goalkeeper','myTeam.defence','myTeam.midfield','myTeam.attack',
    'opponent.goalkeeper','opponent.defence','opponent.midfield','opponent.attack',
    'opponent.stadium','opponent.loginBonus'
  ]);

  const FIELD_BOOLEAN_PATHS=new Set([
    'opponent.human','opponent.trainingCamp','opponent.secretTraining','opponent.offside'
  ]);

  const FIELD_SELECT_OPTIONS={
    'match.venue':['Casa','Fora'],
    'match.refereeColor':['Verde','Azul','Amarelo','Laranja','Vermelho'],
    'opponent.formation':FORMATIONS,
    'opponent.style':GAME_PLANS,
    'opponent.marking':['À zona','Individual']
  };

  function fieldControlHtml(path,value,id){
    const current=(value===null||value===undefined||value===''?'NI':value);

    if(FIELD_BOOLEAN_PATHS.has(path)){
      const vv=typeof value==='boolean'?String(value):'NI';
      return `<select id="${id}" data-path="${e(path)}" data-kind="boolean">
        <option value="NI" ${vv==='NI'?'selected':''}>NI</option>
        <option value="true" ${vv==='true'?'selected':''}>Sim</option>
        <option value="false" ${vv==='false'?'selected':''}>Não</option>
      </select>`;
    }

    const opts=FIELD_SELECT_OPTIONS[path];
    if(Array.isArray(opts)){
      return `<select id="${id}" data-path="${e(path)}" data-kind="select">
        <option value="NI" ${current==='NI'?'selected':''}>NI</option>
        ${opts.map(x=>`<option value="${e(x)}" ${String(current)===String(x)?'selected':''}>${e(x)}</option>`).join('')}
      </select>`;
    }

    if(FIELD_NUMBER_PATHS.has(path)){
      return `<input id="${id}" data-path="${e(path)}" data-kind="number" type="number" inputmode="numeric" step="1" value="${current==='NI'?'':e(current)}" placeholder="NI">`;
    }

    return `<input id="${id}" data-path="${e(path)}" data-kind="text" value="${current==='NI'?'':e(current)}" placeholder="NI">`;
  }

  function readManualControl(el){
    const kind=el?.dataset?.kind||'text';
    const raw=String(el?.value??'').trim();
    if(!raw || raw.toUpperCase()==='NI')return null;
    if(kind==='boolean')return raw==='true';
    if(kind==='number'){
      const num=Number(raw);
      return Number.isFinite(num)?num:null;
    }
    return raw;
  }

  window.editField=function(slotNo,path){
    const s=state.slots[slotNo-1];
    const def=FIELD_DEFS.find(x=>x[0]===path);
    const label=def?.[1]||path;
    const value=getPath(s,path);

    openModal(`<h2>Editar · ${e(label)}</h2>
      <div class="field-edit">
        <label>Valor
          ${fieldControlHtml(path,value,'fieldValue')}
        </label>
        <p class="small muted">Campos com opções fixas usam seleção. NI significa informação desconhecida.</p>
        <button class="btn" onclick="saveEditedField(${slotNo},'${e(path)}')">Salvar</button>
      </div>`);
  };

  window.saveEditedField=function(slotNo,path){
    const s=state.slots[slotNo-1];
    const el=document.getElementById('fieldValue');
    const value=readManualControl(el);
    setField(s,path,value,'manual',1);
    calcQuality(s);
    s.tactic=null;
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
    closeModal();
    renderAll();
    renderPregame();
    toast('Campo corrigido');
  };

  window.editAllFields=function(slotNo,missingOnly=false){
    const s=state.slots[slotNo-1];
    const rows=FIELD_DEFS.filter(([path])=>{
      const v=getPath(s,path);
      return !missingOnly || !(hasValue(v)||typeof v==='boolean');
    });

    openModal(`<h2>${missingOnly?'Completar campos ausentes':'Corrigir dados'} · Slot ${slotNo}</h2>
      <div class="field-edit bulk-smart-fields">
        ${rows.map(([path,label],i)=>`
          <label>
            ${e(label)}
            ${fieldControlHtml(path,getPath(s,path),`bulk_${i}`)}
          </label>`).join('')}
        <p class="small muted">Selecione as opções corretas. Texto livre ficou apenas onde realmente é necessário.</p>
        <button class="btn" onclick="saveBulkFields(${slotNo})">Salvar alterações</button>
      </div>`);
  };

  window.saveBulkFields=function(slotNo){
    const s=state.slots[slotNo-1];
    document.querySelectorAll('[id^="bulk_"]').forEach(el=>{
      const path=el.dataset.path;
      if(!path)return;
      setField(s,path,readManualControl(el),'manual',1);
    });
    calcQuality(s);
    s.tactic=null;
    localStorage.setItem(STATE_KEY,JSON.stringify(state));
    closeModal();
    renderAll();
    renderPregame();
    toast('Dados atualizados');
  };

  // ------------------------------------------------------------
  // 2.5.7 — ANÁLISE DE PARTIDA MAIS COMPLETA
  // ------------------------------------------------------------
  const _analyze257=v21Analyze;

  function evenlySpacedFrames(frames,max){
    const sorted=[...(frames||[])].sort((a,b)=>(a.time||0)-(b.time||0));
    if(sorted.length<=max)return sorted;
    const out=[];
    for(let i=0;i<max;i++){
      const idx=Math.round(i*(sorted.length-1)/Math.max(1,max-1));
      if(sorted[idx]&&!out.includes(sorted[idx]))out.push(sorted[idx]);
    }
    return out;
  }

  v21Analyze=async function(files){
    if(analysisMode!=='tactic')return _analyze257(files);

    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
    state.selectedSlot=slotNo;
    const video=files.find(f=>String(f.type||'').startsWith('video/'));
    const images=files.filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];

    setProgress(5,'Capturando mais telas da partida…');

    if(video){
      frames=await v21ExtractVideoFrames(video,32);
    }else if(images.length){
      for(const f of images)frames.push(await v21ImageToFrame(f));
    }else{
      throw new Error('Selecione um vídeo ou imagens do OSM.');
    }

    v21RenderEvidence(frames);
    setProgress(18,'OCR local em toda a análise…');
    const ocr=await v21RunLocalOcr(frames);

    const required=v21SelectRequiredTacticFrames(frames,ocr);
    let evidence=required.filter(x=>x.frame).map(x=>x.frame);

    for(const f of evenlySpacedFrames(frames,12)){
      if(!evidence.includes(f))evidence.push(f);
      if(evidence.length>=14)break;
    }

    const missingScreens=required.filter(x=>!x.frame).map(x=>x.label);
    if(document.getElementById('analysisDiagnostics')){
      document.getElementById('analysisDiagnostics').textContent=
        missingScreens.length
          ? `Algumas telas não foram classificadas com certeza (${missingScreens.join(', ')}). A IA continuará usando quadros distribuídos do vídeo.`
          : 'Todas as telas principais foram localizadas. Validando os campos…';
    }

    setProgress(58,'Conferindo forças, árbitro e Data Analyst…');
    const result=await v21AnalyzePackage(ocr,evidence,'tactic');
    v21ApplyCapture(result.capture||result.captures?.[0]||result);
    localStorage.setItem(STATE_KEY,JSON.stringify(state));

    const s=selectedSlot();
    calcQuality(s);
    renderCoverage(s);
    renderAnalysisSummary(s);
    renderPregame();

    if(document.getElementById('autoTactic')?.checked && !s.tactic){
      await generateTactic(slotNo);
    }

    const missingNow=missingRequired(s);
    setProgress(100,missingNow.length?'Análise concluída com campos pendentes':'Análise completa');
    setAnalysisRun(
      s,
      'tactic',
      missingNow.length?'warning':'success',
      missingNow.length
        ? `${missingNow.length} campo(s) essencial(is) ainda não identificado(s). Complete apenas os que souber.`
        : `Cobertura ${s.analysisQuality}% · dados essenciais confirmados.`,
      {quality:s.analysisQuality,missing:missingNow}
    );
    job(
      missingNow.length
        ? `Partida analisada. ${missingNow.length} campo(s) essencial(is) ficaram pendentes.`
        : 'Partida analisada com dados essenciais completos.',
      'done'
    );
  };
  window.v21Analyze=v21Analyze;

  const _prompt257=v21Prompt;
  v21Prompt=function(ocr,mode){
    let p=_prompt257(ocr,mode);
    if(mode==='tactic'){
      p+=`

REVISÃO FINAL OBRIGATÓRIA DA PARTIDA:
Antes de responder, percorra novamente TODAS as imagens enviadas e tente localizar, separadamente:
1. meu time e adversário;
2. casa/fora;
3. árbitro;
4. força geral dos dois times;
5. GOL/DEF/MEI/ATA dos dois times;
6. humano/CPU e manager rival;
7. bônus de login rival;
8. estádio rival;
9. campo de treinamento e treino secreto;
10. formação rival;
11. plano rival;
12. marcação rival;
13. impedimento rival.

Não use null só porque uma tela não foi classificada pelo OCR. Confira visualmente todos os quadros.
Porém, se realmente não estiver visível, mantenha null — nunca invente.`;
    }
    return p;
  };


  // ------------------------------------------------------------
  // 2.5.8 — BOTÃO FORTE 4-3-3 VISÍVEL NO PREPARAR
  // ------------------------------------------------------------
  function strong433ButtonHtml(s){
    if(!s || s.status!=='active')return '';
    const d=strengthDiff(s);

    if(d===null){
      return `<div class="card strong433-action-card strong433-waiting">
        <div>
          <span class="eyebrow">TÁTICA FORTE 4-3-3</span>
          <h3>Informe a força rival</h3>
          <p class="small muted">A força rival está NI. O app precisa desse valor para confirmar se sua vantagem é de pelo menos 13 pontos.</p>
        </div>
        <div class="actions">
          <button class="btn ghost" onclick="editField(${s.slotNumber},'opponent.overall')">Informar força rival</button>
        </div>
      </div>`;
    }

    if(d<13)return '';

    return `<div class="card strong433-action-card">
      <div>
        <span class="eyebrow">VANTAGEM DE FORÇA +${e(d)}</span>
        <h3>Tática forte 4-3-3 disponível</h3>
        <p class="small muted">Seu time está pelo menos 13 pontos acima do rival.</p>
      </div>
      <div class="actions">
        <button class="btn strong433-main-btn" onclick="applyOffensiveDefault(${s.slotNumber})">⚡ Gerar tática forte 4-3-3</button>
        <button class="btn ghost" onclick="toggleOffensiveDefault(${s.slotNumber})">Ver configuração</button>
      </div>
      <div id="offensiveDefaultBox-${s.slotNumber}" class="strong433-inline-details hidden">
        <table class="tactic-table">
          <tr><td>Formação</td><td>4-3-3 A</td></tr>
          <tr><td>Estilo de jogo</td><td>Jogar pelas alas</td></tr>
          <tr><td>Pressão</td><td>78</td></tr>
          <tr><td>Estilo / Mentalidade</td><td>82</td></tr>
          <tr><td>Temporização / Ritmo</td><td>84</td></tr>
          <tr><td>Marcação</td><td>À zona</td></tr>
          <tr><td>Impedimento</td><td>Sim</td></tr>
          <tr><td>Desarme</td><td>Normal</td></tr>
          <tr><td>Ataque</td><td>Atacar apenas</td></tr>
          <tr><td>Meio</td><td>Pressionar na frente</td></tr>
          <tr><td>Defesa</td><td>Apoiar o meio-campo</td></tr>
        </table>
      </div>
    </div>`;
  }

  const _renderPregame258=renderPregame;
  renderPregame=function(){
    _renderPregame258();
    const s=selectedSlot();
    const target=document.getElementById('pregameContent');
    if(!target || !s || s.status!=='active')return;

    // Remove qualquer versão antiga duplicada do botão/box.
    target.querySelectorAll('.offensive-default-wrap').forEach(x=>x.remove());

    const html=strong433ButtonHtml(s);
    if(html){
      target.insertAdjacentHTML('afterbegin',html);
    }
  };


  // ------------------------------------------------------------
  // 2.6.0 — DIRETOR MOSTRA O QUE A IA APRENDEU
  // ------------------------------------------------------------
  const _renderMarket260=renderMarket;
  renderMarket=function(){
    _renderMarket260();
    const s=selectedSlot();
    const target=document.getElementById('marketContent');
    if(!target || !s || s.status!=='active')return;

    const learn=(Array.isArray(s.learningLog)?s.learningLog:[]).slice(0,5);
    if(!learn.length){
      target.insertAdjacentHTML('beforeend',`
        <div class="card director-learning-card" style="margin-top:12px">
          <span class="eyebrow">APRENDIZADO DA IA</span>
          <h3>Ainda sem padrões suficientes</h3>
          <p class="small muted">Registre resultados. Aqui aparecerá o que ganhou peso, perdeu peso e deve ser evitado.</p>
        </div>`);
      return;
    }

    const positives=[...new Set(learn.flatMap(x=>x.increaseWeight||[]))].slice(0,6);
    const negatives=[...new Set(learn.flatMap(x=>x.decreaseWeight||[]))].slice(0,6);
    const avoid=[...new Set(learn.flatMap(x=>x.avoid||[]))].slice(0,4);
    const next=[...new Set(learn.flatMap(x=>x.nextUse||[]))].slice(0,6);

    target.insertAdjacentHTML('beforeend',`
      <div class="card director-learning-card" style="margin-top:12px">
        <div class="section-head compact-head">
          <div><span class="eyebrow">APRENDIZADO DA IA · SLOT ${e(s.slotNumber)}</span><h3>O que está mudando nas decisões</h3></div>
        </div>
        <p class="small muted">Resumo dos últimos ${learn.length} aprendizado(s) salvos.</p>
        ${positives.length?`<div class="director-learning-group positive"><b>↑ Ganhando peso</b>${positives.map(x=>`<div>${e(x)}</div>`).join('')}</div>`:''}
        ${negatives.length?`<div class="director-learning-group negative"><b>↓ Perdendo peso</b>${negatives.map(x=>`<div>${e(x)}</div>`).join('')}</div>`:''}
        ${avoid.length?`<div class="director-learning-group avoid"><b>⚠ Evitar</b>${avoid.map(x=>`<div>${e(x)}</div>`).join('')}</div>`:''}
        ${next.length?`<div class="director-learning-group next"><b>→ Próximas decisões</b>${next.map(x=>`<div>${e(x)}</div>`).join('')}</div>`:''}
        <div class="actions"><button class="btn ghost" onclick="showView('learning')">Ver aprendizado completo</button></div>
      </div>`);
  };


  // ------------------------------------------------------------
  // 2.6.1 — IA RÁPIDA: SOMENTE O MODELO ESCOLHIDO
  // ------------------------------------------------------------
  geminiJson=async function(parts,temperature=.1,maxOutputTokens=5000){
    const key=localStorage.getItem(API_KEY_STORAGE);
    if(!key)throw new Error('API Gemini não configurada.');

    const model=settings.model||'gemini-3.5-flash';
    if(document.getElementById('analysisDiagnostics')){
      document.getElementById('analysisDiagnostics').textContent=`Gemini: ${model}`;
    }
    job(`Consultando ${model}…`);

    const body={
      contents:[{role:'user',parts}],
      generationConfig:{
        temperature,
        maxOutputTokens,
        responseMimeType:'application/json'
      }
    };

    let res;
    try{
      res=await geminiFetch(model,key,body);
    }catch(err){
      const b=document.getElementById('jobBanner');
      if(b){b.className='job-banner error';b.textContent='Falha ao consultar a IA.'}
      throw new Error(err?.message||'Falha ao consultar a IA.');
    }

    if(!res.ok){
      const txt=await res.text();
      const b=document.getElementById('jobBanner');
      if(b){b.className='job-banner error';b.textContent=`Falha no ${model}.`}
      throw new Error(`Gemini ${res.status}: ${txt.slice(0,220)}`);
    }

    const data=await res.json();
    const text=(data.candidates?.[0]?.content?.parts||[])
      .map(p=>p.text||'')
      .join('')
      .trim();

    if(!text)throw new Error('A IA não retornou conteúdo utilizável.');

    if(document.getElementById('analysisDiagnostics')){
      document.getElementById('analysisDiagnostics').textContent=`Análise concluída com ${model}.`;
    }

    const b=document.getElementById('jobBanner');
    if(b){
      b.className='job-banner done';
      b.textContent=`Concluído com ${model}.`;
      setTimeout(()=>b.classList.add('hidden'),1800);
    }
    return parseJsonText(text);
  };

  // ------------------------------------------------------------
  // 2.6.1 — PLANO 72H DE EVOLUÇÃO
  // ------------------------------------------------------------
  function rosterRatings(s){
    return (Array.isArray(s?.roster)?s.roster:[])
      .map((p,index)=>({
        index,
        raw:p,
        name:playerNameValue(p)||`Jogador ${index+1}`,
        pos:normalizePos(playerPosValue(p)),
        rating:Number(playerRatingValue(p)),
        training:p?.training===true,
        forSale:p?.forSale===true
      }))
      .filter(x=>['ATA','MEI','DEF','GOL'].includes(x.pos) && Number.isFinite(x.rating));
  }

  function aggressiveGrowthPlan(s){
    const players=rosterRatings(s);
    const counts=countPositions(s.roster);
    const byPos={ATA:[],MEI:[],DEF:[],GOL:[]};
    for(const p of players)byPos[p.pos].push(p);
    for(const arr of Object.values(byPos))arr.sort((a,b)=>a.rating-b.rating);

    const all=players.map(x=>x.rating);
    const avg=all.length?all.reduce((a,b)=>a+b,0)/all.length:null;
    const displayed=Number(s?.myTeam?.overall);
    const baseline=Number.isFinite(displayed)?displayed:(avg!==null?Math.round(avg):null);

    const candidates=[];
    for(const pos of ['ATA','MEI','DEF','GOL']){
      const arr=byPos[pos];
      const target=POS_TARGET[pos];
      const excess=Math.max(0,arr.length-target);
      for(let i=0;i<excess;i++){
        const p=arr[i];
        if(p && !p.training)candidates.push({...p,reason:`Excesso em ${pos}; pode sair sem quebrar a estrutura ${target}.`});
      }
    }

    if(candidates.length<4){
      const remaining=players
        .filter(p=>!p.training && !candidates.some(c=>c.index===p.index))
        .sort((a,b)=>a.rating-b.rating);
      for(const p of remaining){
        if(candidates.length>=4)break;
        candidates.push({...p,reason:`Troca por upgrade: comprar substituto ${p.pos} antes da venda.`,replaceFirst:true});
      }
    }

    const sellQueue=candidates.slice(0,4);

    const priorities=[];
    for(const pos of ['ATA','MEI','DEF','GOL']){
      const arr=byPos[pos];
      if(!arr.length){
        priorities.push({pos,priority:100,weakest:null,target:baseline?baseline+8:75,reason:`Sem ${pos} reconhecido.`});
        continue;
      }
      const weakest=arr[0];
      const strongest=arr[arr.length-1];
      const target=Math.max(
        weakest.rating+10,
        avg!==null?Math.ceil(avg+6):weakest.rating+10,
        Math.ceil(strongest.rating*.92)
      );
      const deficit=Math.max(0,POS_TARGET[pos]-(counts[pos]||0));
      const gap=(avg!==null?avg-weakest.rating:0);
      priorities.push({
        pos,
        priority:deficit*50+gap,
        weakest,
        target,
        reason:deficit
          ? `Faltam ${deficit} jogador(es) para a estrutura ${POS_TARGET[pos]}.`
          : `Substituir o mais fraco (${weakest.rating}) por ${target}+ gera salto real.`
      });
    }
    priorities.sort((a,b)=>b.priority-a.priority);

    const target72=baseline!==null?Math.min(99,baseline+12):null;
    const stretchTarget=baseline!==null?Math.min(99,baseline+20):null;

    const actions=[];
    if(sellQueue.length)actions.push(`Manter 4 vendas ativas: ${sellQueue.map(x=>x.name).join(', ')}.`);
    actions.push(`Comprar primeiro nas prioridades: ${priorities.slice(0,2).map(x=>`${x.pos} ${x.target}+`).join(' e ')}.`);
    actions.push('Toda compra precisa ser upgrade claro; evitar jogador só para completar número.');
    actions.push('Repor imediatamente cada venda sem cair abaixo de 4 ATA / 6 MEI / 6 DEF / 2 GOL.');
    actions.push('Treinar continuamente os jogadores mais fortes/de maior teto e usar amistosos quando compensar.');
    actions.push('Após cada compra ou venda, recalcular o plano e trocar a fila de venda se necessário.');

    return {baseline,avg:avg!==null?Math.round(avg*10)/10:null,target72,stretchTarget,sellQueue,priorities,actions,counts};
  }

  function growthPlanHtml(s){
    const p=aggressiveGrowthPlan(s);
    const sells=p.sellQueue.length
      ? p.sellQueue.map((x,i)=>`
          <div class="growth-row">
            <span class="growth-rank">${i+1}</span>
            <div><b>${e(x.name)}</b><small>${e(x.pos)} · força ${e(x.rating)}</small></div>
            <em>${e(x.replaceFirst?'Comprar substituto antes':'Pode listar agora')}</em>
          </div>`).join('')
      : '<p class="small muted">Nenhum jogador elegível identificado para venda.</p>';

    const buys=p.priorities.slice(0,4).map((x,i)=>`
      <div class="growth-row">
        <span class="growth-rank">${i+1}</span>
        <div><b>${e(x.pos)} · buscar ${e(x.target)}+</b><small>${e(x.reason)}</small></div>
        <em>${x.weakest?`Atual ${e(x.weakest.rating)}`:'Prioridade'}</em>
      </div>`).join('');

    return `<div class="card growth72-card">
      <div class="section-head compact-head">
        <div>
          <span class="eyebrow">PLANO 72H · SLOT ${e(s.slotNumber)}</span>
          <h3>Subir força rapidamente</h3>
        </div>
      </div>

      <div class="growth-kpis">
        <div><span>Força atual</span><b>${e(p.baseline)}</b></div>
        <div><span>Meta 72h</span><b>${e(p.target72)}</b></div>
        <div><span>Meta agressiva</span><b>${e(p.stretchTarget)}</b></div>
        <div><span>Média elenco</span><b>${e(p.avg)}</b></div>
      </div>

      <p class="small muted">Metas são referências agressivas, não garantia. O salto depende de vendas, preços da lista, eventos e frequência de treino.</p>

      <h4>1. Fila de venda agora</h4>
      <div class="growth-list">${sells}</div>

      <h4>2. Compras que realmente melhoram o time</h4>
      <div class="growth-list">${buys}</div>

      <h4>3. Execução</h4>
      <div class="growth-actions">${p.actions.map(x=>`<div>${e(x)}</div>`).join('')}</div>

      <div class="actions">
        <button class="btn" onclick="addBoughtPlayerModal()">Registrar compra</button>
        <button class="btn ghost" onclick="sellPlayerModal()">Registrar venda</button>
        <button class="btn ghost" onclick="renderMarket()">Recalcular plano</button>
      </div>
    </div>`;
  }

  const _renderMarket261=renderMarket;
  renderMarket=function(){
    _renderMarket261();
    const s=selectedSlot();
    const target=document.getElementById('marketContent');
    if(!target || !s || s.status!=='active')return;
    const old=document.getElementById('growth72Host');
    if(old)old.remove();
    target.insertAdjacentHTML('afterbegin',`<div id="growth72Host">${growthPlanHtml(s)}</div>`);
  };


  // ------------------------------------------------------------
  // 2.6.2 — TÁTICA FORTE: NÃO COBRAR TODOS OS CAMPOS
  // ------------------------------------------------------------
  function isStrong433Applied(s){
    return s?.tactic?.engine==='Padrão ofensivo 2.5.2' ||
           s?.tactic?.engine==='Tática forte 4-3-3' ||
           s?.tactic?.strong433===true;
  }

  function refereeTackling(ref){
    const r=n(ref);
    if(r==='vermelho' || r==='laranja')return 'Cuidadoso';
    if(r==='amarelo')return 'Normal';
    if(r==='azul' || r==='verde')return 'Agressivo';
    return null;
  }

  const _applyOffensiveDefault262=applyOffensiveDefault;
  window.applyOffensiveDefault=function(slotNo){
    const s=state.slots[slotNo-1];
    if(!s)return;
    _applyOffensiveDefault262(slotNo);
    const cur=state.slots[slotNo-1];
    if(cur?.tactic){
      cur.tactic.strong433=true;
      cur.tactic.engine='Tática forte 4-3-3';
      const tk=refereeTackling(cur.match?.refereeColor);
      cur.tactic.tackling=tk||'NI';
      localStorage.setItem(STATE_KEY,JSON.stringify(state));
    }
    renderAll();
    showView('pregame');
  };

  function strongTacticMissing(s){
    if(!isStrong433Applied(s))return missingRequired(s);
    return filled(s?.match?.refereeColor)?[]:['match.refereeColor'];
  }

  function strong433RefereeHtml(s){
    if(!isStrong433Applied(s))return '';
    const ref=s?.match?.refereeColor;
    const tk=refereeTackling(ref);
    if(!filled(ref)){
      return `<div class="card strong433-referee-warning">
        <div>
          <span class="eyebrow">TÁTICA FORTE APLICADA</span>
          <h3>Falta apenas o árbitro</h3>
          <p class="small muted">A 4-3-3 já está pronta. Informe o árbitro para o app definir o desarme correto.</p>
        </div>
        <div class="actions">
          <button class="btn" onclick="editField(${s.slotNumber},'match.refereeColor')">Definir árbitro</button>
          <button class="btn ghost" onclick="openTacticMediaPicker(${s.slotNumber})">📹 Enviar vídeo / atualizar dados</button>
        </div>
      </div>`;
    }
    return `<div class="card strong433-referee-ok">
      <span class="eyebrow">TÁTICA FORTE APLICADA</span>
      <h3>Árbitro ${e(ref)} · Desarme ${e(tk||s.tactic?.tackling||'NI')}</h3>
      <div class="actions">
        <button class="btn ghost" onclick="editField(${s.slotNumber},'match.refereeColor')">Alterar árbitro</button>
        <button class="btn ghost" onclick="openTacticMediaPicker(${s.slotNumber})">📹 Enviar vídeo / atualizar dados</button>
      </div>
    </div>`;
  }

  window.openTacticMediaPicker=function(slotNo){
    state.selectedSlot=slotNo;
    renderAll();
    showView('analyze');
    setAnalysisMode('tactic');
    const select=document.getElementById('analysisSlot');
    if(select)select.value=String(slotNo);
    setTimeout(()=>{
      const input=document.getElementById('mediaInput');
      if(!input)return;
      input.value='';
      try{
        if(typeof input.showPicker==='function')input.showPicker();
        else input.click();
      }catch{
        input.click();
      }
    },220);
  };

  const _renderPregame262=renderPregame;
  renderPregame=function(){
    _renderPregame262();
    const s=selectedSlot();
    const target=document.getElementById('pregameContent');
    if(!target || !s || s.status!=='active')return;

    const refHtml=strong433RefereeHtml(s);
    if(refHtml)target.insertAdjacentHTML('afterbegin',refHtml);

    if(isStrong433Applied(s)){
      target.querySelectorAll('.audit-card').forEach(card=>{
        const txt=String(card.textContent||'');
        if(/revis[aã]o necess[aá]ria|campo\(s\).*sem confirma/i.test(txt)){
          card.classList.add('strong433-hidden-audit');
        }
      });
    }
  };

  const _nextAction262=nextAction;
  nextAction=function(){
    const s=selectedSlot();
    if(!s || !isStrong433Applied(s))return _nextAction262();

    const miss=strongTacticMissing(s);
    if(miss.length){
      return {
        priority:`SLOT ${s.slotNumber} · TÁTICA FORTE`,
        title:`${s.teamName||'Meu time'} × ${s.opponent?.teamName||'Adversário'}`,
        detail:'4-3-3 forte aplicada. Falta apenas confirmar o árbitro para ajustar o desarme.',
        buttons:`<button class="btn" onclick="editField(${s.slotNumber},'match.refereeColor')">Definir árbitro</button><button class="btn ghost" onclick="openTacticMediaPicker(${s.slotNumber})">Enviar vídeo</button><button class="btn ghost" onclick="resultModal(${s.slotNumber})">Registrar resultado</button>`
      };
    }

    return {
      priority:`SLOT ${s.slotNumber} · TÁTICA FORTE PRONTA`,
      title:`${s.teamName||'Meu time'} × ${s.opponent?.teamName||'Adversário'}`,
      detail:`${s.match?.venue||'Local NI'} · ${s.match?.nextMatchAt?fmtDate(s.match.nextMatchAt):'Horário NI'} · desarme ${s.tactic?.tackling||'NI'}.`,
      buttons:`<button class="btn" onclick="showView('pregame')">Ver plano</button><button class="btn ghost" onclick="openTacticMediaPicker(${s.slotNumber})">Atualizar vídeo</button><button class="btn ghost" onclick="resultModal(${s.slotNumber})">Registrar resultado</button>`
    };
  };

  const _renderRadar262=renderRadar;
  renderRadar=function(){
    const s=selectedSlot();
    const el=document.getElementById('radarPanel');
    if(s && isStrong433Applied(s)){
      const refOk=filled(s.match?.refereeColor);
      el.innerHTML=`<div class="section-head"><div><span class="eyebrow">RADAR DO SLOT ${s.slotNumber}</span><h2>Próximas ações</h2></div></div>
        <div class="card radar-list">
          ${refOk
            ? '<p class="muted">Tática forte pronta. Nenhum campo adicional é obrigatório agora.</p>'
            : `<div class="radar-item"><div><b>Definir árbitro</b><span>Necessário apenas para ajustar o desarme da 4-3-3 forte.</span></div><span class="status warn">Atenção</span></div>`
          }
        </div>`;
      return;
    }
    _renderRadar262();
  };

  const _saveEditedField262=saveEditedField;
  window.saveEditedField=function(slotNo,path){
    _saveEditedField262(slotNo,path);
    const s=state.slots[slotNo-1];
    if(path==='match.refereeColor' && s?.tactic && isStrong433Applied(s)){
      const tk=refereeTackling(s.match?.refereeColor);
      s.tactic.tackling=tk||'NI';
      localStorage.setItem(STATE_KEY,JSON.stringify(state));
      renderAll();
      renderPregame();
      toast(`Árbitro atualizado · Desarme ${tk||'NI'}`);
    }
  };

  // ------------------------------------------------------------
  // 2.6.2 — 503: UMA ÚNICA NOVA TENTATIVA NO MESMO MODELO
  // ------------------------------------------------------------
  geminiJson=async function(parts,temperature=.1,maxOutputTokens=5000){
    const key=localStorage.getItem(API_KEY_STORAGE);
    if(!key)throw new Error('API Gemini não configurada.');

    const model=settings.model||'gemini-3.5-flash';
    const body={
      contents:[{role:'user',parts}],
      generationConfig:{temperature,maxOutputTokens,responseMimeType:'application/json'}
    };

    let lastError='';
    for(let attempt=1;attempt<=2;attempt++){
      if(document.getElementById('analysisDiagnostics')){
        document.getElementById('analysisDiagnostics').textContent=
          attempt===1?`Gemini: ${model}`:`Gemini: ${model} · nova tentativa`;
      }
      job(attempt===1?`Consultando ${model}…`:`${model} indisponível · tentando mais uma vez…`);

      let res;
      try{
        res=await geminiFetch(model,key,body);
      }catch(err){
        lastError=err?.message||String(err);
        if(attempt===1){
          await new Promise(r=>setTimeout(r,900));
          continue;
        }
        break;
      }

      if(res.ok){
        const data=await res.json();
        const text=(data.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim();
        if(!text)throw new Error('A IA não retornou conteúdo utilizável.');

        const b=document.getElementById('jobBanner');
        if(b){
          b.className='job-banner done';
          b.textContent=`Concluído com ${model}.`;
          setTimeout(()=>b.classList.add('hidden'),1400);
        }
        return parseJsonText(text);
      }

      const txt=await res.text();
      lastError=`Gemini ${res.status}: ${txt.slice(0,220)}`;
      if(attempt===1 && [429,500,502,503,504].includes(res.status)){
        await new Promise(r=>setTimeout(r,900));
        continue;
      }
      break;
    }

    const b=document.getElementById('jobBanner');
    if(b){
      b.className='job-banner error';
      b.textContent=`Falha no ${model}. Tente novamente em instantes.`;
    }
    throw new Error(lastError||`Falha no ${model}.`);
  };

  // ------------------------------------------------------------
  // 2.6.2 — LEITURA PARCIAL: UM CARD RUIM NÃO INVALIDA O RESTO
  // ------------------------------------------------------------
  const _v21ApplyCapture262=v21ApplyCapture;
  v21ApplyCapture=function(c){
    try{
      _v21ApplyCapture262(c||{});
    }catch(err){
      console.warn('Falha parcial ao aplicar captura:',err);
      const safe=c||{};
      const s=selectedSlot();
      if(safe.teamName)s.teamName=safe.teamName;
      if(safe.opponent?.teamName)s.opponent.teamName=safe.opponent.teamName;
      if(safe.myTeam)s.myTeam=v21MergeNonNull(s.myTeam,safe.myTeam);
      if(safe.opponent)s.opponent=v21MergeNonNull(s.opponent,safe.opponent);
      if(safe.match)s.match=v21MergeNonNull(s.match,safe.match);
      s.lastAnalysisAt=new Date().toISOString();
      calcQuality(s);
    }
  };


  // ------------------------------------------------------------
  // 2.6.4 — CONTINGÊNCIA: ERRO DA IA NÃO CANCELA A ANÁLISE
  // ------------------------------------------------------------
  let lastFailedTacticAnalysis=null;

  function localOcrText(ocr){
    return String(ocr?.joined||'');
  }

  function detectRefereeFromOcr(text){
    const t=n(text);
    for(const color of ['vermelho','laranja','amarelo','azul','verde']){
      if(t.includes(color))return color.charAt(0).toUpperCase()+color.slice(1);
    }
    return null;
  }

  function detectVenueFromOcr(text){
    const t=n(text);
    if(/\bcasa\b/.test(t))return 'Casa';
    if(/\bfora\b/.test(t))return 'Fora';
    return null;
  }

  function detectFormationFromOcr(text){
    const raw=String(text||'');
    const found=FORMATIONS.find(f=>raw.includes(f));
    return found||null;
  }

  function detectMarkingFromOcr(text){
    const t=n(text);
    if(t.includes('marcacao a zona') || t.includes('a zona') || t.includes('zona'))return 'À zona';
    if(t.includes('individual'))return 'Individual';
    return null;
  }

  function detectOffsideFromOcr(text){
    const t=n(text);
    if(t.includes('fora de jogo') || t.includes('impedimento')){
      if(/\bnao\b|\bnão\b/.test(t))return false;
      if(/\bsim\b/.test(t))return true;
    }
    return null;
  }

  function applyLocalFallbackFromOcr(s,ocr){
    const text=localOcrText(ocr);
    const detected=[];

    const setIfEmpty=(path,value)=>{
      if(value===null||value===undefined||value==='')return;
      const current=getPath(s,path);
      if(hasValue(current)||typeof current==='boolean')return;
      setField(s,path,value,'local_ocr',.55);
      detected.push(path);
    };

    setIfEmpty('match.refereeColor',detectRefereeFromOcr(text));
    setIfEmpty('match.venue',detectVenueFromOcr(text));
    setIfEmpty('opponent.formation',detectFormationFromOcr(text));
    setIfEmpty('opponent.marking',detectMarkingFromOcr(text));

    const off=detectOffsideFromOcr(text);
    if(off!==null && !(typeof getPath(s,'opponent.offside')==='boolean')){
      setField(s,'opponent.offside',off,'local_ocr',.55);
      detected.push('opponent.offside');
    }

    calcQuality(s);
    return detected;
  }

  function localTacticFromRules(s){
    const d=strengthDiff(s);
    const ref=s?.match?.refereeColor;
    const tackling=refereeTackling(ref)||'Normal';

    if(d!==null && d>=13){
      return {
        formation:'4-3-3 A',
        gamePlan:'Jogar pelas alas',
        pressure:78,
        mentality:82,
        tempo:84,
        marking:'À zona',
        offside:'Sim',
        tackling,
        attackInstruction:'Atacar apenas',
        midfieldInstruction:'Pressionar na frente',
        defenceInstruction:'Apoiar o meio-campo',
        reason:'Gerada pelo modo de contingência local porque a IA estava indisponível.',
        confidenceScore:.58,
        generatedAt:new Date().toISOString(),
        engine:'Fallback local 4-3-3',
        fallback:true
      };
    }

    // Para cenários não dominantes, só gera se houver força dos dois times e formação rival.
    if(d!==null && filled(s?.opponent?.formation)){
      if(d<=-8){
        return {
          formation:'4-5-1',
          gamePlan:'Contra-ataque',
          pressure:38,
          mentality:34,
          tempo:68,
          marking:'À zona',
          offside:'Não',
          tackling,
          attackInstruction:'Atacar apenas',
          midfieldInstruction:'Manter posição',
          defenceInstruction:'Defender atrás',
          reason:'Fallback local conservador: time mais fraco e IA indisponível.',
          confidenceScore:.48,
          generatedAt:new Date().toISOString(),
          engine:'Fallback local defensivo',
          fallback:true
        };
      }
      return {
        formation:'4-2-3-1',
        gamePlan:'Jogo de passes',
        pressure:58,
        mentality:55,
        tempo:65,
        marking:'À zona',
        offside:'Não',
        tackling,
        attackInstruction:'Atacar apenas',
        midfieldInstruction:'Manter posição',
        defenceInstruction:'Defender atrás',
        reason:'Fallback local equilibrado: IA indisponível.',
        confidenceScore:.46,
        generatedAt:new Date().toISOString(),
        engine:'Fallback local equilibrado',
        fallback:true
      };
    }
    return null;
  }

  function renderAiFallbackNotice(s,detected){
    const target=document.getElementById('analysisContent');
    if(!target)return;
    const missing=missingRequired(s);
    target.innerHTML=`
      <div class="card ai-fallback-card">
        <span class="eyebrow">MODO DE CONTINGÊNCIA</span>
        <h3>A IA falhou, mas a análise local foi mantida</h3>
        <p class="small muted">O vídeo não foi perdido. O OCR local terminou e os dados válidos foram preservados.</p>
        <div class="fallback-kpis">
          <div><span>Campos locais</span><b>${detected.length}</b></div>
          <div><span>Pendentes</span><b>${missing.length}</b></div>
          <div><span>Tática local</span><b>${s.tactic?.fallback?'Sim':'Não'}</b></div>
        </div>
        <div class="actions">
          <button class="btn" onclick="retryLastTacticAI()">Tentar IA novamente</button>
          ${missing.length?`<button class="btn ghost" onclick="editAllFields(${s.slotNumber},true)">Completar campos</button>`:''}
          <button class="btn ghost" onclick="showView('pregame')">Ir para Preparar</button>
        </div>
      </div>`;
  }

  window.retryLastTacticAI=async function(){
    if(!lastFailedTacticAnalysis){
      toast('Não há análise pendente para tentar novamente.');
      return;
    }
    const {slotNo,ocr,evidence}=lastFailedTacticAnalysis;
    state.selectedSlot=slotNo;
    try{
      job('Tentando a IA novamente…');
      const result=await v21AnalyzePackage(ocr,evidence,'tactic');
      v21ApplyCapture(result.capture||result.captures?.[0]||result);
      localStorage.setItem(STATE_KEY,JSON.stringify(state));
      lastFailedTacticAnalysis=null;
      renderAll();
      renderPregame();
      renderAnalysisSummary(selectedSlot());
      job('Análise concluída.','done');
      toast('IA respondeu e a análise foi atualizada.');
    }catch(err){
      const b=document.getElementById('jobBanner');
      if(b){
        b.className='job-banner error';
        b.textContent='IA ainda indisponível. A análise local continua salva.';
      }
      toast('IA ainda indisponível; dados locais mantidos.');
    }
  };

  // Override somente do modo Partida para capturar a falha da IA e continuar.
  const _v21Analyze264=v21Analyze;
  v21Analyze=async function(files){
    if(analysisMode!=='tactic')return _v21Analyze264(files);

    const slotNo=Number(document.getElementById('analysisSlot')?.value)||state.selectedSlot;
    state.selectedSlot=slotNo;
    const video=files.find(f=>String(f.type||'').startsWith('video/'));
    const images=files.filter(f=>String(f.type||'').startsWith('image/'));
    let frames=[];

    setProgress(5,'Capturando telas da partida…');
    if(video)frames=await v21ExtractVideoFrames(video,32);
    else if(images.length){
      for(const f of images)frames.push(await v21ImageToFrame(f));
    }else throw new Error('Selecione um vídeo ou imagens do OSM.');

    v21RenderEvidence(frames);
    setProgress(18,'OCR local em toda a análise…');
    const ocr=await v21RunLocalOcr(frames);

    const required=v21SelectRequiredTacticFrames(frames,ocr);
    let evidence=required.filter(x=>x.frame).map(x=>x.frame);

    const sorted=[...frames].sort((a,b)=>(a.time||0)-(b.time||0));
    const extra=[];
    const want=Math.min(12,sorted.length);
    for(let i=0;i<want;i++){
      const idx=Math.round(i*(sorted.length-1)/Math.max(1,want-1));
      if(sorted[idx]&&!extra.includes(sorted[idx]))extra.push(sorted[idx]);
    }
    for(const f of extra){
      if(!evidence.includes(f))evidence.push(f);
      if(evidence.length>=14)break;
    }

    const s=selectedSlot();
    const locallyDetected=applyLocalFallbackFromOcr(s,ocr);
    localStorage.setItem(STATE_KEY,JSON.stringify(state));

    try{
      setProgress(58,'Consultando a IA…');
      const result=await v21AnalyzePackage(ocr,evidence,'tactic');
      v21ApplyCapture(result.capture||result.captures?.[0]||result);
      lastFailedTacticAnalysis=null;

      localStorage.setItem(STATE_KEY,JSON.stringify(state));
      calcQuality(s);
      renderCoverage(s);
      renderAnalysisSummary(s);
      renderPregame();

      setProgress(100,'Análise concluída');
      setAnalysisRun(s,'tactic','success',`Cobertura ${s.analysisQuality}%`,{quality:s.analysisQuality});
      job('Partida analisada.','done');
      return;
    }catch(err){
      // NÃO cancela. Mantém OCR, tenta tática local e apresenta o que já foi obtido.
      lastFailedTacticAnalysis={slotNo,ocr,evidence};

      if(!s.tactic){
        const fallback=localTacticFromRules(s);
        if(fallback)s.tactic=fallback;
      }

      localStorage.setItem(STATE_KEY,JSON.stringify(state));
      calcQuality(s);
      renderCoverage(s);
      renderPregame();
      renderAiFallbackNotice(s,locallyDetected);

      setProgress(100,'Análise local concluída · IA indisponível');
      setAnalysisRun(
        s,
        'tactic',
        'warning',
        `IA indisponível. ${locallyDetected.length} campo(s) aproveitado(s) localmente; ${missingRequired(s).length} pendente(s).`,
        {quality:s.analysisQuality,localFallback:true,missing:missingRequired(s)}
      );

      const b=document.getElementById('jobBanner');
      if(b){
        b.className='job-banner warn';
        b.textContent='IA indisponível · análise local salva';
        setTimeout(()=>b.classList.add('hidden'),2500);
      }
    }
  };
  window.v21Analyze=v21Analyze;

  // ------------------------------------------------------------
  // INICIALIZAÇÃO — NÃO altera adversário/rodada/calendário
  // ------------------------------------------------------------
  try{
    // Remove qualquer faixa visual criada por hotfix antigo, caso tenha ficado no DOM.
    document.getElementById('activeSlotBanner')?.remove();
    localStorage.setItem('osm_ai_coach_clean_fix',CLEAN_VERSION);
    renderAll();
    renderAnalysisStatus();
  }catch(err){
    console.error('Clean fix 2.5.0',err);
  }
})();
