import {load,save} from './storage.js';
let state=load(); const subs=new Set();
export const getState=()=>state;
export const getSlot=()=>state.slots[state.activeSlot-1];
export function setState(fn){state=typeof fn==='function'?fn(structuredClone(state)):fn; save(state); subs.forEach(f=>f(state));}
export const subscribe=fn=>(subs.add(fn),()=>subs.delete(fn));
export function patchSlot(patch){setState(s=>{const i=s.activeSlot-1; s.slots[i]={...s.slots[i],...patch,updatedAt:new Date().toISOString()}; return s;});}
export function updateSlot(mutator){setState(s=>{const i=s.activeSlot-1; mutator(s.slots[i],s); s.slots[i].updatedAt=new Date().toISOString(); return s;});}
export function setActiveSlot(n){setState(s=>(s.activeSlot=n,s));}
