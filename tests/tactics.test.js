import test from 'node:test';import assert from 'node:assert/strict';import {generateTactic} from '../src/tactics-engine.js';
const settings={refereeMap:{Vermelho:'Cauteloso',Verde:'Agressivo'}};
test('mais fraco não força 433',()=>assert.equal(generateTactic({myStrength:70,rivalStrength:90,referee:'Vermelho'},settings).formation,'5-4-1 A'));
test('árbitro vermelho cauteloso',()=>assert.equal(generateTactic({myStrength:90,rivalStrength:90,referee:'Vermelho'},settings).tackling,'Cauteloso'));
test('+13 libera forte',()=>assert.equal(generateTactic({myStrength:100,rivalStrength:87,referee:'Amarelo'},settings).strongAvailable,true));
