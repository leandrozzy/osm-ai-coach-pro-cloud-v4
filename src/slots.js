import {normalize} from './utils.js';
import {isRivalNickname} from './parser-match.js';
export function rivalHuman(nickname,competitionType='Liga normal',ownUsername=''){
 // Em Batalha o rival é sempre humano por regra do jogo/fluxo do Coach.
 if(normalize(competitionType).includes('batalha')||normalize(competitionType).includes('battle'))return true;
 // Competition does not identify a manager. A missing OCR nickname remains
 // unknown; CPU requires a visible empty manager line or an explicit CPU label.
 const n=normalize(nickname).replace(/^@/,'');if(!n||n==='ni')return null;
 const own=normalize(ownUsername).replace(/^@/,'');if(own&&n===own)return null;
 if(['cpu','computador','computer','bot'].includes(n))return false;
 return isRivalNickname(nickname,{username:ownUsername})?true:null;
}
