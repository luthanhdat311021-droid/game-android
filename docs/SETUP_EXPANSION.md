# Shadow Duel – Expansion setup (Google login, quests, shop, payments)

## 1. Database (Supabase)

Run `supabase/migrations/20261122000000_economy_quests_shop_payments.sql` in **Supabase Dashboard → SQL Editor** (after the base migration).
It adds: `items`, `player_inventory` (extended), `player_equipment`, `wallets`, `currency_ledger`, `reward_grants`,
`quests`, `quest_objectives`, `player_quest_progress`, `quest_reward_claims`, `quest_events`, `shop_products`,
`payment_orders`, `payment_transactions`, `purchase_history`, `admins`, `audit_log`, plus seed items/quests/products.

Who can do what:

| Action | Who | How it is enforced |
|---|---|---|
| Read own wallet / inventory / orders / quests | the player | RLS `player_id = auth.uid()` |
| Buy with Gold/Gems, claim quest, equip, use item, daily login | the player | `SECURITY DEFINER` SQL functions that re-check ownership, balance, progress and duplicates |
| Quest progress, match Gold | game server only | `record_player_match()` — service_role only, idempotent per `matchId:userId` |
| Create Gems order, confirm payment | game server only | `create_payment_order()`, `fulfill_payment()` — service_role only |
| Edit catalog / prices / quests, resolve orders | admins | Node `/api/admin/*` checks `public.admins` (never a client-sent role) + `audit_log` |

Make yourself admin (SQL Editor): `insert into public.admins (user_id) select id from auth.users where email = 'you@example.com';`
then open `http://<server>:8080/admin`.

## 2. Google Sign-In (Supabase OAuth + PKCE)

The app opens `SUPABASE_URL/auth/v1/authorize?provider=google&…&code_challenge=…` in the browser; Google returns
to Supabase, Supabase redirects to `shadowduel://auth-callback?code=…`, and the app exchanges the code with its
PKCE verifier (`/auth/v1/token?grant_type=pkce`). The app never trusts an e-mail address by itself; the session
comes from Supabase Auth. Tokens are stored encrypted with an Android Keystore key and are never logged.

1. **Google Cloud Console → APIs & Services → Credentials → Create OAuth client ID → Web application.**
   Authorized redirect URI: `https://<project-ref>.supabase.co/auth/v1/callback`.
   (OAuth consent screen: app name, support e-mail, scopes `email`, `profile`, `openid`.)
2. **Supabase → Authentication → Sign In / Providers → Google**: enable, paste the Web *Client ID* and *Client secret*.
3. **Supabase → Authentication → URL Configuration → Redirect URLs**: add `shadowduel://auth-callback`.
4. Package name is `com.shadowduel.arena`. An Android OAuth client + SHA-1 is only needed if you later switch to the
   native Credential Manager flow; the browser + PKCE flow above does not need it.
5. Same e-mail used with password and Google: Supabase links identities only according to its own rules
   (verified e-mails); the app does not merge accounts by e-mail.

New players (any provider) get a profile, stats, ranking, wallet and starter kit from the `handle_new_user` trigger;
`init_my_account()` repeats this idempotently on every launch, so nothing is granted twice.

## 3. Payments

### Distribution channel rules
| `DISTRIBUTION_CHANNEL` | Allowed `PAYMENT_PROVIDER` |
|---|---|
| `google_play` | `google_play` only (Google Play Billing for digital goods). SePay is refused by the server. |
| `direct` (APK outside Google Play) | `sepay`; `mock` only when `NODE_ENV` is not `production` |

`GooglePlayBillingProvider` is a stub that reports "not configured" until a Play Console app and server-side purchase
verification are added. Don't enable SePay in a Google Play build unless the app is eligible for an alternative billing
programme and complies with its rules.

### SePay (bank transfer QR)
Server `.env`:
```
PAYMENTS_ENABLED=true
DISTRIBUTION_CHANNEL=direct
PAYMENT_PROVIDER=sepay
SEPAY_BANK=MBBank                 # short name / code from https://vietqr.app/banks.json
SEPAY_ACCOUNT_NUMBER=...          # receiving account linked in SePay
SEPAY_ACCOUNT_NAME=TEN CHU TK     # unaccented
SEPAY_WEBHOOK_AUTH=apikey         # or hmac
SEPAY_WEBHOOK_API_KEY=...         # for apikey mode
SEPAY_WEBHOOK_HMAC_SECRET=...     # for hmac mode
ORDER_TTL_MINUTES=15
```
In **my.sepay.vn**:
1. Link the receiving bank account.
2. *Cấu hình mã thanh toán*: add prefix `SD` (order codes look like `SDAB12CD34`).
3. Create a webhook → URL `https://<your-server>/api/payments/sepay/webhook`, event "tiền vào",
   authentication **API Key** (`Authorization: Apikey <key>`) or **HMAC-SHA256** (`X-SePay-Signature`,
   `X-SePay-Timestamp`) — the same value as in `.env`. Enable "Tự động gửi lại khi server trả lỗi".
4. The server answers `{"success": true}` with HTTP 200 (required by SePay), including for duplicates.
5. Test in **Test mode → Mô phỏng giao dịch** (no real money) before going live.

The QR image uses SePay's documented VietQR URL: `https://vietqr.app/img?acc=…&bank=…&amount=…&des=<order code>&template=compact`.

### What the server checks on every webhook
authentication → incoming transfer → our receiving account → transaction id not seen before (unique
`(provider, provider_txn_id)`) → order code in the content → order is PENDING and not expired → exact amount →
mark PAID + grant Gems + ledger row, all in one Postgres transaction. Anything else (wrong amount, late, duplicate
payment, unknown code) is stored in `payment_transactions` and the order becomes `NEEDS_REVIEW` for manual
reconciliation in `/admin` (Cấp Gems / Hủy / Đã hoàn tiền), with an audit-log entry.
Missed webhooks: SePay retries for ~33 minutes, then use *Lịch sử gửi → Replay* in SePay; the order stays retrievable in the app.

### Development without SePay
`PAYMENTS_ENABLED=true PAYMENT_PROVIDER=mock` (not allowed with `NODE_ENV=production`): the payment screen shows a
"[DEV] giả lập thanh toán" button; such orders are stored with `provider = 'mock'` and are not real payments.

## 4. Tests
```
cd server
npm test                       # unit + websocket + payment API tests (no network)
node tools/economy-e2e.js      # against your Supabase with the two test accounts in .env
```
