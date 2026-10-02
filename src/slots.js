import {normalize} from './utils.js';
export function rivalHuman(nickname, competitionType='Liga normal', myUser='leandrozzy'){
  if (normalize(competitionType).includes('batalha')) return true;
  if (!nickname || !normalize(nickname)) return false;
  return normalize(nickname)!==normalize(myUser);
}
