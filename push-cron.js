
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
function cfg(){
  const publicKey=(process.env.WEB_PUSH_VAPID_PUBLIC_KEY||'').trim();
  const privateKey=(process.env.WEB_PUSH_VAPID_PRIVATE_KEY||'').trim();
  const subject=(process.env.WEB_PUSH_SUBJECT||'mailto:osm-ai-coach@example.com').trim();
  const ecdh=crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(b64urlToBuffer(privateKey));
  const derived=bufferToB64url(ecdh.getPublicKey(null,'uncompressed'));
  if(derived!==publicKey)throw new Error('Par VAPID inválido.');
  webpush.setVapidDetails(subject,publicKey,privateKey);
}
function db(){
  if(!admin.apps.length){
    const raw=process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if(!raw)throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON não configurado.');
    admin.initializeApp({credential:admin.credential.cert(JSON.parse(raw))});
  }
  return admin.firestore();
}
async function send(sub,p){
  await webpush.sendNotification(sub,JSON.stringify(p),{TTL:240,urgency:'high'});
}
function dueWindow(minutes,threshold,tolerance=4){
  return minutes<=threshold && minutes>threshold-tolerance;
}
function matchKey(s){
  return s?.match?.nextMatchAt?`${s.slotNumber}|${s.match.nextMatchAt}|${s.opponent?.teamName||'NI'}`:null;
}
function notification(title,body,slot,view,tag,urgent=false){
  return {
    title,body,slot,view,url:`/?view=${view}&slot=${slot}`,
    tag,requireInteraction:urgent,
    vibrate:urgent?[220,80,220,80,260]:[140,70,140]
  };
}
function buildDue(doc){
  const out=[];
  const snap=doc.snapshot||{};
  const prefs=doc.preferences||{};
  const now=Date.now();

  for(const s of (snap.slots||[])){
    if(s.status!=='active')continue;
    const when=new Date(s?.match?.nextMatchAt||0).getTime();
    const mk=matchKey(s);
    const battle=String(s.competitionType||'').toLowerCase().includes('batal');
    const opponent=s?.opponent?.teamName||'adversário';
    const hasTactic=!!s.tactic;
    const missing=Array.isArray(s.missingRequired)?s.missingRequired.length:0;

    if(Number.isFinite(when) && when>0){
      const min=(when-now)/60000;

      if(battle && prefs.battle30!==false && dueWindow(min,30)){
        out.push(['battle30',notification(
          '⚔️ Batalha · 30 minutos',
          `Slot ${s.slotNumber} contra ${opponent}.${hasTactic?' Tática pronta.':' Tática ainda não gerada.'}`,
          s.slotNumber,'pregame',`battle30-${mk}`,true
        )]);
      }

      if(prefs.match20!==false && dueWindow(min,20)){
        let body=`Slot ${s.slotNumber} contra ${opponent} em cerca de 20 minutos.`;
        if(!hasTactic && prefs.tacticMissing!==false)body+=' Tática ainda não gerada.';
        else if(missing && prefs.missingData!==false)body+=` ${missing} dado(s) essencial(is) pendente(s).`;
        else if(hasTactic)body+=` ${s.tactic.formation||'Tática'} pronta.`;
        out.push(['match20',notification('⚠️ Partida em 20 minutos',body,s.slotNumber,'pregame',`match20-${mk}`,true)]);
      }

      if(prefs.match10!==false && dueWindow(min,10)){
        let body=`Slot ${s.slotNumber} contra ${opponent} em cerca de 10 minutos.`;
        if(!hasTactic)body+=' Abra o app e gere/confirme a tática.';
        else body+=` Confirme ${s.tactic.formation||'a tática'} no OSM.`;
        out.push(['match10',notification('🚨 Partida em 10 minutos',body,s.slotNumber,'pregame',`match10-${mk}`,true)]);
      }

      if(battle && prefs.battle5!==false && dueWindow(min,5,3)){
        out.push(['battle5',notification(
          '🚨 BATALHA · 5 minutos',
          `Slot ${s.slotNumber} contra ${opponent}. Verificação final da tática.`,
          s.slotNumber,'pregame',`battle5-${mk}`,true
        )]);
      }

      if(s?.pendingFixture?.status==='awaiting_opponent' && prefs.cupOpponent!==false && min<=360 && min>330){
        out.push(['cup_pending',notification(
          '🏆 Copa/Taça aguardando adversário',
          `Slot ${s.slotNumber}: a partida está próxima e o adversário ainda não foi confirmado.`,
          s.slotNumber,'info',`cup-pending-${mk}`,false
        )]);
      }

      if(prefs.resultPending!==false && now>when+20*60000 && now<when+5*60*60000){
        const baseline=doc.matchBaselines?.[mk]?.resultCount;
        if(Number.isFinite(Number(baseline)) && Number(s.resultCount||0)<=Number(baseline)){
          out.push(['result_pending',notification(
            '📊 Resultado pendente',
            `Slot ${s.slotNumber}: registre o resultado para atualizar calendário e aprendizado.`,
            s.slotNumber,'dashboard',`result-${mk}`,false
          )]);
        }
      }
    }
  }
  return out;
}

module.exports=async function handler(req,res){
  try{
    const secret=(process.env.PUSH_CRON_KEY||'').trim();
    const supplied=String(req.query?.key||'').trim();
    const auth=String(req.headers.authorization||'').replace(/^Bearer\s+/i,'').trim();
    if(!secret || (supplied!==secret && auth!==secret)){
      return res.status(401).json({error:'Não autorizado.'});
    }

    cfg();
    const store=db();
    const qs=await store.collection('osm_push_devices').where('active','==',true).limit(100).get();

    let devices=0,sentCount=0,errors=0;
    for(const d of qs.docs){
      devices++;
      const data=d.data();
      if(!data.subscription?.endpoint)continue;
      const sent={...(data.sent||{})};
      let changed=false;

      for(const [kind,payload] of buildDue(data)){
        const dedup=payload.tag;
        if(sent[dedup])continue;
        try{
          await send(data.subscription,payload);
          sent[dedup]=new Date().toISOString();
          sentCount++;
          changed=true;
        }catch(e){
          errors++;
          const status=e?.statusCode||0;
          if(status===404||status===410){
            await d.ref.set({active:false,lastPushError:String(e?.message||e),updatedAt:new Date().toISOString()},{merge:true});
          }
        }
      }

      if(changed){
        const entries=Object.entries(sent).sort((a,b)=>String(b[1]).localeCompare(String(a[1]))).slice(0,120);
        await d.ref.set({sent:Object.fromEntries(entries),lastCronAt:new Date().toISOString()},{merge:true});
      }
    }

    return res.status(200).json({ok:true,devices,sent:sentCount,errors,at:new Date().toISOString()});
  }catch(e){
    console.error('push-cron',e);
    return res.status(500).json({error:e?.message||'Falha no cron de Push.'});
  }
};
