
const webpush = require('web-push');

function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }

module.exports = async function handler(req,res){
  if(req.method!=='POST') return res.status(405).json({error:'Use POST.'});
  try{
    const {subscription,delaySeconds=0}=req.body||{};
    if(!subscription?.endpoint) return res.status(400).json({error:'Assinatura Web Push ausente.'});

    const publicKey=process.env.WEB_PUSH_VAPID_PUBLIC_KEY;
    const privateKey=process.env.WEB_PUSH_VAPID_PRIVATE_KEY;
    const subject=process.env.WEB_PUSH_SUBJECT||'mailto:osm-ai-coach@example.com';
    if(!publicKey||!privateKey) throw new Error('Configure WEB_PUSH_VAPID_PUBLIC_KEY e WEB_PUSH_VAPID_PRIVATE_KEY no Vercel.');

    webpush.setVapidDetails(subject,publicKey,privateKey);

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
