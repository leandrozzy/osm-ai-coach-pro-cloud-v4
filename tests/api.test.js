import test from 'node:test';
import assert from 'node:assert/strict';
import handler from '../api/ai.js';
import {generateTactic} from '../src/tactics-engine.js';
function response(){return {code:0,data:null,setHeader(){},status(n){this.code=n;return this;},json(d){this.data=d;return this;}};}
test('API rejeita tarefa desconhecida e JSON inválido',async()=>{for(const body of ['{',{task:'anything'}]){const res=response();await handler({method:'POST',body},res);assert.equal(res.code,400);}});
test('chave de sessão é usada sem aparecer na resposta',async()=>{const old=globalThis.fetch;let auth;globalThis.fetch=async(url,opts)=>{auth=opts.headers.authorization;return {ok:true,json:async()=>({choices:[{message:{content:JSON.stringify({tactic:generateTactic({})})}}]})};};try{const res=response();await handler({method:'POST',body:{task:'tactic',sessionKey:'test-session-secret',payload:{context:{}}}},res);assert.equal(res.code,200);assert.equal(auth,'Bearer test-session-secret');assert.equal(JSON.stringify(res.data).includes('test-session-secret'),false);}finally{globalThis.fetch=old;}});
test('erro do provedor não revela detalhes ou chave',async()=>{const old=globalThis.fetch;globalThis.fetch=async()=>({ok:false,status:429});try{const res=response();await handler({method:'POST',body:{task:'tactic',sessionKey:'private',payload:{context:{}}}},res);assert.equal(res.code,503);assert.equal(JSON.stringify(res.data).includes('private'),false);}finally{globalThis.fetch=old;}});
