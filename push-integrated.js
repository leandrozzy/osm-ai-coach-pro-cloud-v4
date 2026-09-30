
'use strict';

(function(){
  const STATE_KEY='osm_ai_coach_pro_v2_state';
  const SETTINGS_KEY='osm_ai_coach_pro_v2_settings';
  const PUSH_PREF_KEY='osm_push_preferences_v4';
  const PUSH_DEVICE_KEY='osm_push_device_id_v4';
  const PUSH_SUB_KEY='osm_webpush_subscription_v4';

  const DEFAULT_PREFS={
    enabled:true,
    match20:true,
    match10:true,
    battle30:true,
    battle5:true,
    tacticMissing:true,
    missingData:true,
    tacticReady:true,
    resultPending:true,
    market:true,
    analysisError:true,
    cupOpponent:true
  };

  const REQUIRED=[
    'teamName','opponent.teamName','match.venue','match.refereeColor',
    'myTeam.overall','opponent.overall','opponent.formation',
    'opponent.style','opponent.marking','opponent.offside'
  ];

  const safeParse=(v,f=null)=>{try{return JSON.parse(v)}catch{return f}};
  const getPath=(obj,path)=>path.split('.').reduce((a,k)=>a?.[k],obj);
  const filled=v=>!(v===null||v===undefined||v===''||v==='NI');
  const esc=v=>String(v??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m]));
  const $=id=>document.getElementById(id);

  let prefs={...DEFAULT_PREFS,...safeParse(localStorage.getItem(PUSH_PREF_KEY),{})};
  let lastSnapshot=null;
  let syncTimer=null;
  let uiReady=false;

  function uuid(){
    if(crypto?.randomUUID)return crypto.randomUUID();
    return 'dev-'+Date.now()+'-'+Math.random().toString(36).slice(2);
  }
  function deviceId(){
    let id=localStorage.getItem(PUSH_DEVICE_KEY);
    if(!id){id=uuid();localStorage.setItem(PUSH_DEVICE_KEY,id)}
    return id;
  }
  function hash(v){
    try{
      const s=JSON.stringify(v);
      let h=2166136261;
      for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)}
      return (h>>>0).toString(36);
    }catch{return '0'}
  }
  function summarizeMarket(plan){
    const actions=Array.isArray(plan?.actions)?plan.actions:[];
    const text=actions.slice(0,4).map(a=>{
      if(typeof a==='string')return a;
      return a?.title||a?.action||a?.instruction||a?.reason||a?.player||'Ação de mercado';
    }).filter(Boolean);
    return {
      count:actions.length,
      text,
      fingerprint:hash(actions)
    };
  }
  function latestResultAt(s){
    const rows=Array.isArray(s?.results)?s.results:[];
    const times=rows.map(r=>new Date(r?.createdAt||r?.at||0).getTime()).filter(Number.isFinite);
    return times.length?new Date(Math.max(...times)).toISOString():null;
  }
  function analysisError(s){
    const runs=s?.analysisRuns||{};
    for(const [mode,run] of Object.entries(runs)){
      if(run?.status==='error')return {mode,message:run.message||'Falha na análise',at:run.at||null};
    }
    return null;
  }
  function normalizeSlot(s){
    const missing=REQUIRED.filter(p=>{
      const v=getPath(s,p);
      return !(filled(v)||typeof v==='boolean');
    });
    const tactic=s?.tactic||null;
    const market=summarizeMarket(s?.marketPlan);
    return {
      slotNumber:Number(s?.slotNumber)||0,
      status:s?.status||'empty',
      teamName:s?.teamName||null,
      competitionName:s?.competitionName||null,
      competitionType:s?.competitionType||null,
      round:s?.round??null,
      totalRounds:s?.totalRounds??null,
      match:{
        nextMatchAt:s?.match?.nextMatchAt||null,
        venue:s?.match?.venue||null,
        refereeColor:s?.match?.refereeColor||null
      },
      opponent:{
        teamName:s?.opponent?.teamName||null,
        human:s?.opponent?.human??null,
        formation:s?.opponent?.formation||null,
        style:s?.opponent?.style||null,
        marking:s?.opponent?.marking||null,
        offside:s?.opponent?.offside??null,
        overall:s?.opponent?.overall??null
      },
      myOverall:s?.myTeam?.overall??null,
      tactic:tactic?{
        formation:tactic.formation||null,
        gamePlan:tactic.gamePlan||null,
        tackling:tactic.tackling||null,
        generatedAt:tactic.generatedAt||null,
        fingerprint:hash({
          formation:tactic.formation,gamePlan:tactic.gamePlan,pressure:tactic.pressure,
          mentality:tactic.mentality,tempo:tactic.tempo,tackling:tactic.tackling
        })
      }:null,
      missingRequired:missing,
      market,
      resultCount:Array.isArray(s?.results)?s.results.length:0,
      latestResultAt:latestResultAt(s),
      pendingFixture:s?.pendingFixture?{
        status:s.pendingFixture.status||null,
        dateTime:s.pendingFixture.dateTime||null,
        round:s.pendingFixture.round??null,
        competitionType:s.pendingFixture.competitionType||null
      }:null,
      analysisError:analysisError(s)
    };
  }
  function snapshot(){
    const st=safeParse(localStorage.getItem(STATE_KEY),{});
    return {
      version:4,
      selectedSlot:Number(st?.selectedSlot)||1,
      syncedAt:new Date().toISOString(),
      slots:(Array.isArray(st?.slots)?st.slots:[]).map(normalizeSlot)
    };
  }

  async function serverConfig(){
    const r=await fetch('/api/push?action=config',{cache:'no-store'});
    const j=await r.json().catch(()=>({}));
    if(!r.ok)throw new Error(j.error||`Falha na configuração (${r.status})`);
    return j;
  }

  function b64uToBytes(s){
    const p='='.repeat((4-s.length%4)%4);
    const b=(s+p).replace(/-/g,'+').replace(/_/g,'/');
    const raw=atob(b);
    return Uint8Array.from([...raw].map(c=>c.charCodeAt(0)));
  }
  function sameKey(a,b){
    try{
      const aa=new Uint8Array(a||[]);
      const bb=b64uToBytes(b);
      if(aa.length!==bb.length)return false;
      for(let i=0;i<aa.length;i++)if(aa[i]!==bb[i])return false;
      return true;
    }catch{return false}
  }

  async function registration(){
    if(!('serviceWorker'in navigator))throw new Error('Service Worker não suportado neste navegador.');
    let reg=await navigator.serviceWorker.register('/sw.js?v=4',{scope:'/'});
    try{await reg.update()}catch{}
    return Promise.race([
      navigator.serviceWorker.ready,
      new Promise((_,rej)=>setTimeout(()=>rej(new Error('Service Worker não ficou ativo em 15 segundos.')),15000))
    ]);
  }

  async function getSubscription(create=false){
    const cfg=await serverConfig();
    if(cfg.pairMatches===false)throw new Error('As chaves VAPID da Vercel não formam o mesmo par.');
    const reg=await registration();
    let sub=await reg.pushManager.getSubscription();

    if(sub && !sameKey(sub.options?.applicationServerKey,cfg.publicKey)){
      try{await sub.unsubscribe()}catch{}
      sub=null;
    }
    if(!sub && create){
      sub=await reg.pushManager.subscribe({
        userVisibleOnly:true,
        applicationServerKey:b64uToBytes(cfg.publicKey)
      });
    }
    if(sub)localStorage.setItem(PUSH_SUB_KEY,JSON.stringify(sub));
    return sub;
  }

  async function api(payload){
    const r=await fetch('/api/push',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify(payload)
    });
    const j=await r.json().catch(()=>({}));
    if(!r.ok)throw new Error(j.error||`Erro ${r.status}`);
    return j;
  }

  async function sync(reason='sync'){
    if(!prefs.enabled)return;
    const sub=await getSubscription(false).catch(()=>null);
    const snap=snapshot();
    await api({
      action:'sync',
      deviceId:deviceId(),
      subscription:sub?sub.toJSON():safeParse(localStorage.getItem(PUSH_SUB_KEY),null),
      preferences:prefs,
      snapshot:snap,
      reason
    });
    detectImmediateEvents(lastSnapshot,snap);
    lastSnapshot=snap;
    updatePushUi('synced');
  }

  function debounceSync(reason){
    clearTimeout(syncTimer);
    syncTimer=setTimeout(()=>sync(reason).catch(e=>updatePushUi('error',e.message)),900);
  }

  async function activate(){
    if(!('Notification'in window))throw new Error('Notificações não suportadas.');
    const p=await Notification.requestPermission();
    if(p!=='granted')throw new Error('Permissão de notificações não concedida.');

    const sub=await getSubscription(true);
    prefs.enabled=true;
    localStorage.setItem(PUSH_PREF_KEY,JSON.stringify(prefs));
    await api({
      action:'sync',
      deviceId:deviceId(),
      subscription:sub.toJSON(),
      preferences:prefs,
      snapshot:snapshot(),
      reason:'activate'
    });
    updatePushUi('active');
  }

  async function disable(){
    prefs.enabled=false;
    localStorage.setItem(PUSH_PREF_KEY,JSON.stringify(prefs));
    try{await api({action:'disable',deviceId:deviceId()})}catch{}
    updatePushUi('disabled');
  }

  async function test(){
    const sub=await getSubscription(true);
    await api({
      action:'test',
      deviceId:deviceId(),
      subscription:sub.toJSON(),
      title:'🤖 OSM AI Coach',
      body:'Push integrado funcionando em todo o app.',
      url:'/?view=settings'
    });
  }

  async function fireEvent(kind,slot,body,url){
    if(!prefs.enabled)return;
    const map={
      tactic_ready:'tacticReady',
      market:'market',
      analysis_error:'analysisError',
      cup_opponent:'cupOpponent'
    };
    if(map[kind] && !prefs[map[kind]])return;
    try{
      await api({
        action:'event',
        deviceId:deviceId(),
        kind,
        slot:slot?.slotNumber||null,
        body,
        url:url||'/'
      });
    }catch{}
  }

  function detectImmediateEvents(before,after){
    if(!before)return;
    const oldBy=new Map((before.slots||[]).map(s=>[s.slotNumber,s]));
    for(const s of (after.slots||[])){
      const o=oldBy.get(s.slotNumber)||{};
      if(s.tactic && (!o.tactic || o.tactic.fingerprint!==s.tactic.fingerprint)){
        fireEvent('tactic_ready',s,
          `Slot ${s.slotNumber}: ${s.tactic.formation||'Tática'} · ${s.tactic.gamePlan||'plano pronto'}.`,
          `/?view=pregame&slot=${s.slotNumber}`);
      }
      if(s.market?.count>0 && s.market.fingerprint!==o.market?.fingerprint){
        fireEvent('market',s,
          `Slot ${s.slotNumber}: plano de mercado atualizado com ${s.market.count} ação(ões).`,
          `/?view=market&slot=${s.slotNumber}`);
      }
      if(s.analysisError?.at && s.analysisError.at!==o.analysisError?.at){
        fireEvent('analysis_error',s,
          `Slot ${s.slotNumber}: ${s.analysisError.message}`,
          `/?view=analyze&slot=${s.slotNumber}`);
      }
      if(o.pendingFixture?.status==='awaiting_opponent' &&
         s.pendingFixture?.status!=='awaiting_opponent' &&
         s.opponent?.teamName){
        fireEvent('cup_opponent',s,
          `Slot ${s.slotNumber}: adversário definido — ${s.opponent.teamName}.`,
          `/?view=pregame&slot=${s.slotNumber}`);
      }
    }
  }

  function pushCardHtml(){
    const items=[
      ['match20','20 min antes da partida'],
      ['match10','10 min antes da partida'],
      ['battle30','Batalha: 30 min antes'],
      ['battle5','Batalha: 5 min antes'],
      ['tacticMissing','Tática ainda não gerada'],
      ['missingData','Dados essenciais pendentes'],
      ['tacticReady','Tática pronta ou alterada'],
      ['resultPending','Resultado ainda não registrado'],
      ['market','Plano de mercado atualizado'],
      ['analysisError','Falha na análise'],
      ['cupOpponent','Adversário de Copa/Taça definido']
    ];
    return `<div id="integratedPushCard" class="card push-settings-card">
      <div class="push-head">
        <div><span class="eyebrow">PUSH AUTOMÁTICO</span><h3>Alertas mesmo com o app fechado</h3></div>
        <span id="pushStateBadge" class="push-badge">Verificando…</span>
      </div>
      <p class="small muted">O app sincroniza os 4 slots com o servidor. Os alertas de horário são disparados pelo verificador externo mesmo com o navegador fechado.</p>
      <div class="push-grid">
        ${items.map(([k,l])=>`<label class="push-check"><input type="checkbox" data-push-pref="${k}" ${prefs[k]?'checked':''}><span>${esc(l)}</span></label>`).join('')}
      </div>
      <div class="actions">
        <button id="pushActivateIntegrated" class="btn">🔔 Ativar / renovar Push</button>
        <button id="pushTestIntegrated" class="btn ghost">Enviar teste</button>
        <button id="pushSyncIntegrated" class="btn ghost">Sincronizar agora</button>
        <button id="pushDisableIntegrated" class="btn ghost">Desativar Push</button>
      </div>
      <div id="pushServerStatus" class="small muted" style="margin-top:10px"></div>
    </div>`;
  }

  function injectStyles(){
    if(document.getElementById('pushIntegratedStyles'))return;
    const s=document.createElement('style');
    s.id='pushIntegratedStyles';
    s.textContent=`
      .push-settings-card{margin-top:12px;border-color:#285b73!important}
      .push-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}
      .push-head h3{margin:5px 0 3px}
      .push-badge{font-size:9px;font-weight:900;padding:6px 8px;border-radius:999px;background:#25364a;color:#b6c8d9;white-space:nowrap}
      .push-badge.ok{background:#123d31;color:#79e6bb}
      .push-badge.warn{background:#403516;color:#ffd46c}
      .push-badge.bad{background:#451f29;color:#ff9ba7}
      .push-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:13px 0}
      .push-check{display:flex!important;align-items:flex-start!important;gap:9px!important;padding:10px!important;border:1px solid #1f4058!important;border-radius:10px!important;background:#091827!important}
      .push-check input{margin-top:2px!important;flex:0 0 auto}
      .push-check span{font-size:10px;color:#c3d3df}
      .push-top-indicator{display:inline-flex;align-items:center;gap:5px;font-size:9px;font-weight:900;padding:5px 7px;border-radius:999px;background:#143a30;color:#70dfb5;border:1px solid #28624f}
      @media(max-width:640px){.push-grid{grid-template-columns:1fr}.push-head{flex-direction:column}}
    `;
    document.head.appendChild(s);
  }

  function ensureUi(){
    injectStyles();
    const settingsView=$('view-settings');
    if(settingsView && !$('integratedPushCard')){
      const first=settingsView.querySelector('.card');
      if(first)first.insertAdjacentHTML('afterend',pushCardHtml());
      else settingsView.insertAdjacentHTML('beforeend',pushCardHtml());

      settingsView.querySelectorAll('[data-push-pref]').forEach(el=>{
        el.addEventListener('change',()=>{
          prefs[el.dataset.pushPref]=!!el.checked;
          localStorage.setItem(PUSH_PREF_KEY,JSON.stringify(prefs));
          debounceSync('preferences');
        });
      });
      $('pushActivateIntegrated').onclick=()=>activate().then(()=>{updatePushUi('active');if(window.toast)toast('Push ativado')}).catch(e=>updatePushUi('error',e.message));
      $('pushTestIntegrated').onclick=()=>test().then(()=>{if(window.toast)toast('Push de teste enviado')}).catch(e=>updatePushUi('error',e.message));
      $('pushSyncIntegrated').onclick=()=>sync('manual').then(()=>{if(window.toast)toast('Push sincronizado')}).catch(e=>updatePushUi('error',e.message));
      $('pushDisableIntegrated').onclick=()=>disable().then(()=>{if(window.toast)toast('Push desativado')});
      uiReady=true;
    }

    const top=document.querySelector('.top-actions');
    if(top && !document.getElementById('pushTopIndicator')){
      const span=document.createElement('span');
      span.id='pushTopIndicator';
      span.className='push-top-indicator';
      span.textContent='🔔 Push';
      span.onclick=()=>{
        document.querySelector('.nav-btn[data-view="settings"]')?.click();
        setTimeout(()=>$('integratedPushCard')?.scrollIntoView({behavior:'smooth',block:'center'}),100);
      };
      top.prepend(span);
    }
  }

  async function updatePushUi(mode='checking',message=''){
    ensureUi();
    const badge=$('pushStateBadge');
    const status=$('pushServerStatus');
    const top=$('pushTopIndicator');
    if(!badge)return;
    const map={
      active:['Ativo','ok'],
      synced:['Ativo · sincronizado','ok'],
      disabled:['Desativado','warn'],
      error:['Erro','bad'],
      checking:['Verificando…','warn']
    };
    const [txt,cls]=map[mode]||map.checking;
    badge.textContent=txt;
    badge.className='push-badge '+cls;
    if(top){
      top.textContent=mode==='error'?'🔕 Push':'🔔 Push';
      top.style.opacity=mode==='disabled'?'0.55':'1';
    }
    if(status)status.textContent=message||(
      mode==='active'||mode==='synced'
        ? 'Assinatura ativa. Dados dos slots serão sincronizados automaticamente.'
        : mode==='disabled'
          ? 'Push desativado neste dispositivo.'
          : ''
    );
  }

  function openFromNotification(){
    const q=new URLSearchParams(location.search);
    const view=q.get('view');
    const slot=Number(q.get('slot'));
    if(slot>=1&&slot<=4 && typeof window.selectSlot==='function'){
      try{window.selectSlot(slot)}catch{}
    }
    if(view){
      const b=document.querySelector(`.nav-btn[data-view="${CSS.escape(view)}"]`);
      if(b)b.click();
    }
    if(view||slot){
      history.replaceState({},'',location.pathname);
    }
  }

  function hookLocalStorage(){
    const original=Storage.prototype.setItem;
    if(original.__osmPushWrapped)return;
    function wrapped(k,v){
      const r=original.apply(this,arguments);
      if(this===localStorage && (k===STATE_KEY||k===SETTINGS_KEY)){
        debounceSync(k===STATE_KEY?'state-change':'settings-change');
      }
      return r;
    }
    wrapped.__osmPushWrapped=true;
    Storage.prototype.setItem=wrapped;
  }

  async function init(){
    hookLocalStorage();
    ensureUi();
    lastSnapshot=snapshot();
    openFromNotification();

    if(Notification.permission==='granted' && prefs.enabled){
      try{
        const sub=await getSubscription(false);
        if(sub){
          await sync('startup');
          updatePushUi('active');
        }else{
          updatePushUi('disabled','Push permitido no Android, mas este navegador ainda não possui assinatura ativa.');
        }
      }catch(e){
        updatePushUi('error',e.message);
      }
    }else if(!prefs.enabled){
      updatePushUi('disabled');
    }else{
      updatePushUi('disabled','Toque em “Ativar / renovar Push” na aba Config.');
    }

    setInterval(()=>{if(document.visibilityState==='visible')sync('heartbeat').catch(()=>{})},120000);
    document.addEventListener('visibilitychange',()=>{
      if(document.visibilityState==='hidden')sync('background').catch(()=>{});
    });
    window.addEventListener('pagehide',()=>sync('pagehide').catch(()=>{}));
  }

  document.addEventListener('DOMContentLoaded',()=>setTimeout(init,250));
})();
