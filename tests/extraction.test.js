import {readFileSync} from 'node:fs';
import {parseSquadOverlay} from '../src/ocr-layout.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import analyze from '../api/analyze.js';
import twelve from '../api/twelvelabs.js';
import {normalizeExtraction,fuseExtraction,cleanCalendar,coverage} from '../src/extraction.js';
const response=()=>({code:0,setHeader(){},status(code){this.code=code;return this;},json(data){this.data=data;return this;}});
test('calendário fora inverte placar e não inventa data do horário',()=>{
 const [row]=cleanCalendar([{round:17,home:false,displayedScore:'0-2',result:'V',time:'22:18',date:'NI'}]);
 assert.equal(row.score,'2x0');assert.equal(row.date,'NI');
 assert.equal(cleanCalendar([{round:14,home:false,displayedScore:'4-0',result:'D'}])[0].score,'0x4');
});
test('fusão preserva nome sem posição e registra força divergente',()=>{
 const a=normalizeExtraction({players:[{name:'Koundé',position:'NI',strength:'NI',age:27}]},'squad');
 const b=normalizeExtraction({players:[{name:'Koundé',position:'DEF',strength:91,age:27}]},'squad');
 const merged=fuseExtraction(a,b);assert.equal(merged.players.length,1);assert.equal(merged.players[0].strength,91);
 const conflict=fuseExtraction(merged,normalizeExtraction({players:[{name:'Koundé',position:'DEF',strength:92}]},'squad'));
 assert.equal(conflict.players[0].strength,91);assert.equal(conflict.conflicts.length,1);assert.equal(coverage('squad',conflict).complete,false);
});
test('Groq 429 não bloqueia OCR e não recebe repetição textual',async()=>{
 const old=globalThis.fetch;let groqCalls=0;
 globalThis.fetch=async url=>{if(url.includes('groq')){groqCalls++;return {ok:false,status:429,json:async()=>({})};}return {ok:true,json:async()=>({ParsedResults:[{ParsedText:'Força geral: 84',TextOverlay:{Lines:[]}}]})};};
 try{const res=response();await analyze({method:'POST',body:{type:'match',keys:{groq:'private-groq',ocrspace:'private-ocr'},images:[{url:'data:image/jpeg;base64,YQ==',width:2448,height:1080}]}},res);assert.equal(res.code,200);assert.equal(groqCalls,1);assert.ok(res.data.attempts.includes('ocrspace'));assert.equal(res.data.failures[0].status,429);assert.equal(JSON.stringify(res.data).includes('private-'),false);assert.equal(res.data.coverage.complete,false);}finally{globalThis.fetch=old;}
});
test('TwelveLabs usa vídeo original e contrato v1.3 e preserva campos ocultos',async()=>{
 const old=globalThis.fetch;let wire;
 globalThis.fetch=async(url,options)=>{wire={url,...JSON.parse(options.body)};return {ok:true,json:async()=>({data:JSON.stringify({match:{myStrength:88,rivalStrength:'NI'}})})};};
 try{const res=response();await twelve({method:'POST',body:{action:'analyze',type:'match',keys:{twelvelabs:'private-twelve'},videoBase64:'YQ=='}},res);assert.equal(res.code,200);assert.equal(wire.model_name,'pegasus1.5');assert.equal(wire.video.type,'base64_string');assert.equal(res.data.data.match.myStrength,88);assert.equal(res.data.data.match.rivalStrength,undefined);assert.equal(JSON.stringify(res.data).includes('private-twelve'),false);}finally{globalThis.fetch=old;}
});

test('tela real Tobol preserva oito nomes e posições não lidas como NI',()=>{const lines=JSON.parse(readFileSync(new URL('./fixtures/tobol-native-overlay.json',import.meta.url)));const rows=parseSquadOverlay(lines,2448,1080);assert.equal(rows.length,8);assert.equal(rows[0].name,'Koundé');assert.equal(rows[0].age,27);assert.equal(rows[0].position,'NI');assert.equal(rows[0].strength,null);assert.equal(rows[7].name,'Busurmanov');});
test('cadeado no relatório confirma treino secreto e não revela formação oculta',()=>{
 const locked=normalizeExtraction({match:{rivalName:'Ludogorets Razgrad',secretTraining:'Não',rivalFormation:'NI'},meta:{rivalReportLocked:true}},'match','Groq visual');
 assert.equal(locked.match.secretTraining,'Sim');assert.equal(locked.match.rivalFormation,undefined);
 const previous=normalizeExtraction({match:{secretTraining:'Não'}},'match');
 const combined=fuseExtraction(previous,locked);assert.equal(combined.match.secretTraining,'Sim');assert.ok(combined.conflicts.some(c=>c.field==='match.secretTraining'));
 const unknown=normalizeExtraction({match:{},meta:{rivalReportLocked:false}},'match');assert.equal(unknown.match.secretTraining,undefined);
});
