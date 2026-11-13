import test from 'node:test';
import assert from 'node:assert/strict';
import { validateCoinPackages, validateMultipliers, validateCosmetic, validateConfigValue } from './adminControl.js';

const goodPkg = { id: 'pkg_starter', title: 'Starter Glow', coins: 70, bonusCoins: 10, priceKes: 50, priceUsd: 0.99 };

test('accepts a valid package list', () => {
  assert.equal(validateCoinPackages([goodPkg]), null);
});

test('rejects negative and zero prices', () => {
  assert.match(validateCoinPackages([{ ...goodPkg, priceKes: -5 }]), /cannot be negative/);
  assert.match(validateCoinPackages([{ ...goodPkg, priceKes: 0, priceUsd: 0 }]), /needs a price/);
});

test('rejects duplicate and malformed ids', () => {
  assert.match(validateCoinPackages([goodPkg, goodPkg]), /used twice/);
  assert.match(validateCoinPackages([{ ...goodPkg, id: 'Pkg Bad!' }]), /lowercase id/);
});

test('rejects non-integer or out-of-range coin grants', () => {
  assert.match(validateCoinPackages([{ ...goodPkg, coins: 2.5 }]), /whole coins/);
  assert.match(validateCoinPackages([{ ...goodPkg, coins: 0 }]), /whole coins/);
  assert.match(validateCoinPackages([{ ...goodPkg, coins: 2000000 }]), /whole coins/);
});

test('rejects empty and oversized lists', () => {
  assert.match(validateCoinPackages([]), /1 to 20/);
  assert.match(validateCoinPackages(Array.from({ length: 21 }, (_, i) => ({ ...goodPkg, id: `pkg_${i}` }))), /1 to 20/);
});

test('multipliers must be numbers within 0-100', () => {
  assert.equal(validateMultipliers({ free: 0.5, premium: 2.5 }), null);
  assert.match(validateMultipliers({ free: -1 }), /between 0 and 100/);
  assert.match(validateMultipliers({ free: 'lots' }), /between 0 and 100/);
});

test('cosmetics validate id, kind, price and rarity', () => {
  assert.equal(validateCosmetic({ item_id: 'frame_pearl', kind: 'FRAME', name: 'Pearl', price_coins: 350, rarity: 'CLASSIC' }), null);
  assert.match(validateCosmetic({ item_id: 'Bad Id', kind: 'FRAME', name: 'Pearl', price_coins: 1, rarity: 'CLASSIC' }), /lowercase/);
  assert.match(validateCosmetic({ item_id: 'frame_pearl', kind: 'FRAME', name: 'Pearl', price_coins: -1, rarity: 'CLASSIC' }), /whole number/);
  assert.match(validateCosmetic({ item_id: 'frame_pearl', kind: 'FRAME', name: 'Pearl', price_coins: 1, rarity: 'MYTHIC' }), /Rarity/);
});

test('config gate only allows whitelisted keys', () => {
  assert.match(validateConfigValue('payment_secrets', {}), /not editable/);
  assert.equal(validateConfigValue('coin_packages', [goodPkg]), null);
});
