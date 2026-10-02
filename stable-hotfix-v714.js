(function(){
'use strict';
var VERSION='7.14.0';

function present(v){
  return !(v===null||v===undefined||v===''||v==='NI');
}
function readPath(obj,path){
  return String(path).split('.').reduce(function(a,k){return a==null?undefined:a[k]},obj);
}

// V7.14: only truly indispensable values block generation.
// Opponent tactical details remain valuable and visible as NI, but they no longer
// prevent the user from obtaining a tactic. If secret training is active, rival
// strength and hidden tactical data are legitimately unavailable.
function stableMissingRequired(s){
  var req=['teamName','opponent.teamName','match.venue','match.refereeColor','myTeam.overall'];
  if(!(s&&s.opponent&&s.opponent.secretTraining===true)) req.push('opponent.overall');
  return req.filter(function(path){
    var v=readPath(s,path);
    return !(present(v)||typeof v==='boolean');
  });
}

try{
  window.missingRequired=stableMissingRequired;
  // In classic scripts the original function binding is normally writable.
  try{ missingRequired=stableMissingRequired; }catch(_){}
}catch(_){}

// After every render/analyze, recalculate the visible status using the stable rule.
function refresh(){
  try{
    if(typeof selectedSlot!=='function')return;
    var s=selectedSlot();
    if(!s)return;
    if(typeof calcQuality==='function')calcQuality(s);
    if(typeof renderCoverage==='function')renderCoverage(s);
    if(typeof renderAnalysisSummary==='function')renderAnalysisSummary(s);
    if(typeof renderPregame==='function')renderPregame();
  }catch(_){}
}

window.OSM_V714_STABLE=true;
window.OSM_V714_MISSING_REQUIRED=stableMissingRequired;
setTimeout(refresh,250);
try{console.info('[OSM] V7.14 operational rules active')}catch(_){}
})();