import {load,save} from './storage.js';
let state=load();const subs=new Set();
export const getState=()=>state;
export const getSlot=()=>state.slots[state.activeSlot-1];
export function setState(fn){const next=typeof fn==='function'?fn(structuredClone(state)):fn;save(next);state=next;subs.forEach(f=>f(state));}
export const subscribe=fn=>(subs.add(fn),()=>subs.delete(fn));
export function updateSlot(mutator,id=state.activeSlot){setState(s=>{mutator(s.slots[id-1],s);s.slots[id-1].updatedAt=new Date().toISOString();return s;});}
export function setActiveSlot(n){if(![1,2,3,4].includes(n))return;setState(s=>(s.activeSlot=n,s));}

