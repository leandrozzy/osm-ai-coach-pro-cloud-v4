import {resultMediaFields} from './result-reader.js';
const KEY='osm-ai-coach-pro:results:v1';
function read(){try{const value=JSON.parse(localStorage.getItem(KEY)||'{}');return value&&typeof value==='object'&&!Array.isArray(value)?value:{};}catch{return {};}}
export function getResultDraft(slot){const draft=read()[slot];return draft&&draft.slot===slot?draft:null;}
export function saveResultDraft(slot,reading){
 const data=read(),result={};
 for(const field of [...resultMediaFields,'calendarId','notes']){
  const value=reading.result?.[field];if(['string','number'].includes(typeof value))result[field]=typeof value==='string'?value.slice(0,4000):value;
 }
 const draft={slot,result,fieldSources:reading.fieldSources||{},warnings:(reading.warnings||[]).slice(0,40),source:reading.source||'',elapsed:reading.elapsed||0,recognizedFields:reading.recognizedFields||0,previews:(reading.previews||[]).filter(p=>typeof p.url==='string'&&p.url.length<=180000&&/^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(p.url)).slice(0,6),at:new Date().toISOString()};
 data[slot]=draft;
 try{localStorage.setItem(KEY,JSON.stringify(data));}catch{for(const item of Object.values(data))item.previews=[];localStorage.setItem(KEY,JSON.stringify(data));}
 return draft;
}
export function removeResultDraft(slot){const data=read();delete data[slot];localStorage.setItem(KEY,JSON.stringify(data));}
