export default async function handler(req,res){
 if(req.method!=='POST')return res.status(405).json({error:'Method not allowed'});
 return res.status(200).json({ok:true,mode:'local-pwa',message:'O app usa notificações locais agendadas a partir do calendário salvo. Push remoto pode ser conectado a FCM sem alterar o frontend principal.'});
}
