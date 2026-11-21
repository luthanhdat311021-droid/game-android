'use strict';
const test = require('node:test');
const assert = require('node:assert');
const http = require('http');
const crypto = require('crypto');
const { EconomyApi } = require('../src/api/EconomyApi');
const { selectProvider, SePayProvider } = require('../src/payments/PaymentProviders');

process.env.LOG_LEVEL = 'silent';

const BASE = {
  supabaseUrl: 'https://x.supabase.co', supabaseAnonKey: 'pk', production: false,
  distributionChannel: 'direct', paymentProvider: 'sepay', paymentsEnabled: true, orderTtlMinutes: 15,
  sepayBank: 'MBBank', sepayAccountNumber: '0123456789', sepayAccountName: 'SHADOW DUEL',
  sepayWebhookAuth: 'apikey', sepayWebhookApiKey: 'test-key-123', sepayWebhookHmacSecret: 'hmac-secret'
};

/** Records every call instead of talking to Supabase. */
function fakeSupabase() {
  const calls = [];
  return {
    calls, enabled: true,
    async rpc(fn, args) {
      calls.push({ fn, args });
      if (fn === 'create_payment_order') {
        return { id: '11111111-1111-1111-1111-111111111111', code: 'SDABCD1234', player_id: 'u1', product_id: args.p_product,
          amount_vnd: 50000, gems: 300, provider: args.p_provider, status: 'PENDING', expires_at: new Date(Date.now() + 9e5).toISOString() };
      }
      if (fn === 'fulfill_payment') return { result: 'paid' };
      return {};
    },
    async request() { return []; },
    async verifyToken(t) { return t === 'good-jwt' ? { id: 'u1', username: 'tester' } : null; }
  };
}

async function serve(cfg, sb) {
  const api = new EconomyApi(Object.assign({}, BASE, cfg), sb);
  const srv = http.createServer(async (req, res) => { if (!(await api.handle(req, res))) { res.writeHead(404); res.end(); } });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${srv.address().port}`;
  return { url, close: () => new Promise(r => srv.close(r)) };
}

const payload = (over = {}) => Object.assign({
  id: 92704, gateway: 'MBBank', transactionDate: '2026-11-22 10:00:00', accountNumber: '0123456789', subAccount: '',
  code: null, content: 'SDABCD1234 nap gems', transferType: 'in', description: 'X chuyen tien', transferAmount: 50000,
  accumulated: 0, referenceCode: 'FT26000000'
}, over);

test('SePay webhook: rejects missing or wrong API key without touching the database', async () => {
  const sb = fakeSupabase();
  const s = await serve({}, sb);
  try {
    let r = await fetch(s.url + '/api/payments/sepay/webhook', { method: 'POST', body: JSON.stringify(payload()) });
    assert.equal(r.status, 401);
    r = await fetch(s.url + '/api/payments/sepay/webhook', { method: 'POST', headers: { Authorization: 'Apikey wrong' }, body: JSON.stringify(payload()) });
    assert.equal(r.status, 401);
    assert.equal(sb.calls.length, 0, 'no rpc for unauthenticated webhooks');
  } finally { await s.close(); }
});

test('SePay webhook: valid API key → fulfill_payment with normalized data and {"success": true}', async () => {
  const sb = fakeSupabase();
  const s = await serve({}, sb);
  try {
    const r = await fetch(s.url + '/api/payments/sepay/webhook', { method: 'POST', headers: { Authorization: 'Apikey test-key-123', 'Content-Type': 'application/json' }, body: JSON.stringify(payload()) });
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), { success: true });
    const call = sb.calls.find(c => c.fn === 'fulfill_payment');
    assert.equal(call.args.p.txn_id, '92704');
    assert.equal(call.args.p.amount, 50000);
    assert.equal(call.args.p.incoming, true);
    assert.match(call.args.p.content, /SDABCD1234/);
  } finally { await s.close(); }
});

test('SePay webhook: money to another account is never treated as incoming payment', async () => {
  const sb = fakeSupabase();
  const s = await serve({}, sb);
  try {
    await fetch(s.url + '/api/payments/sepay/webhook', { method: 'POST', headers: { Authorization: 'Apikey test-key-123' }, body: JSON.stringify(payload({ accountNumber: '999' })) });
    assert.equal(sb.calls[0].args.p.incoming, false);
  } finally { await s.close(); }
});

test('SePay webhook: malformed payloads are refused', async () => {
  const sb = fakeSupabase();
  const s = await serve({}, sb);
  try {
    const h = { Authorization: 'Apikey test-key-123' };
    assert.equal((await fetch(s.url + '/api/payments/sepay/webhook', { method: 'POST', headers: h, body: 'not json' })).status, 400);
    assert.equal((await fetch(s.url + '/api/payments/sepay/webhook', { method: 'POST', headers: h, body: JSON.stringify(payload({ transferAmount: -5 })) })).status, 400);
    assert.equal(sb.calls.length, 0);
  } finally { await s.close(); }
});

test('HMAC mode: correct signature accepted, tampered body / stale timestamp rejected', () => {
  const p = new SePayProvider(Object.assign({}, BASE, { sepayWebhookAuth: 'hmac' }));
  const body = JSON.stringify(payload());
  const now = 1_800_000_000;
  const sig = 'sha256=' + crypto.createHmac('sha256', 'hmac-secret').update(now + '.' + body).digest('hex');
  assert.equal(p.verify({ 'x-sepay-signature': sig, 'x-sepay-timestamp': String(now) }, body, now), true);
  assert.equal(p.verify({ 'x-sepay-signature': sig, 'x-sepay-timestamp': String(now) }, body.replace('50000', '5000000'), now), false);
  assert.equal(p.verify({ 'x-sepay-signature': sig, 'x-sepay-timestamp': String(now) }, body, now + 301), false);
  assert.equal(p.verify({}, body, now), false);
});

test('creating an order needs a valid JWT and ignores any client-sent price', async () => {
  const sb = fakeSupabase();
  const s = await serve({}, sb);
  try {
    let r = await fetch(s.url + '/api/shop/orders', { method: 'POST', body: JSON.stringify({ productId: 'gems_warrior' }) });
    assert.equal(r.status, 401);
    r = await fetch(s.url + '/api/shop/orders', { method: 'POST', headers: { Authorization: 'Bearer good-jwt' },
      body: JSON.stringify({ productId: 'gems_warrior', amount: 1, gems: 999999 }) });
    assert.equal(r.status, 201);
    const b = await r.json();
    const call = sb.calls.find(c => c.fn === 'create_payment_order');
    assert.deepEqual(Object.keys(call.args).sort(), ['p_player', 'p_product', 'p_provider', 'p_ttl_minutes']);
    assert.equal(call.args.p_player, 'u1', 'player id comes from the verified token');
    assert.equal(b.payment.transferContent, 'SDABCD1234');
    assert.match(b.payment.qrUrl, /^https:\/\/vietqr\.app\/img\?acc=0123456789&bank=MBBank&amount=50000&des=SDABCD1234/);
  } finally { await s.close(); }
});

test('distribution rules: no SePay for Google Play builds, no mock in production, nothing when disabled', () => {
  assert.equal(selectProvider(Object.assign({}, BASE, { distributionChannel: 'google_play' })).reason, 'PROVIDER_NOT_ALLOWED_FOR_CHANNEL');
  assert.equal(selectProvider(Object.assign({}, BASE, { paymentProvider: 'mock', production: true })).reason, 'MOCK_NOT_ALLOWED_IN_PRODUCTION');
  assert.equal(selectProvider(Object.assign({}, BASE, { paymentsEnabled: false })).reason, 'PAYMENTS_DISABLED');
  assert.equal(selectProvider(Object.assign({}, BASE, { sepayWebhookApiKey: '' })).reason, 'PROVIDER_NOT_CONFIGURED');
  assert.equal(selectProvider(Object.assign({}, BASE, { distributionChannel: 'google_play', paymentProvider: 'google_play' })).reason, 'PROVIDER_NOT_CONFIGURED');
  assert.equal(selectProvider(BASE).provider.id, 'sepay');
});

test('admin API refuses non-admins (role read from DB, not the client)', async () => {
  const sb = fakeSupabase();
  const s = await serve({}, sb);
  try {
    const r = await fetch(s.url + '/api/admin/orders', { headers: { Authorization: 'Bearer good-jwt', 'X-Role': 'admin' } });
    assert.equal(r.status, 403);
  } finally { await s.close(); }
});
