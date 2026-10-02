import test from 'node:test';
import assert from 'node:assert/strict';
import {generateTactic,generateStrong433,validateTactic} from '../src/tactics-engine.js';
const settings={refereeMap:{Vermelho:'Cauteloso',Verde:'Agressivo'}};
test('mais fraco recebe formação defensiva',()=>assert.equal(generateTactic({myStrength:70,rivalStrength:90,referee:'Vermelho'},settings).formation,'5-4-1 A'));
test('árbitro vermelho e desconhecido são cautelosos',()=>{assert.equal(generateTactic({referee:'Vermelho'},settings).tackling,'Cauteloso');assert.equal(generateTactic({},settings).tackling,'Cauteloso');});
test('vantagem real 13 habilita tática forte sem forçar 433 normal',()=>{const match={myStrength:100,rivalStrength:87};assert.equal(generateTactic(match).strongAvailable,true);assert.equal(generateTactic(match).formation.startsWith('4-3-3'),false);assert.ok(generateStrong433(match).formation.startsWith('4-3-3'));assert.throws(()=>generateStrong433({myStrength:'NI',rivalStrength:50}));});
test('contexto desconhecido fica explícito',()=>{const t=generateTactic({});assert.ok(t.provisional);assert.match(t.reason,/Rival: NI/);assert.match(t.reason,/Local: NI/);});
test('campo e setores influenciam recomendação',()=>{const m={myStrength:90,rivalStrength:90};assert.notEqual(generateTactic(m).formation,generateTactic({...m,myMID:70,rivalMID:100}).formation);assert.notEqual(generateTactic(m).pressure,generateTactic({...m,secretTraining:'Sim'}).pressure);});
test('histórico compartilhado influencia após amostra mínima',()=>{const m={myStrength:90,rivalStrength:90};const l={weights:{'4-5-1':{games:12,wins:12,losses:0}}};assert.equal(generateTactic(m,{},l).formation,'4-5-1');});
test('tática completa contém sliders válidos',()=>{assert.ok(validateTactic(generateTactic({})));assert.equal(validateTactic({...generateTactic({}),pressure:101}),false);});

