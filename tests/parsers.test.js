import test from 'node:test';
import assert from 'node:assert/strict';
import {parseReferee,parseFormation,parsePlan,parseMarking,parseOffside,parseMatchText,parseStrengthPair} from '../src/parser-match.js';
import {dedupePlayers,parseSquadText} from '../src/parser-squad.js';
import {parseCalendarText,mergeCalendar} from '../src/parser-calendar.js';
import {mergeBetter,validateMatch} from '../src/validator.js';
import {rivalHuman} from '../src/slots.js';
test('cor precisa estar associada ao árbitro',()=>{assert.equal(parseReferee('Camisa Vermelho\nÁrbitro Azul'),'Azul');assert.equal(parseReferee('Camisa laranja'),'NI');});
test('formação mantém A/B e ignora placar',()=>{assert.equal(parseFormation('Rival 4-3-3 B'),'4-3-3 B');assert.equal(parseFormation('Resultado 4-3-3'),'NI');});
test('plano, marcação e impedimento explícitos',()=>{assert.equal(parsePlan('Jogar pelas alas'),'Jogar pelas alas');assert.equal(parseMarking('Marcação à zona'),'À zona');assert.equal(parseOffside('Impedimento: Não'),'Não');});
test('treino secreto não permite adivinhar força',()=>{const r=parseMatchText('Treino secreto: Sim\nÁrbitro Amarelo');assert.equal(r.rivalStrength,'NI');assert.ok(validateMatch(r).hiddenByGame.length);});
test('números sem associação não viram força',()=>{assert.deepEqual(parseStrengthPair('90 x 70'),{my:null,rival:null});assert.deepEqual(parseStrengthPair('Minha força: 90\nForça rival: 70'),{my:90,rival:70});});
test('preserva dados válidos e estados falsos',()=>{assert.equal(mergeBetter({referee:'Vermelho'},{referee:'NI'}).referee,'Vermelho');assert.equal(mergeBetter({human:true},{human:false}).human,false);});
test('ausência de nickname nunca significa CPU',()=>{assert.equal(rivalHuman('fulano'),true);assert.equal(rivalHuman(''),null);assert.equal(rivalHuman('NI'),null);assert.equal(rivalHuman('CPU'),false);assert.equal(rivalHuman('CPU','Batalha'),true);});
test('elenco atualiza estados sem perder valor válido',()=>{const p=dedupePlayers([{name:'A',position:'ATA',strength:90,value:'10M',training:true},{name:'A',position:'ATA',strength:88,value:'NI',training:false}])[0];assert.equal(p.strength,88);assert.equal(p.value,'10M');assert.equal(p.training,false);});
test('OCR não confunde idade e força',()=>{assert.equal(parseSquadText('Jogador ATA 24 88')[0].strength,null);});
test('calendário não inventa ano, casa ou resultado',()=>{assert.equal(parseCalendarText('02/10 Rival A').length,0);const r=parseCalendarText('02/10/2026 12:00 Rival: A')[0];assert.equal(r.home,null);assert.equal(r.result,'NI');});
test('calendário ordena pelo tempo e preserva local',()=>{const rows=mergeCalendar([{date:'30/09/2026',time:'10:00',opponent:'A',home:true}],[{date:'01/10/2026',time:'10:00',opponent:'B'},{date:'30/09/2026',time:'10:00',opponent:'A',home:null}]);assert.equal(rows[0].opponent,'A');assert.equal(rows[0].home,true);});

