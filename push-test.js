
const webpush = require('web-push');
const crypto = require('crypto');

function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }

function b64urlToBuffer(s){
  let x = String(s || '').replace(/-/g,'+').replace(/_/g,'/');
  while(x.length % 4) x += '=';
  return Buffer.from(x, 'base64');
}

function bufferToB64url(b){
  return Buffer.from(b).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}

function readConfig(){
  const publicKey=(process.env.WEB_PUSH_VAPID_PUBLIC_KEY||'').trim();
  const privateKey=(process.env.WEB_PUSH_VAPID_PRIVATE_KEY||'').trim();
  const subject=(process.env.WEB_PUSH_SUBJECT||'mailto:osm-ai-coach@example.com').trim();
  if(!publicKey) throw new Error('WEB_PUSH_VAPID_PUBLIC_KEY não configurada na Vercel.');
  if(!privateKey) throw new Error('WEB_PUSH_VAPID_PRIVATE_KEY não configurada na Vercel.');

  let derivedPublic = null;
  let pairMatches = false;
  try{
    const ecdh = crypto.createECDH('prime256v1');
    ecdh.setPrivateKey(b64urlToBuffer(privateKey));
    derivedPublic = bufferToB64url(ecdh.getPublicKey(null, 'uncompressed'));
    pairMatches = derivedPublic === publicKey;
  }catch(_){}

  return {publicKey,privateKey,subject,derivedPublic,pairMatches};
}

module.exports = async function handler(req,res){
  try{
    const cfg=readConfig();

    if(req.method==='GET'){
      return res.status(200).json({
        ok:true,
        publicKey:cfg.publicKey,
        pairMatches:cfg.pairMatches,
        publicKeyStart:cfg.publicKey.slice(0,12),
        derivedPublicStart:cfg.derivedPublic ? cfg.derivedPublic.slice(0,12) : null
      });
    }

    if(req.method!=='POST') return res.status(405).json({error:'Use GET ou POST.'});

    if(!cfg.pairMatches){
      return res.status(500).json({
        error:'As variáveis WEB_PUSH_VAPID_PUBLIC_KEY e WEB_PUSH_VAPID_PRIVATE_KEY da Vercel não pertencem ao mesmo par.'
      });
    }

    const {subscription,delaySeconds=0}=req.body||{};
    if(!subscription?.endpoint) return res.status(400).json({error:'Assinatura Web Push ausente.'});

    webpush.setVapidDetails(cfg.subject,cfg.publicKey,cfg.privateKey);

    const delay=Math.max(0,Math.min(20,Number(delaySeconds)||0));
    if(delay) await sleep(delay*1000);

    const payload=JSON.stringify({
      title:'🤖 OSM AI Coach',
      body:delay?'Push com o app fechado funcionando.':'Push configurado corretamente.',
      url:'/',
      tag:'osm-webpush-test'
    });

    await webpush.sendNotification(subscription,payload,{TTL:60,urgency:'high'});
    return res.status(200).json({ok:true});
  }catch(e){
    console.error(e);
    return res.status(500).json({error:e?.body||e?.message||'Falha no Web Push.'});
  }
};
module.exports.config={maxDuration:30};
