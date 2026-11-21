'use strict';
/**
 * Payment provider abstraction.
 *  - SePayProvider: VietQR bank transfer + SePay webhook (https://developer.sepay.vn/vi).
 *  - GooglePlayBillingProvider: required for digital goods in Google Play builds; not wired yet
 *    (needs a Play Console app + server-side purchase verification) — reports "not configured".
 *  - MockProvider: development only. Orders are recorded with provider = 'mock' and never count as real payments.
 *
 * Which provider is allowed depends on the distribution channel (see docs/PAYMENTS.md):
 *   DISTRIBUTION_CHANNEL=google_play  → only google_play (SePay is refused for digital goods)
 *   DISTRIBUTION_CHANNEL=direct       → sepay (APK distributed outside Google Play) or mock in development
 */
const crypto = require('crypto');

class SePayProvider {
  constructor(cfg) {
    this.id = 'sepay';
    this.bank = cfg.sepayBank;
    this.account = cfg.sepayAccountNumber;
    this.holder = cfg.sepayAccountName;
    this.authMode = cfg.sepayWebhookAuth;          // 'apikey' | 'hmac'
    this.apiKey = cfg.sepayWebhookApiKey;
    this.hmacSecret = cfg.sepayWebhookHmacSecret;
  }

  configured() {
    const auth = this.authMode === 'hmac' ? !!this.hmacSecret : !!this.apiKey;
    return !!(this.bank && this.account && auth);
  }

  /** Payment instructions shown in the app. QR image per SePay "Tạo QR code" docs (vietqr.app/img). */
  instructions(order) {
    const q = new URLSearchParams({
      acc: this.account, bank: this.bank, amount: String(order.amount_vnd), des: order.code,
      template: 'compact', showinfo: 'true'
    });
    if (this.holder) q.set('holder', this.holder);
    return {
      provider: 'sepay', bank: this.bank, accountNumber: this.account, accountName: this.holder || '',
      amount: order.amount_vnd, transferContent: order.code, qrUrl: 'https://vietqr.app/img?' + q.toString()
    };
  }

  /**
   * Verifies a webhook request. Supported SePay auth modes:
   *  - API Key: header "Authorization: Apikey <key>"
   *  - HMAC-SHA256: X-SePay-Signature = "sha256=" + hex(HMAC(secret, "<timestamp>.<raw body>")),
   *    X-SePay-Timestamp within ±300 s.
   */
  verify(headers, rawBody, nowSec = Math.floor(Date.now() / 1000)) {
    if (this.authMode === 'hmac') {
      const sig = String(headers['x-sepay-signature'] || '');
      const ts = Number(headers['x-sepay-timestamp']);
      if (!sig.startsWith('sha256=') || !Number.isFinite(ts) || Math.abs(nowSec - ts) > 300) return false;
      const expected = 'sha256=' + crypto.createHmac('sha256', this.hmacSecret).update(ts + '.' + rawBody).digest('hex');
      return safeEqual(sig, expected);
    }
    const auth = String(headers.authorization || '');
    return !!this.apiKey && safeEqual(auth, 'Apikey ' + this.apiKey);
  }

  /** Maps the SePay payload to the provider-neutral shape used by public.fulfill_payment(). */
  normalize(body) {
    return {
      provider: 'sepay',
      txn_id: String(body.id),
      amount: Number.isInteger(body.transferAmount) ? body.transferAmount : parseInt(body.transferAmount, 10),
      account: String(body.accountNumber || ''),
      content: String(body.content || '') + ' ' + String(body.code || ''),
      reference: String(body.referenceCode || ''),
      incoming: body.transferType === 'in',
      raw: body
    };
  }
}

class MockProvider {
  constructor() { this.id = 'mock'; }
  configured() { return true; }
  instructions(order) {
    return {
      provider: 'mock', bank: 'MOCK BANK (dev)', accountNumber: '0000000000', accountName: 'SHADOW DUEL DEV',
      amount: order.amount_vnd, transferContent: order.code,
      qrUrl: '', note: 'Development mock — no real money. Use the "Simulate payment" button.'
    };
  }
}

class GooglePlayBillingProvider {
  constructor() { this.id = 'google_play'; }
  configured() { return false; }
  instructions() { return { provider: 'google_play', note: 'Google Play Billing is not configured on this server.' }; }
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** Picks the provider allowed for this deployment, or null when payments are off. */
function selectProvider(cfg) {
  if (!cfg.paymentsEnabled) return { provider: null, reason: 'PAYMENTS_DISABLED' };
  const channel = cfg.distributionChannel;
  const wanted = cfg.paymentProvider;
  if (channel === 'google_play' && wanted !== 'google_play') {
    return { provider: null, reason: 'PROVIDER_NOT_ALLOWED_FOR_CHANNEL' };
  }
  let p;
  if (wanted === 'sepay') p = new SePayProvider(cfg);
  else if (wanted === 'mock') {
    if (cfg.production) return { provider: null, reason: 'MOCK_NOT_ALLOWED_IN_PRODUCTION' };
    p = new MockProvider();
  } else if (wanted === 'google_play') p = new GooglePlayBillingProvider();
  else return { provider: null, reason: 'NO_PROVIDER' };
  if (!p.configured()) return { provider: null, reason: 'PROVIDER_NOT_CONFIGURED' };
  return { provider: p, reason: null };
}

module.exports = { SePayProvider, MockProvider, GooglePlayBillingProvider, selectProvider, safeEqual };
