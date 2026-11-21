#!/usr/bin/env node
'use strict';
/**
 * End-to-end economy test against the real Supabase project (uses the two test accounts in .env).
 * Run AFTER applying supabase/migrations/20261122000000_economy_quests_shop_payments.sql:
 *   node tools/economy-e2e.js
 * Payments use SePay-shaped webhooks sent to a local server instance with a local test key —
 * no real bank transfer happens.
 */
require('../src/config');
const crypto = require('crypto');
const { createServer } = require('../src/index');
const baseCfg = require('../src/config');

const url = process.env.SUPABASE_URL, pk = process.env.SUPABASE_PUBLISHABLE_KEY, sk = process.env.SUPABASE_SECRET_KEY;
const svc = { apikey: sk, Authorization: 'Bearer ' + sk, 'Content-Type': 'application/json' };
let pass = 0, fail = 0;
const check = (name, ok, extra) => { ok ? pass++ : fail++; console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  ' + extra : '')); };

async function login(i) {
  const r = await fetch(url + '/auth/v1/token?grant_type=password', { method: 'POST', headers: { apikey: pk, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: process.env[`TEST_USER${i}_EMAIL`], password: process.env[`TEST_USER${i}_PASSWORD`] }) });
  const b = await r.json();
  if (!r.ok) throw new Error('login failed');
  return { id: b.user.id, token: b.access_token, h: { apikey: pk, Authorization: 'Bearer ' + b.access_token, 'Content-Type': 'application/json' } };
}
async function rpc(h, fn, args) {
  const r = await fetch(`${url}/rest/v1/rpc/${fn}`, { method: 'POST', headers: h, body: JSON.stringify(args || {}) });
  const t = await r.text();
  let b; try { b = JSON.parse(t); } catch (e) { b = t; }
  return { ok: r.ok, status: r.status, body: b, msg: typeof b === 'object' && b ? (b.message || '') : String(b) };
}
async function get(h, q) { return (await fetch(`${url}/rest/v1/${q}`, { headers: h })).json(); }
async function wallet(u) { return (await get(u.h, `wallets?select=gold,gems&player_id=eq.${u.id}`))[0]; }

(async () => {
  const u = await login(1);

  // ---- account bootstrap (idempotent)
  await rpc(u.h, 'init_my_account');
  await rpc(u.h, 'init_my_account');
  const starter = await get(svc, `reward_grants?select=ref&ref=eq.starter:${u.id}`);
  check('starter kit granted exactly once', starter.length === 1);
  const potions0 = (await get(u.h, `player_inventory?select=quantity&player_id=eq.${u.id}&item_id=eq.health_potion`))[0];
  check('starter items in inventory', potions0 && potions0.quantity >= 3);

  // ---- daily login: once per day
  const d1 = await rpc(u.h, 'claim_daily_login'), d2 = await rpc(u.h, 'claim_daily_login');
  check('daily login cannot be claimed twice', d2.ok && d2.body.claimed === false, JSON.stringify([d1.body, d2.body]));

  // ---- client cannot write balances / inventory / progress
  const w0 = await wallet(u);
  const hack1 = await fetch(`${url}/rest/v1/wallets?player_id=eq.${u.id}`, { method: 'PATCH', headers: u.h, body: '{"gold":999999,"gems":999999}' });
  const hack2 = await fetch(`${url}/rest/v1/player_inventory`, { method: 'POST', headers: u.h, body: JSON.stringify({ player_id: u.id, item_id: 'skin_kaze_gold', quantity: 1 }) });
  const hack3 = await rpc(u.h, 'grant_reward', { p_player: u.id, p_ref: 'hack', p_source: 'hack', p_reward: { gems: 99999 } });
  const hack4 = await rpc(u.h, 'record_player_match', { p_event: { event_id: 'hack', player_id: u.id, kills: 999, won: true } });
  const hack5 = await rpc(u.h, 'create_payment_order', { p_player: u.id, p_product: 'gems_hero', p_provider: 'sepay', p_ttl_minutes: 15 });
  const hack6 = await rpc(u.h, 'fulfill_payment', { p: { provider: 'sepay', txn_id: 'x', amount: 1, content: 'SDAAAAAAAA', incoming: true } });
  const w1 = await wallet(u);
  check('client cannot PATCH wallet', !hack1.ok && w1.gold === w0.gold && w1.gems === w0.gems, 'status ' + hack1.status);
  check('client cannot INSERT inventory', !hack2.ok, 'status ' + hack2.status);
  check('client cannot call grant_reward / record_player_match / payment functions',
    !hack3.ok && !hack4.ok && !hack5.ok && !hack6.ok, [hack3.status, hack4.status, hack5.status, hack6.status].join(','));

  // ---- shop with Gold
  const reqId = crypto.randomUUID();
  const b1 = await rpc(u.h, 'buy_product', { p_product: 'p_health_potion', p_request_id: reqId });
  const b2 = await rpc(u.h, 'buy_product', { p_product: 'p_health_potion', p_request_id: reqId });
  const w2 = await wallet(u);
  check('buy with Gold deducts price once (retry with same request id is free)', b1.ok && b2.ok && b2.body.duplicate === true && w2.gold === w1.gold - 120,
    `${w1.gold} -> ${w2.gold}`);
  const poor = await rpc(u.h, 'buy_product', { p_product: 'p_skin_kaze_gold', p_request_id: crypto.randomUUID() });
  check('cannot buy without enough Gems', !poor.ok && /INSUFFICIENT_FUNDS/.test(poor.msg), poor.msg);
  const gemsPackViaClient = await rpc(u.h, 'buy_product', { p_product: 'gems_hero', p_request_id: crypto.randomUUID() });
  check('Gems packs cannot be "bought" without paying', !gemsPackViaClient.ok, gemsPackViaClient.msg);

  // ---- equip rules
  const eq1 = await rpc(u.h, 'equip_item', { p_item: 'skin_kaze_gold', p_character: 'kaze' });
  check('cannot equip an item you do not own', !eq1.ok && /NOT_OWNED/.test(eq1.msg), eq1.msg);
  const eq2 = await rpc(u.h, 'equip_item', { p_item: 'nameplate_rookie', p_character: 'kaze' });
  check('equip an owned item', eq2.ok, eq2.msg);
  const use1 = await rpc(u.h, 'use_item', { p_item: 'health_potion', p_request_id: crypto.randomUUID() });
  check('use a consumable decrements it', use1.ok && use1.body.effect && use1.body.effect.training, use1.msg);
  const use2 = await rpc(u.h, 'use_item', { p_item: 'skin_kaze_gold', p_request_id: crypto.randomUUID() });
  check('cannot use an item that is not usable/owned', !use2.ok, use2.msg);

  // ---- quests from server events (idempotent by event id)
  const ev = { event_id: 'e2e-' + crypto.randomUUID() + ':' + u.id, player_id: u.id, mode: 'tdm', won: true, kills: 6, assists: 2, damage: 5200, rating: 1000 };
  const before = await get(u.h, `player_quest_progress?select=quest_id,metric,progress&player_id=eq.${u.id}&quest_id=eq.d_kill5`);
  const r1 = await rpc(svc, 'record_player_match', { p_event: ev });
  const r2 = await rpc(svc, 'record_player_match', { p_event: ev });
  const after = await get(u.h, `player_quest_progress?select=quest_id,metric,progress&player_id=eq.${u.id}&quest_id=eq.d_kill5`);
  const prog = a => (a[0] ? a[0].progress : 0);
  check('match event counted once (replay ignored)', r1.ok && r2.ok && r2.body.duplicate === true && prog(after) === prog(before) + 6,
    `${prog(before)} -> ${prog(after)}`);
  const c1 = await rpc(u.h, 'claim_quest', { p_quest: 'd_kill5' });
  const c2 = await rpc(u.h, 'claim_quest', { p_quest: 'd_kill5' });
  check('quest reward claimed once', (c1.ok || /ALREADY_CLAIMED/.test(c1.msg)) && !c2.ok && /ALREADY_CLAIMED/.test(c2.msg), c2.msg);
  const c3 = await rpc(u.h, 'claim_quest', { p_quest: 'w_win15' });
  check('incomplete quest cannot be claimed', !c3.ok && /QUEST_NOT_COMPLETE/.test(c3.msg), c3.msg);
  const chest = await rpc(u.h, 'claim_quest', { p_quest: 'd_dmg5000' });
  const chestRow = (await get(u.h, `player_inventory?select=quantity&player_id=eq.${u.id}&item_id=eq.chest_basic`))[0];
  check('item reward (chest) delivered to inventory', (chest.ok || /ALREADY_CLAIMED/.test(chest.msg)) && chestRow && chestRow.quantity >= 1);

  // ---- payments through the real Node server (SePay-shaped webhooks, local test key)
  const KEY = 'e2e-local-webhook-key-' + crypto.randomBytes(6).toString('hex');
  const server = createServer(Object.assign({}, baseCfg, {
    port: 0, host: '127.0.0.1', serverId: 'e2e-test', paymentsEnabled: true, paymentProvider: 'sepay', distributionChannel: 'direct',
    sepayBank: 'MBBank', sepayAccountNumber: '0000E2E000', sepayAccountName: 'E2E TEST', sepayWebhookAuth: 'apikey', sepayWebhookApiKey: KEY
  }));
  const port = await server.start();
  const base = `http://127.0.0.1:${port}`;
  const order = async () => (await (await fetch(base + '/api/shop/orders', { method: 'POST', headers: { Authorization: 'Bearer ' + u.token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ productId: 'gems_warrior', amount: 1 }) })).json()).order;
  const hook = (body, key = KEY) => fetch(base + '/api/payments/sepay/webhook', { method: 'POST', headers: { Authorization: 'Apikey ' + key, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const status = async id => (await (await fetch(base + '/api/shop/orders/' + id, { headers: { Authorization: 'Bearer ' + u.token } })).json()).order.status;
  const tx = (o, over) => Object.assign({ id: crypto.randomInt(1e9, 2e9), gateway: 'MBBank', transactionDate: '2026-11-22 10:00:00', accountNumber: '0000E2E000', subAccount: '',
    code: null, content: o.code + ' nap gems', transferType: 'in', description: '', transferAmount: o.amountVnd, accumulated: 0, referenceCode: 'SBE2E' }, over || {});

  try {
    const o1 = await order();
    check('order price comes from the database (client sent amount=1)', o1 && o1.amountVnd === 50000 && o1.gems === 300, o1 && o1.code);
    const gems0 = (await wallet(u)).gems;
    const forged = await hook(tx(o1), 'wrong-key');
    check('forged webhook rejected (401), order still PENDING', forged.status === 401 && (await status(o1.id)) === 'PENDING');
    const low = await hook(tx(o1, { transferAmount: 5000 }));
    check('wrong amount → NEEDS_REVIEW, no Gems', low.status === 200 && (await status(o1.id)) === 'NEEDS_REVIEW' && (await wallet(u)).gems === gems0);

    const o2 = await order();
    const t2 = tx(o2);
    const ok1 = await hook(t2);
    const ack = await ok1.json();
    const gems1 = (await wallet(u)).gems;
    check('valid payment → PAID, +300 Gems, ack {"success":true}', ack.success === true && (await status(o2.id)) === 'PAID' && gems1 === gems0 + 300, `${gems0} -> ${gems1}`);
    await hook(t2); await hook(t2);
    check('same webhook resent twice → no extra Gems', (await wallet(u)).gems === gems1);
    await hook(tx(o2, { id: crypto.randomInt(1e9, 2e9) }));
    check('second transfer for an already-paid order → no extra Gems (kept for review)', (await wallet(u)).gems === gems1);

    const o3 = await order();
    await fetch(`${url}/rest/v1/payment_orders?id=eq.${o3.id}`, { method: 'PATCH', headers: svc, body: JSON.stringify({ expires_at: new Date(Date.now() - 60000).toISOString() }) });
    await hook(tx(o3));
    check('payment after expiry → not granted automatically', (await wallet(u)).gems === gems1 && ['NEEDS_REVIEW', 'EXPIRED'].includes(await status(o3.id)));

    await hook(tx({ code: 'random text', amountVnd: 50000 }, { content: 'chuyen tien khong ma' }));
    const unmatched = await get(svc, 'payment_transactions?select=result&result=eq.no_order&order=id.desc&limit=1');
    check('transfer with wrong content recorded for reconciliation', unmatched.length === 1);
    await hook(tx({ code: 'x', amountVnd: 50000 }, { transferType: 'out' }));
    check('outgoing transactions never grant anything', (await wallet(u)).gems === gems1);

    const ledger = await get(u.h, `currency_ledger?select=currency,delta,ref&player_id=eq.${u.id}&currency=eq.gems`);
    check('every Gems change has a unique ledger row', ledger.length === new Set(ledger.map(l => l.ref)).size && ledger.some(l => l.ref === 'order:' + o2.id));
    const other = await login(2);
    const peek = await get(other.h, `payment_orders?select=id&player_id=eq.${u.id}`);
    check('RLS: other players cannot see your orders', Array.isArray(peek) && peek.length === 0);
  } finally {
    await server.stop();
  }
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
