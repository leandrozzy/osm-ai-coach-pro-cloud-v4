import {normalize} from './utils.js';

export function rivalHuman(nickname, competitionType='Liga normal', myUser='leandrozzy'){
  if (normalize(competitionType).includes('batalha')) return true;
  const n=normalize(nickname);
  if (!n) return null; // ausência de nickname = desconhecido, nunca CPU por suposição
  return n!==normalize(myUser);
}
