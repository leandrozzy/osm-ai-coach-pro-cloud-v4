
const webpush=require('web-push');
const crypto=require('crypto');
const admin=require('firebase-admin');

function b64urlToBuffer(s){
  let x=String(s||'').replace(/-/g,'+').replace(/_/g,'/');
  while(x.length%4)x+='=';
  return Buffer.from(x,'base64');
}
function bufferToB64url(b){
  return Buffer.from(b).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}
function vapid(){
  const publicKey=(process.env.WEB_PUSH_VAPID_PUBLIC_KEY||'').trim();
  const privateKey=(process.env.WEB_PUSH_VAPID_PRIVATE_KEY||'').trim();
  const subject=(process.env.WEB_PUSH_SUBJECT||'mailto:osm-ai-coach@example.com').trim();
  if(!publicKey||!privateKey)throw new Error('VAPID não configurada na Vercel.');
  let derived=null,pairMatches=false;
  try{
    const ecdh=crypto.createECDH('prime256v1');
    ecdh.setPrivateKey(b64urlToBuffer(privateKey));
    derived=bufferToB64url(ecdh.getPublicKey(null,'uncompressed'));
    pairMatches=derived===publicKey;
  }catch{}
  return {publicKey,privateKey,subject,pairMatches};
}
function db(){
  if(!admin.apps.length){
    const raw=process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if(!raw)throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON não configurado.');
    let serviceAccount;
    try{serviceAccount=JSON.parse(raw)}catch{throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON inválido.')}
    admin.initializeApp({credential:admin.credential.cert(serviceAccount)});
  }
  return admin.firestore();
}
function cleanId(v){
  return String(v||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,120);
}
function sameOrigin(req){
  const origin=req.headers.origin||'';
  const host=req.headers.host||'';
  return !origin || origin.includes(host);
}
async function send(subscription,payload){
  const cfg=vapid();
  if(!cfg.pairMatches)throw new Error('As chaves VAPID PUBLIC/PRIVATE não formam o mesmo par.');
  webpush.setVapidDetails(cfg.subject,cfg.publicKey,cfg.privateKey);
  return webpush.sendNotification(subscription,JSON.stringify(payload),{TTL:180,urgency:'high'});
}
function eventPayload(kind,slot,body,url){
  const titleMap={
    tactic_ready:'⚽ Tática pronta',
    market:'📈 Mercado atualizado',
    analysis_error:'❌ Falha na análise',
    cup_opponent:'🏆 Adversário definido'
  };
  const viewMap={
    tactic_ready:'pregame',market:'market',analysis_error:'analyze',cup_opponent:'pregame'
  };
  return {
    title:titleMap[kind]||'🤖 OSM AI Coach',
    body:body||'Há uma atualização no OSM AI Coach.',
    url:url||'/',
    view:viewMap[kind]||'dashboard',
    slot:slot||null,
    tag:`osm-${kind}-${slot||'all'}`,
    requireInteraction:kind==='analysis_error'
  };
}

module.exports=async function handler(req,res){
  try{
    if(req.method==='GET'){
      const cfg=vapid();
      return res.status(200).json({
        ok:true,
        publicKey:cfg.publicKey,
        pairMatches:cfg.pairMatches,
        storageConfigured:!!process.env.FIREBASE_SERVICE_ACCOUNT_JSON
      });
    }
    if(req.method!=='POST')return res.status(405).json({error:'Use POST.'});
    if(!sameOrigin(req))return res.status(403).json({error:'Origem não permitida.'});

    const body=req.body||{};
    const action=body.action||'sync';
    const id=cleanId(body.deviceId);
    if(!id)return res.status(400).json({error:'deviceId ausente.'});

    if(action==='test'){
      const subscription=body.subscription;
      if(!subscription?.endpoint)return res.status(400).json({error:'Assinatura ausente.'});
      await send(subscription,{
        title:body.title||'🤖 OSM AI Coach',
        body:body.body||'Push integrado funcionando.',
        url:body.url||'/?view=settings',
        view:'settings',
        tag:'osm-push-test'
      });
      return res.status(200).json({ok:true});
    }

    const store=db();
    const ref=store.collection('osm_push_devices').doc(id);
    const prev=(await ref.get()).data()||{};

    if(action==='disable'){
      await ref.set({active:false,updatedAt:new Date().toISOString()},{merge:true});
      return res.status(200).json({ok:true});
    }

    if(action==='event'){
      const subscription=prev.subscription;
      if(!subscription?.endpoint)return res.status(404).json({error:'Dispositivo sem assinatura salva.'});
      const key=`evt_${body.kind}_${body.slot||0}_${Math.floor(Date.now()/60000)}`;
      if(prev.sent?.[key])return res.status(200).json({ok:true,dedup:true});
      await send(subscription,eventPayload(body.kind,body.slot,body.body,body.url));
      const sent={...(prev.sent||{}),[key]:new Date().toISOString()};
      await ref.set({sent,updatedAt:new Date().toISOString()},{merge:true});
      return res.status(200).json({ok:true});
    }

    const snapshot=body.snapshot||{};
    const slots=Array.isArray(snapshot.slots)?snapshot.slots:[];
    const matchBaselines={...(prev.matchBaselines||{})};
    for(const s of slots){
      const matchKey=s?.match?.nextMatchAt?`${s.slotNumber}|${s.match.nextMatchAt}|${s.opponent?.teamName||'NI'}`:null;
      if(matchKey && !matchBaselines[matchKey]){
        matchBaselines[matchKey]={
          resultCount:Number(s.resultCount)||0,
          firstSeenAt:new Date().toISOString()
        };
      }
    }

    await ref.set({
      active:true,
      subscription:body.subscription||prev.subscription||null,
      preferences:body.preferences||prev.preferences||{},
      snapshot,
      reason:body.reason||'sync',
      matchBaselines,
      sent:prev.sent||{},
      createdAt:prev.createdAt||new Date().toISOString(),
      updatedAt:new Date().toISOString()
    },{merge:true});

    return res.status(200).json({ok:true});
  }catch(e){
    console.error('push-api',e);
    return res.status(500).json({error:e?.message||'Falha no Push.'});
  }
};
