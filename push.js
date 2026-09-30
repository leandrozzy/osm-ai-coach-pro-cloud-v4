
(() => {
  const $ = id => document.getElementById(id);
  const SUB_KEY = 'osm_webpush_subscription_v1';

  function setStatus(msg, kind='warn'){
    const el=$('status'); el.textContent=msg; el.className='status '+kind;
  }
  function urlBase64ToUint8Array(base64String){
    const padding='='.repeat((4-base64String.length%4)%4);
    const base64=(base64String+padding).replace(/-/g,'+').replace(/_/g,'/');
    const raw=atob(base64);
    return Uint8Array.from([...raw].map(c=>c.charCodeAt(0)));
  }
  async function getActiveSW(){
    if(!('serviceWorker' in navigator)) throw new Error('Service Worker não suportado.');
    let reg=await navigator.serviceWorker.register('/sw.js',{scope:'/'});
    try{await reg.update()}catch(_){}
    reg=await Promise.race([
      navigator.serviceWorker.ready,
      new Promise((_,rej)=>setTimeout(()=>rej(new Error('Service Worker não ativou em 15 s.')),15000))
    ]);
    if(!reg.active) throw new Error('Service Worker ainda não está ativo.');
    return reg;
  }

  async function activate(){
    try{
      if(!('Notification' in window)) throw new Error('Notificações não suportadas.');
      const p=await Notification.requestPermission();
      if(p!=='granted') throw new Error('Permissão de notificações não concedida.');

      const reg=await getActiveSW();
      // Remove qualquer assinatura antiga criada com outra VAPID
      // (por exemplo, a tentativa anterior via Firebase/FCM).
      let sub=await reg.pushManager.getSubscription();
      if(sub){
        try{ await sub.unsubscribe(); }catch(_){}
      }

      sub=await reg.pushManager.subscribe({
        userVisibleOnly:true,
        applicationServerKey:urlBase64ToUint8Array(window.OSM_WEB_PUSH_PUBLIC_KEY)
      });

      localStorage.setItem(SUB_KEY, JSON.stringify(sub));
      $('testBtn').disabled=false;
      $('closedBtn').disabled=false;
      setStatus('✅ PUSH ATIVO NESTE DISPOSITIVO\n\nAssinatura Web Push criada. Não usa FCM Registration Token.','ok');
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
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({subscription:sub,delaySeconds})
      });
      const data=await r.json().catch(()=>({}));
      if(!r.ok) throw new Error(data.error||`HTTP ${r.status}`);
      setStatus(delaySeconds?'✅ Servidor aceitou o teste. Feche a aba.':'✅ Push enviado.','ok');
    }catch(e){ setStatus('❌ '+(e?.message||e),'bad'); }
  }

  $('activateBtn').onclick=activate;
  $('testBtn').onclick=()=>send(0);
  $('closedBtn').onclick=()=>send(15);

  if(localStorage.getItem(SUB_KEY)){
    $('testBtn').disabled=false; $('closedBtn').disabled=false;
    setStatus('Assinatura Web Push salva. Toque em Ativar para validar/renovar.','ok');
  }
})();
