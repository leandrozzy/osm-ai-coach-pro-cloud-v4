
(() => {
  const $ = id => document.getElementById(id);
  const SUB_KEY = 'osm_webpush_subscription_v2';

  function setStatus(msg, kind='warn'){
    const el=$('status');
    el.textContent=msg;
    el.className='status '+kind;
  }

  function urlBase64ToUint8Array(base64String){
    const padding='='.repeat((4-base64String.length%4)%4);
    const base64=(base64String+padding).replace(/-/g,'+').replace(/_/g,'/');
    const raw=atob(base64);
    return Uint8Array.from([...raw].map(c=>c.charCodeAt(0)));
  }

  async function getServerPushConfig(){
    const r = await fetch('/api/push-test?config=1', {cache:'no-store'});
    const data = await r.json().catch(()=>({}));
    if(!r.ok) throw new Error(data.error || `Falha ao ler configuração do servidor (${r.status})`);
    if(!data.publicKey) throw new Error('Servidor não retornou a VAPID pública.');
    if(data.pairMatches === false){
      throw new Error('As variáveis VAPID PUBLIC e PRIVATE da Vercel não formam o mesmo par. Corrija as variáveis e faça Redeploy.');
    }
    return data;
  }

  async function getFreshRegistration(){
    if(!('serviceWorker' in navigator)) throw new Error('Service Worker não suportado.');

    // Remove inscrições antigas e registros antigos deste domínio.
    const regs = await navigator.serviceWorker.getRegistrations();
    for(const reg of regs){
      try{
        const oldSub = await reg.pushManager.getSubscription();
        if(oldSub) await oldSub.unsubscribe();
      }catch(_){}
      try{ await reg.unregister(); }catch(_){}
    }

    localStorage.removeItem(SUB_KEY);

    const reg = await navigator.serviceWorker.register('/sw.js?v=3',{scope:'/'});
    await reg.update().catch(()=>{});

    const ready = await Promise.race([
      navigator.serviceWorker.ready,
      new Promise((_,rej)=>setTimeout(()=>rej(new Error('Service Worker não ativou em 15 s.')),15000))
    ]);
    if(!ready.active) throw new Error('Service Worker não ficou ativo.');
    return ready;
  }

  async function activate(){
    try{
      if(!('Notification' in window)) throw new Error('Notificações não suportadas.');
      const p=await Notification.requestPermission();
      if(p!=='granted') throw new Error('Permissão de notificações não concedida.');

      setStatus('⏳ Conferindo VAPID do servidor e recriando assinatura...', 'warn');

      const cfg = await getServerPushConfig();
      const reg = await getFreshRegistration();

      const sub = await reg.pushManager.subscribe({
        userVisibleOnly:true,
        applicationServerKey:urlBase64ToUint8Array(cfg.publicKey)
      });

      localStorage.setItem(SUB_KEY, JSON.stringify(sub));
      $('testBtn').disabled=false;
      $('closedBtn').disabled=false;
      setStatus(
        '✅ PUSH ATIVO NESTE DISPOSITIVO\n\nNova assinatura criada usando exatamente a VAPID pública carregada da Vercel.\nPar PUBLIC/PRIVATE: confirmado.',
        'ok'
      );
    }catch(e){
      console.error(e);
      setStatus('❌ '+(e?.message||e),'bad');
    }
  }

  async function send(delaySeconds){
    try{
      let sub=localStorage.getItem(SUB_KEY);
      if(!sub) throw new Error('Ative as notificações primeiro.');
      sub=JSON.parse(sub);

      setStatus(delaySeconds ? `⏳ Push agendado para ${delaySeconds}s. Feche a aba agora.` : '⏳ Enviando push...','warn');

      const r=await fetch('/api/push-test',{
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body:JSON.stringify({subscription:sub,delaySeconds})
      });
      const data=await r.json().catch(()=>({}));
      if(!r.ok) throw new Error(data.error||`HTTP ${r.status}`);
      setStatus(delaySeconds?'✅ Servidor aceitou o teste. Feche a aba.':'✅ Push enviado. Verifique a notificação.','ok');
    }catch(e){
      setStatus('❌ '+(e?.message||e),'bad');
    }
  }

  $('activateBtn').onclick=activate;
  $('testBtn').onclick=()=>send(0);
  $('closedBtn').onclick=()=>send(15);

  // Não reutiliza assinatura antiga automaticamente.
  localStorage.removeItem(SUB_KEY);
  $('testBtn').disabled=true;
  $('closedBtn').disabled=true;
  setStatus('Toque em “Ativar notificações Push” para recriar a assinatura com as chaves atuais da Vercel.','warn');
})();
