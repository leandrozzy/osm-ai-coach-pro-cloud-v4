import {normalize} from './utils.js';
export function rivalHuman(nickname,competitionType='Liga normal'){
 if(normalize(competitionType).includes('batalha'))return true;
 const n=normalize(nickname);if(!n||n==='ni')return null;
 if(['cpu','computador','computer','bot'].includes(n))return false;
 return true;
}

