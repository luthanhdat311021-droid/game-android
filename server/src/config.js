'use strict';
/**
 * Configuration from environment variables (or server/.env — see .env.example).
 * Never commit real keys. SUPABASE_SERVICE_ROLE_KEY stays on the server only and
 * must never be shipped inside the APK.
 */
const fs = require('fs');
const path = require('path');

function loadDotEnv() {
  const file = path.join(__dirname, '..', '.env');
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}
loadDotEnv();

const env = process.env;
const num = (v, d) => (v === undefined || v === '' ? d : Number(v));
const bool = (v, d) => (v === undefined || v === '' ? d : /^(1|true|yes)$/i.test(v));

const config = {
  port: num(env.PORT, 8080),
  host: env.HOST || '0.0.0.0',
  serverId: env.SERVER_ID || 'local-1',
  supabaseUrl: (env.SUPABASE_URL || '').replace(/\/+$/, ''),
  supabaseAnonKey: env.SUPABASE_PUBLISHABLE_KEY || env.SUPABASE_ANON_KEY || '',
  supabaseServiceKey: env.SUPABASE_SERVICE_ROLE_KEY || env.SUPABASE_SECRET_KEY || '',
  /** Allow unauthenticated guests (local testing only). Guests never write to the database. */
  allowGuests: bool(env.ALLOW_GUESTS, false),
  /** Rooms are limited by resources, not a hardcoded constant. 0 = unlimited. */
  maxRooms: num(env.MAX_ROOMS, 0),
  maxConnections: num(env.MAX_CONNECTIONS, 2000),
  snapshotEvery: num(env.SNAPSHOT_EVERY_TICKS, 2),
  reconnectGraceMs: num(env.RECONNECT_GRACE_MS, 30000),
  heartbeatTimeoutMs: num(env.HEARTBEAT_TIMEOUT_MS, 15000),
  matchDurationOverrideSec: env.MATCH_DURATION_SEC ? Number(env.MATCH_DURATION_SEC) : null,
  countdownMs: num(env.COUNTDOWN_MS, 3000),
  resultsMs: num(env.RESULTS_MS, 8000),
  interestRangeX: num(env.INTEREST_RANGE_X, 1500),
  interestRangeY: num(env.INTEREST_RANGE_Y, 1000),
  inputRatePerSec: num(env.INPUT_RATE_PER_SEC, 45),
  logLevel: env.LOG_LEVEL || 'info',
  // ---- economy / payments (see docs/PAYMENTS.md)
  production: env.NODE_ENV === 'production',
  distributionChannel: env.DISTRIBUTION_CHANNEL || 'direct',   // direct | google_play
  paymentProvider: env.PAYMENT_PROVIDER || 'none',              // sepay | mock | google_play | none
  paymentsEnabled: bool(env.PAYMENTS_ENABLED, false),
  orderTtlMinutes: num(env.ORDER_TTL_MINUTES, 15),
  sepayBank: env.SEPAY_BANK || '',
  sepayAccountNumber: env.SEPAY_ACCOUNT_NUMBER || '',
  sepayAccountName: env.SEPAY_ACCOUNT_NAME || '',
  sepayWebhookAuth: (env.SEPAY_WEBHOOK_AUTH || 'apikey').toLowerCase(),
  sepayWebhookApiKey: env.SEPAY_WEBHOOK_API_KEY || '',
  sepayWebhookHmacSecret: env.SEPAY_WEBHOOK_HMAC_SECRET || ''
};
config.supabaseEnabled = !!(config.supabaseUrl && config.supabaseServiceKey);

module.exports = config;
