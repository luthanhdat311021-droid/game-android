'use strict';
/**
 * HTTP API for the economy (JSON):
 *   GET  /api/payments/config                      → which provider is active (no secrets)
 *   POST /api/shop/orders            (Bearer JWT)  → create a Gems order; price comes from shop_products
 *   GET  /api/shop/orders/:id        (Bearer JWT)  → order status (owner only)
 *   POST /api/payments/sepay/webhook               → SePay transaction notification (verified)
 *   POST /api/payments/mock/:id/pay  (Bearer JWT)  → dev-only simulation (provider = mock)
 *   /api/admin/...                   (Bearer JWT of a user listed in public.admins)
 *
 * Purchases with Gold/Gems, quest claims, equip and item use are Postgres functions that use
 * auth.uid() and are called directly by the app with the user's own token.
 */
const log = require('../log');
const { selectProvider, SePayProvider } = require('../payments/PaymentProviders');

const MAX_BODY = 64 * 1024;

class HttpError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

class EconomyApi {
  constructor(cfg, supabase) {
    this.cfg = cfg;
    this.sb = supabase;
    this.limits = new Map(); // key -> { count, reset }
    const sel = selectProvider(cfg);
    this.provider = sel.provider;
    this.providerReason = sel.reason;
    // the SePay webhook stays verifiable even if new orders are switched off
    this.sepay = new SePayProvider(cfg);
    this.expireTimer = null;
  }

  start() {
    if (!this.sb.enabled) return;
    this.expireTimer = setInterval(() => this.sb.rpc('expire_payment_orders', {}).catch(e => log.warn('expire orders', e.message)), 60000);
  }

  stop() { clearInterval(this.expireTimer); }

  /** Returns true when the request was handled. */
  async handle(req, res) {
    const url = new URL(req.url, 'http://x');
    if (!url.pathname.startsWith('/api/') && url.pathname !== '/admin') return false;
    try {
      if (url.pathname === '/admin' && req.method === 'GET') return this.adminPage(res), true;
      const out = await this.route(req, url);
      send(res, out.status || 200, out.body);
    } catch (e) {
      if (e instanceof HttpError) send(res, e.status, { error: e.code });
      else {
        log.error('api error', req.method, url.pathname, e.message);
        const code = /PRODUCT_NOT_AVAILABLE|TOO_MANY_PENDING_ORDERS|NOT_ADMIN|ORDER_NOT_FOUND|ALREADY_PAID|BAD_ACTION/.exec(e.message || '');
        send(res, code ? 400 : 500, { error: code ? code[0] : 'INTERNAL' });
      }
    }
    return true;
  }

  async route(req, url) {
    const p = url.pathname, m = req.method;
    if (p === '/api/payments/config' && m === 'GET') {
      return { body: { enabled: !!this.provider, provider: this.provider ? this.provider.id : null,
        reason: this.providerReason, channel: this.cfg.distributionChannel } };
    }
    if (p === '/api/payments/sepay/webhook' && m === 'POST') return this.sepayWebhook(req);
    if (!this.sb.enabled) throw new HttpError(503, 'SUPABASE_NOT_CONFIGURED');

    if (p === '/api/shop/orders' && m === 'POST') {
      const user = await this.auth(req);
      this.rateLimit('order:' + user.id, 10, 60000);
      if (!this.provider) throw new HttpError(403, this.providerReason || 'PAYMENTS_DISABLED');
      const body = await readJson(req);
      if (typeof body.productId !== 'string' || !/^[a-z0-9_]{2,48}$/.test(body.productId)) throw new HttpError(400, 'BAD_PRODUCT');
      // the client sends only the product id — amount and Gems come from the database
      const order = await this.sb.rpc('create_payment_order', {
        p_player: user.id, p_product: body.productId, p_provider: this.provider.id, p_ttl_minutes: this.cfg.orderTtlMinutes
      });
      return { status: 201, body: { order: publicOrder(order), payment: this.provider.instructions(order) } };
    }

    let match = /^\/api\/shop\/orders\/([0-9a-f-]{36})$/.exec(p);
    if (match && m === 'GET') {
      const user = await this.auth(req);
      this.rateLimit('status:' + user.id, 120, 60000);
      const order = await this.order(match[1]);
      if (!order || order.player_id !== user.id) throw new HttpError(404, 'ORDER_NOT_FOUND');
      const prov = order.provider === 'sepay' ? this.sepay : this.provider;
      return { body: { order: publicOrder(order), payment: order.status === 'PENDING' && prov ? prov.instructions(order) : null } };
    }

    match = /^\/api\/payments\/mock\/([0-9a-f-]{36})\/pay$/.exec(p);
    if (match && m === 'POST') {
      if (!this.provider || this.provider.id !== 'mock') throw new HttpError(404, 'NOT_FOUND');
      const user = await this.auth(req);
      const order = await this.order(match[1]);
      if (!order || order.player_id !== user.id || order.provider !== 'mock') throw new HttpError(404, 'ORDER_NOT_FOUND');
      const result = await this.sb.rpc('fulfill_payment', { p: {
        provider: 'mock', txn_id: 'mock-' + order.id, amount: order.amount_vnd, account: 'mock',
        content: order.code, reference: 'MOCK', incoming: true, raw: { mock: true } } });
      return { body: result };
    }

    if (p.startsWith('/api/admin/')) return this.admin(req, url);
    throw new HttpError(404, 'NOT_FOUND');
  }

  // ------------------------------------------------------------------ SePay webhook
  async sepayWebhook(req) {
    const raw = await readRaw(req);
    // never trust the body before the request is authenticated
    if (!this.sepay.configured() || !this.sepay.verify(req.headers, raw)) {
      log.warn('sepay webhook rejected: bad auth');
      throw new HttpError(401, 'UNAUTHORIZED');
    }
    let body;
    try { body = JSON.parse(raw); } catch (e) { throw new HttpError(400, 'BAD_JSON'); }
    if (body === null || typeof body !== 'object' || body.id === undefined || body.transferAmount === undefined) {
      throw new HttpError(400, 'BAD_PAYLOAD');
    }
    const tx = this.sepay.normalize(body);
    if (!Number.isInteger(tx.amount) || tx.amount <= 0) throw new HttpError(400, 'BAD_AMOUNT');
    // money must have arrived on OUR receiving account
    if (tx.account && this.cfg.sepayAccountNumber && tx.account !== this.cfg.sepayAccountNumber) {
      tx.incoming = false;
      tx.raw = Object.assign({}, body, { _note: 'wrong receiving account' });
    }
    if (!this.sb.enabled) throw new HttpError(503, 'SUPABASE_NOT_CONFIGURED');
    const result = await this.sb.rpc('fulfill_payment', { p: tx });
    log.info('sepay webhook', tx.txn_id, result && result.result);
    // SePay requires HTTP 200/201 with exactly {"success": true}; duplicates are acknowledged too
    return { status: 200, body: { success: true } };
  }

  // ------------------------------------------------------------------ admin
  async admin(req, url) {
    const user = await this.auth(req);
    const admins = await this.sb.request('GET', `/rest/v1/admins?select=user_id&user_id=eq.${user.id}`);
    if (!admins || !admins.length) throw new HttpError(403, 'NOT_ADMIN');   // role comes from the DB, never the client
    this.rateLimit('admin:' + user.id, 120, 60000);
    const p = url.pathname, m = req.method;
    const audit = (action, detail) => this.sb.request('POST', '/rest/v1/audit_log', { actor: user.id, action, detail }).catch(() => {});
    const status = url.searchParams.get('status');

    if (p === '/api/admin/orders' && m === 'GET') {
      const filter = status && /^[A-Z_]+$/.test(status) ? `&status=eq.${status}` : '';
      return { body: await this.sb.request('GET', `/rest/v1/payment_orders?select=*&order=created_at.desc&limit=200${filter}`) };
    }
    let mt = /^\/api\/admin\/orders\/([0-9a-f-]{36})\/resolve$/.exec(p);
    if (mt && m === 'POST') {
      const b = await readJson(req);
      return { body: await this.sb.rpc('admin_resolve_order', { p_admin: user.id, p_order: mt[1], p_action: String(b.action || ''), p_note: String(b.note || '').slice(0, 300) }) };
    }
    if (p === '/api/admin/transactions' && m === 'GET') {
      const filter = url.searchParams.get('unmatched') ? '&result=neq.paid' : '';
      return { body: await this.sb.request('GET', `/rest/v1/payment_transactions?select=id,provider,provider_txn_id,order_id,amount_vnd,content,result,created_at&order=id.desc&limit=200${filter}`) };
    }
    if (p === '/api/admin/audit' && m === 'GET') {
      return { body: await this.sb.request('GET', '/rest/v1/audit_log?select=*&order=id.desc&limit=200') };
    }
    const tables = { items: 'items', products: 'shop_products', quests: 'quests' };
    mt = /^\/api\/admin\/(items|products|quests)(?:\/([a-z0-9_]{2,48}))?$/.exec(p);
    if (mt) {
      const table = tables[mt[1]];
      if (m === 'GET') return { body: await this.sb.request('GET', `/rest/v1/${table}?select=*&order=id`) };
      if (m === 'PUT' || m === 'POST') {
        const b = await readJson(req);
        const row = sanitize(table, Object.assign({}, b, mt[2] ? { id: mt[2] } : {}));
        const out = await this.sb.request('POST', `/rest/v1/${table}?on_conflict=id`, row, { Prefer: 'resolution=merge-duplicates,return=representation' });
        await audit(`admin_upsert_${table}`, row);
        return { body: out };
      }
    }
    throw new HttpError(404, 'NOT_FOUND');
  }

  adminPage(res) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    const html = require('fs').readFileSync(require('path').join(__dirname, 'admin.html'), 'utf8')
      .replace('%%SUPABASE_URL%%', this.cfg.supabaseUrl).replace('%%SUPABASE_KEY%%', this.cfg.supabaseAnonKey);
    res.end(html);
  }

  // ------------------------------------------------------------------ helpers
  async auth(req) {
    const h = String(req.headers.authorization || '');
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    const user = token ? await this.sb.verifyToken(token) : null;
    if (!user) throw new HttpError(401, 'UNAUTHORIZED');
    return user;
  }

  async order(id) {
    const rows = await this.sb.request('GET', `/rest/v1/payment_orders?select=*&id=eq.${id}`);
    const o = rows && rows[0];
    if (o && o.status === 'PENDING' && new Date(o.expires_at) < new Date()) {
      await this.sb.rpc('expire_payment_orders', {}).catch(() => {});
      o.status = 'EXPIRED';
    }
    return o;
  }

  rateLimit(key, max, windowMs) {
    const now = Date.now();
    let e = this.limits.get(key);
    if (!e || e.reset < now) { e = { count: 0, reset: now + windowMs }; this.limits.set(key, e); }
    if (++e.count > max) throw new HttpError(429, 'RATE_LIMITED');
    if (this.limits.size > 10000) this.limits.clear();
  }
}

const ALLOWED = {
  items: ['id', 'name', 'description', 'category', 'rarity', 'icon', 'preview_asset', 'acquisition_type', 'price', 'currency_type',
    'stackable', 'max_stack', 'duration_hours', 'character_id', 'gameplay_effect', 'enabled'],
  shop_products: ['id', 'section', 'kind', 'item_id', 'quantity', 'gems', 'price', 'currency', 'title', 'sort', 'enabled', 'available_until'],
  quests: ['id', 'type', 'title', 'description', 'reward', 'sort', 'enabled']
};
function sanitize(table, row) {
  const out = {};
  for (const k of ALLOWED[table]) if (row[k] !== undefined) out[k] = row[k];
  if ('price' in out && !(Number.isInteger(out.price) && out.price >= 0)) throw new HttpError(400, 'BAD_PRICE');
  return out;
}

function publicOrder(o) {
  return { id: o.id, code: o.code, productId: o.product_id, amountVnd: o.amount_vnd, gems: o.gems, provider: o.provider,
    status: o.status, createdAt: o.created_at, expiresAt: o.expires_at, paidAt: o.paid_at };
}

function readRaw(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > MAX_BODY) { reject(new HttpError(413, 'TOO_LARGE')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}
async function readJson(req) {
  const raw = await readRaw(req);
  if (!raw) return {};
  try { return JSON.parse(raw); } catch (e) { throw new HttpError(400, 'BAD_JSON'); }
}
function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

module.exports = { EconomyApi, HttpError };
