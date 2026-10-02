import {normalize} from './utils.js';
export function rivalHuman(nickname,competitionType='Liga normal',ownUsername=''){
 if(normalize(competitionType).includes('batalha'))return true;
 const n=normalize(nickname).replace(/^@/,'');if(!n||n==='ni')return null;
 const own=normalize(ownUsername).replace(/^@/,'');if(own&&n===own)return null;
 if(['cpu','computador','computer','bot'].includes(n))return false;
 return true;
}
