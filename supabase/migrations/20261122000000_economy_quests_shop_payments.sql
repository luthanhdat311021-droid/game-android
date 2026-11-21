-- =====================================================================
-- SHADOW DUEL – Expansion: wallet, ledger, items, inventory, equipment,
-- quests, shop, payment orders (SePay), admin + audit.
--
-- Security model (same as the base migration):
--  * Clients READ their own rows (RLS) and call a few SECURITY DEFINER
--    functions that use auth.uid() and re-check everything in SQL
--    (claim quest, buy with Gold/Gems, equip, use consumable, daily login).
--  * Money (Gems purchases), quest progress and match rewards are written
--    only by the Node.js server with the service_role key.
--  * Every balance change goes through add_currency(): one ledger row per
--    change, unique reference, balances can never go negative.
-- =====================================================================

-- ---------------------------------------------------------------- admin + audit
create table if not exists public.admins (
  user_id    uuid primary key references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.audit_log (
  id         bigint generated always as identity primary key,
  actor      uuid,
  action     text not null,
  detail     jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists audit_log_created_idx on public.audit_log (created_at desc);

-- ---------------------------------------------------------------- catalog
create table if not exists public.items (
  id               text primary key check (id ~ '^[a-z0-9_]{2,48}$'),
  name             text not null,
  description      text not null default '',
  category         text not null check (category in ('weapon','equipment','consumable','skin','effect','emote','nameplate','chest')),
  rarity           text not null default 'common' check (rarity in ('common','rare','epic','legendary','mythic')),
  icon             text not null default '',
  preview_asset    text not null default '',
  acquisition_type text not null default 'shop' check (acquisition_type in ('shop','quest','starter','event','chest','premium')),
  price            int  not null default 0 check (price >= 0),
  currency_type    text not null default 'none' check (currency_type in ('gold','gems','none')),
  stackable        boolean not null default false,
  max_stack        int  not null default 1 check (max_stack >= 1),
  duration_hours   int  check (duration_hours is null or duration_hours > 0),
  character_id     text references public.characters (id),
  -- cosmetic palette / training-only stats / chest contents; never applied in ranked PvP
  gameplay_effect  jsonb not null default '{}'::jsonb,
  enabled          boolean not null default true,
  created_at       timestamptz not null default now()
);

-- the base migration created player_inventory; extend it
alter table public.player_inventory add column if not exists source text not null default 'unknown';
alter table public.player_inventory add column if not exists expires_at timestamptz;
do $$ begin
  alter table public.player_inventory add constraint player_inventory_item_fk foreign key (item_id) references public.items (id);
exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.player_inventory add constraint player_inventory_qty_max check (quantity <= 9999);
exception when duplicate_object then null; end $$;

create table if not exists public.player_equipment (
  player_id    uuid not null references public.profiles (id) on delete cascade,
  slot         text not null check (slot in ('skin','weapon','equipment','effect','emote','nameplate')),
  character_id text not null default '*',
  item_id      text not null references public.items (id),
  equipped_at  timestamptz not null default now(),
  primary key (player_id, slot, character_id)
);

-- ---------------------------------------------------------------- wallet + ledger
create table if not exists public.wallets (
  player_id  uuid primary key references public.profiles (id) on delete cascade,
  gold       bigint not null default 0 check (gold >= 0),
  gems       bigint not null default 0 check (gems >= 0),
  updated_at timestamptz not null default now()
);

create table if not exists public.currency_ledger (
  id            bigint generated always as identity primary key,
  player_id     uuid not null references public.profiles (id) on delete cascade,
  currency      text not null check (currency in ('gold','gems')),
  delta         bigint not null check (delta <> 0),
  balance_after bigint not null check (balance_after >= 0),
  reason        text not null,
  ref           text not null,
  created_at    timestamptz not null default now(),
  unique (player_id, currency, ref)
);
create index if not exists currency_ledger_player_idx on public.currency_ledger (player_id, created_at desc);

create table if not exists public.reward_grants (
  id         bigint generated always as identity primary key,
  player_id  uuid not null references public.profiles (id) on delete cascade,
  ref        text not null unique,
  source     text not null,
  rewards    jsonb not null,
  created_at timestamptz not null default now()
);
create index if not exists reward_grants_player_idx on public.reward_grants (player_id, created_at desc);

-- ---------------------------------------------------------------- quests
create table if not exists public.quests (
  id          text primary key check (id ~ '^[a-z0-9_]{2,48}$'),
  type        text not null check (type in ('daily','weekly','achievement')),
  title       text not null,
  description text not null default '',
  reward      jsonb not null default '{}'::jsonb,   -- {"gold":200,"gems":0,"items":[{"id":"chest_basic","qty":1}]}
  sort        int  not null default 0,
  enabled     boolean not null default true,
  created_at  timestamptz not null default now()
);

create table if not exists public.quest_objectives (
  id       bigint generated always as identity primary key,
  quest_id text not null references public.quests (id) on delete cascade,
  metric   text not null check (metric in ('matches_completed','kills','wins','damage','assists','tdm_completed','win_streak','rating')),
  target   int  not null check (target > 0),
  unique (quest_id, metric)
);

create table if not exists public.player_quest_progress (
  player_id    uuid not null references public.profiles (id) on delete cascade,
  quest_id     text not null references public.quests (id) on delete cascade,
  period       text not null,          -- 2026-11-22 (daily), 2026-W47 (weekly), all (achievement)
  metric       text not null,
  progress     bigint not null default 0 check (progress >= 0),
  updated_at   timestamptz not null default now(),
  primary key (player_id, quest_id, period, metric)
);

create table if not exists public.quest_reward_claims (
  player_id  uuid not null references public.profiles (id) on delete cascade,
  quest_id   text not null references public.quests (id) on delete cascade,
  period     text not null,
  claimed_at timestamptz not null default now(),
  primary key (player_id, quest_id, period)
);

-- events already applied (idempotency by event id = matchId:userId)
create table if not exists public.quest_events (
  event_id     text primary key,
  player_id    uuid not null references public.profiles (id) on delete cascade,
  payload      jsonb not null,
  processed_at timestamptz not null default now()
);

create table if not exists public.player_counters (
  player_id  uuid primary key references public.profiles (id) on delete cascade,
  win_streak int not null default 0 check (win_streak >= 0),
  best_streak int not null default 0 check (best_streak >= 0)
);

create table if not exists public.daily_logins (
  player_id uuid not null references public.profiles (id) on delete cascade,
  day       date not null,
  primary key (player_id, day)
);

-- ---------------------------------------------------------------- shop + payments
create table if not exists public.shop_products (
  id            text primary key check (id ~ '^[a-z0-9_]{2,48}$'),
  section       text not null check (section in ('featured','skins','weapons','effects','currency','limited','consumables')),
  kind          text not null check (kind in ('item','gems_pack')),
  item_id       text references public.items (id),
  quantity      int  not null default 1 check (quantity between 1 and 99),
  gems          int  not null default 0 check (gems >= 0),
  price         bigint not null check (price > 0),             -- Gold, Gems or VND (integer, never float)
  currency      text not null check (currency in ('gold','gems','vnd')),
  title         text not null,
  sort          int  not null default 0,
  enabled       boolean not null default true,
  available_until timestamptz,
  check ((kind = 'item' and item_id is not null and currency in ('gold','gems'))
      or (kind = 'gems_pack' and gems > 0 and currency = 'vnd'))
);

create table if not exists public.payment_orders (
  id             uuid primary key default gen_random_uuid(),
  code           text not null unique check (code ~ '^SD[A-Z0-9]{8}$'),
  player_id      uuid not null references public.profiles (id) on delete cascade,
  product_id     text not null references public.shop_products (id),
  amount_vnd     bigint not null check (amount_vnd > 0),
  gems           int not null check (gems > 0),
  provider       text not null check (provider in ('sepay','google_play','mock')),
  status         text not null default 'PENDING' check (status in ('PENDING','PAID','EXPIRED','CANCELLED','FAILED','NEEDS_REVIEW','REFUNDED')),
  status_note    text not null default '',
  created_at     timestamptz not null default now(),
  expires_at     timestamptz not null,
  paid_at        timestamptz,
  transaction_id bigint
);
create index if not exists payment_orders_player_idx on public.payment_orders (player_id, created_at desc);
create index if not exists payment_orders_status_idx on public.payment_orders (status, expires_at);

create table if not exists public.payment_transactions (
  id              bigint generated always as identity primary key,
  provider        text not null,
  provider_txn_id text not null,
  order_id        uuid references public.payment_orders (id),
  amount_vnd      bigint not null,
  account_number  text not null default '',
  content         text not null default '',
  reference_code  text not null default '',
  result          text not null check (result in ('paid','duplicate_order_payment','amount_mismatch','expired_order','no_order','not_incoming','order_not_pending')),
  raw             jsonb not null default '{}'::jsonb,
  created_at      timestamptz not null default now(),
  unique (provider, provider_txn_id)
);

create table if not exists public.purchase_history (
  id         bigint generated always as identity primary key,
  player_id  uuid not null references public.profiles (id) on delete cascade,
  product_id text not null references public.shop_products (id),
  item_id    text references public.items (id),
  quantity   int not null default 1,
  currency   text not null,
  price      bigint not null,
  ref        text not null unique,
  created_at timestamptz not null default now()
);
create index if not exists purchase_history_player_idx on public.purchase_history (player_id, created_at desc);

-- =====================================================================
-- Internal helpers (not executable by clients)
-- =====================================================================

-- Single entry point for balance changes. Unique (player,currency,ref): a replay is a no-op.
create or replace function public.add_currency(p_player uuid, p_currency text, p_delta bigint, p_reason text, p_ref text)
returns boolean language plpgsql security definer set search_path = public as $$
declare v_after bigint;
begin
  if p_delta = 0 then return true; end if;
  if exists (select 1 from currency_ledger where player_id = p_player and currency = p_currency and ref = p_ref) then
    return false;
  end if;
  insert into wallets (player_id) values (p_player) on conflict (player_id) do nothing;
  if p_currency = 'gold' then
    update wallets set gold = gold + p_delta, updated_at = now() where player_id = p_player returning gold into v_after;
  elsif p_currency = 'gems' then
    update wallets set gems = gems + p_delta, updated_at = now() where player_id = p_player returning gems into v_after;
  else
    raise exception 'unknown currency %', p_currency;
  end if;
  -- the CHECK (gold >= 0) constraint aborts the whole transaction on overdraft
  insert into currency_ledger (player_id, currency, delta, balance_after, reason, ref)
  values (p_player, p_currency, p_delta, v_after, p_reason, p_ref);
  return true;
end $$;

create or replace function public.add_item(p_player uuid, p_item text, p_qty int, p_source text)
returns void language plpgsql security definer set search_path = public as $$
declare it items; v_exp timestamptz;
begin
  select * into it from items where id = p_item;
  if not found then raise exception 'unknown item %', p_item; end if;
  v_exp := case when it.duration_hours is null then null else now() + make_interval(hours => it.duration_hours) end;
  if it.stackable then
    insert into player_inventory (player_id, item_id, quantity, source, expires_at)
    values (p_player, p_item, least(p_qty, it.max_stack), p_source, v_exp)
    on conflict (player_id, item_id) do update
      set quantity = least(player_inventory.quantity + p_qty, it.max_stack),
          expires_at = coalesce(v_exp, player_inventory.expires_at);
  else
    insert into player_inventory (player_id, item_id, quantity, source, expires_at)
    values (p_player, p_item, 1, p_source, v_exp)
    on conflict (player_id, item_id) do update set quantity = 1, expires_at = case
      when player_inventory.expires_at is null or v_exp is null then null   -- permanent wins
      else greatest(player_inventory.expires_at, now()) + make_interval(hours => it.duration_hours) end;
  end if;
end $$;

-- Grants a reward bundle exactly once per reference.
create or replace function public.grant_reward(p_player uuid, p_ref text, p_source text, p_reward jsonb)
returns boolean language plpgsql security definer set search_path = public as $$
declare it jsonb;
begin
  insert into reward_grants (player_id, ref, source, rewards) values (p_player, p_ref, p_source, p_reward)
  on conflict (ref) do nothing;
  if not found then return false; end if;
  if coalesce((p_reward ->> 'gold')::bigint, 0) > 0 then
    perform add_currency(p_player, 'gold', (p_reward ->> 'gold')::bigint, p_source, p_ref);
  end if;
  if coalesce((p_reward ->> 'gems')::bigint, 0) > 0 then
    perform add_currency(p_player, 'gems', (p_reward ->> 'gems')::bigint, p_source, p_ref);
  end if;
  for it in select * from jsonb_array_elements(coalesce(p_reward -> 'items', '[]'::jsonb)) loop
    perform add_item(p_player, it ->> 'id', greatest(1, coalesce((it ->> 'qty')::int, 1)), p_source);
  end loop;
  return true;
end $$;

-- Wallet + starter kit; safe to call any number of times.
create or replace function public.ensure_player_economy(p_player uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into wallets (player_id) values (p_player) on conflict (player_id) do nothing;
  insert into player_counters (player_id) values (p_player) on conflict (player_id) do nothing;
  perform grant_reward(p_player, 'starter:' || p_player, 'starter',
    '{"gold":500,"items":[{"id":"health_potion","qty":3},{"id":"energy_potion","qty":2},{"id":"nameplate_rookie","qty":1}]}'::jsonb);
end $$;

create or replace function public.quest_period(p_type text)
returns text language sql stable as $$
  select case p_type
    when 'daily'  then to_char(now() at time zone 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD')
    when 'weekly' then to_char(now() at time zone 'Asia/Ho_Chi_Minh', 'IYYY-"W"IW')
    else 'all' end;
$$;

-- profile bootstrap now also creates the wallet and starter kit (Google sign-ins included)
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare base text; uname text; i int := 0;
begin
  base := regexp_replace(coalesce(new.raw_user_meta_data ->> 'username', new.raw_user_meta_data ->> 'name',
                                  split_part(new.email, '@', 1), 'player'), '[^A-Za-z0-9_.-]', '', 'g');
  if char_length(base) < 3 then base := base || 'player'; end if;
  base := left(base, 15);
  uname := base;
  while exists (select 1 from public.profiles where username = uname) loop
    i := i + 1;
    uname := base || (floor(random() * 9000) + 1000)::int;
    exit when i > 20;
  end loop;
  insert into public.profiles (id, username) values (new.id, uname) on conflict (id) do nothing;
  insert into public.player_stats (player_id) values (new.id) on conflict (player_id) do nothing;
  insert into public.rankings (player_id) values (new.id) on conflict (player_id) do nothing;
  perform public.ensure_player_economy(new.id);
  return new;
end $$;

-- =====================================================================
-- Server-only (service_role): quest tracking from trusted match events
-- =====================================================================
-- p_event = {"event_id":"<matchId>:<userId>","player_id":"…","mode":"tdm","won":true,
--            "kills":3,"assists":1,"damage":1450,"rating":1016}
create or replace function public.record_player_match(p_event jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_player uuid := (p_event ->> 'player_id')::uuid;
  v_won boolean := coalesce((p_event ->> 'won')::boolean, false);
  v_streak int;
  q record;
  v_amount bigint;
begin
  if not exists (select 1 from profiles where id = v_player) then return '{"ok":false,"reason":"no_profile"}'; end if;
  insert into quest_events (event_id, player_id, payload) values (p_event ->> 'event_id', v_player, p_event)
  on conflict (event_id) do nothing;
  if not found then return '{"ok":true,"duplicate":true}'; end if;

  perform ensure_player_economy(v_player);
  update player_counters set win_streak = case when v_won then win_streak + 1 else 0 end,
         best_streak = greatest(best_streak, case when v_won then win_streak + 1 else 0 end)
   where player_id = v_player returning win_streak into v_streak;

  -- match completion reward (Gold only)
  perform grant_reward(v_player, 'match:' || (p_event ->> 'event_id'), 'match_reward',
          jsonb_build_object('gold', case when v_won then 60 else 30 end + least(40, coalesce((p_event ->> 'kills')::int, 0) * 5)));

  for q in
    select o.quest_id, o.metric, qs.type from quest_objectives o join quests qs on qs.id = o.quest_id where qs.enabled
  loop
    v_amount := case q.metric
      when 'matches_completed' then 1
      when 'kills'   then coalesce((p_event ->> 'kills')::bigint, 0)
      when 'wins'    then case when v_won then 1 else 0 end
      when 'damage'  then coalesce((p_event ->> 'damage')::bigint, 0)
      when 'assists' then coalesce((p_event ->> 'assists')::bigint, 0)
      when 'tdm_completed' then case when p_event ->> 'mode' = 'tdm' then 1 else 0 end
      else 0 end;
    if q.metric in ('win_streak', 'rating') then
      -- "best value reached" metrics
      v_amount := case q.metric when 'win_streak' then v_streak else coalesce((p_event ->> 'rating')::bigint, 0) end;
      insert into player_quest_progress (player_id, quest_id, period, metric, progress)
      values (v_player, q.quest_id, quest_period(q.type), q.metric, v_amount)
      on conflict (player_id, quest_id, period, metric) do update
        set progress = greatest(player_quest_progress.progress, excluded.progress), updated_at = now();
    elsif v_amount > 0 then
      insert into player_quest_progress (player_id, quest_id, period, metric, progress)
      values (v_player, q.quest_id, quest_period(q.type), q.metric, v_amount)
      on conflict (player_id, quest_id, period, metric) do update
        set progress = player_quest_progress.progress + excluded.progress, updated_at = now();
    end if;
  end loop;
  return jsonb_build_object('ok', true, 'win_streak', v_streak);
end $$;

-- =====================================================================
-- Server-only: payment orders + SePay fulfilment
-- =====================================================================
create or replace function public.create_payment_order(p_player uuid, p_product text, p_provider text, p_ttl_minutes int)
returns payment_orders language plpgsql security definer set search_path = public as $$
declare pr shop_products; o payment_orders; v_code text; v_open int;
begin
  select * into pr from shop_products where id = p_product and enabled
    and (available_until is null or available_until > now());
  if not found or pr.kind <> 'gems_pack' then raise exception 'PRODUCT_NOT_AVAILABLE'; end if;
  select count(*) into v_open from payment_orders where player_id = p_player and status = 'PENDING' and expires_at > now();
  if v_open >= 3 then raise exception 'TOO_MANY_PENDING_ORDERS'; end if;
  loop
    v_code := 'SD' || upper(substr(translate(encode(extensions.gen_random_bytes(12), 'base64'), '+/=', 'XYZ'), 1, 8));
    exit when v_code ~ '^SD[A-Z0-9]{8}$' and not exists (select 1 from payment_orders where code = v_code);
  end loop;
  insert into payment_orders (code, player_id, product_id, amount_vnd, gems, provider, expires_at)
  values (v_code, p_player, pr.id, pr.price, pr.gems, p_provider, now() + make_interval(mins => greatest(5, least(p_ttl_minutes, 60))))
  returning * into o;
  insert into audit_log (actor, action, detail) values (p_player, 'order_created', jsonb_build_object('order', o.id, 'code', o.code, 'amount', o.amount_vnd));
  return o;
end $$;

-- p = {"provider":"sepay","txn_id":"92704","amount":50000,"account":"…","content":"SDABCD1234 …",
--      "reference":"FT…","incoming":true,"raw":{…}}
-- Returns {"result": "...", "order": "...", "player": "..."}; idempotent on (provider, txn_id).
create or replace function public.fulfill_payment(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_code text := substring(upper(coalesce(p ->> 'content', '')) from 'SD[A-Z0-9]{8}');
  v_amount bigint := (p ->> 'amount')::bigint;
  o payment_orders;
  v_result text;
  v_txn bigint;
begin
  perform pg_advisory_xact_lock(hashtext('pay:' || (p ->> 'provider') || ':' || (p ->> 'txn_id')));
  if exists (select 1 from payment_transactions where provider = p ->> 'provider' and provider_txn_id = p ->> 'txn_id') then
    return jsonb_build_object('result', 'duplicate_webhook');
  end if;

  if not coalesce((p ->> 'incoming')::boolean, false) then
    v_result := 'not_incoming';
  elsif v_code is null then
    v_result := 'no_order';
  else
    select * into o from payment_orders where code = v_code for update;
    if not found then v_result := 'no_order';
    elsif o.status = 'PAID' then v_result := 'duplicate_order_payment';
    elsif o.status <> 'PENDING' then v_result := 'order_not_pending';
    elsif o.expires_at < now() then v_result := 'expired_order';
    elsif v_amount <> o.amount_vnd then v_result := 'amount_mismatch';
    else v_result := 'paid';
    end if;
  end if;

  insert into payment_transactions (provider, provider_txn_id, order_id, amount_vnd, account_number, content, reference_code, result, raw)
  values (p ->> 'provider', p ->> 'txn_id', o.id, v_amount, coalesce(p ->> 'account', ''), left(coalesce(p ->> 'content', ''), 500),
          coalesce(p ->> 'reference', ''), v_result, coalesce(p -> 'raw', '{}'::jsonb))
  returning id into v_txn;

  if v_result = 'paid' then
    update payment_orders set status = 'PAID', paid_at = now(), transaction_id = v_txn where id = o.id;
    perform grant_reward(o.player_id, 'order:' || o.id, 'gems_purchase', jsonb_build_object('gems', o.gems));
  elsif o.id is not null and v_result in ('amount_mismatch', 'expired_order', 'order_not_pending', 'duplicate_order_payment') then
    -- money arrived but cannot be fulfilled automatically: keep for manual reconciliation
    update payment_orders set status = case when status = 'PAID' then 'PAID' else 'NEEDS_REVIEW' end,
           status_note = v_result || ' txn ' || (p ->> 'txn_id') where id = o.id;
  end if;
  insert into audit_log (action, detail) values ('payment_webhook',
    jsonb_build_object('txn', p ->> 'txn_id', 'result', v_result, 'order', o.id, 'amount', v_amount));
  return jsonb_build_object('result', v_result, 'order', o.id, 'player', o.player_id);
end $$;

create or replace function public.expire_payment_orders()
returns int language sql security definer set search_path = public as $$
  with x as (update payment_orders set status = 'EXPIRED' where status = 'PENDING' and expires_at < now() returning 1)
  select count(*)::int from x;
$$;

-- Admin: manual decision on a NEEDS_REVIEW / unmatched payment (called by the server after checking admins)
create or replace function public.admin_resolve_order(p_admin uuid, p_order uuid, p_action text, p_note text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare o payment_orders;
begin
  if not exists (select 1 from admins where user_id = p_admin) then raise exception 'NOT_ADMIN'; end if;
  select * into o from payment_orders where id = p_order for update;
  if not found then raise exception 'ORDER_NOT_FOUND'; end if;
  if p_action = 'grant' then
    if o.status = 'PAID' then raise exception 'ALREADY_PAID'; end if;
    update payment_orders set status = 'PAID', paid_at = now(), status_note = 'manual: ' || coalesce(p_note, '') where id = o.id;
    perform grant_reward(o.player_id, 'order:' || o.id, 'gems_purchase_manual', jsonb_build_object('gems', o.gems));
  elsif p_action in ('cancel', 'refunded', 'failed') then
    if o.status = 'PAID' and p_action <> 'refunded' then raise exception 'ALREADY_PAID'; end if;
    update payment_orders set status = case p_action when 'cancel' then 'CANCELLED' when 'refunded' then 'REFUNDED' else 'FAILED' end,
           status_note = 'manual: ' || coalesce(p_note, '') where id = o.id;
  else raise exception 'BAD_ACTION';
  end if;
  insert into audit_log (actor, action, detail) values (p_admin, 'order_' || p_action, jsonb_build_object('order', o.id, 'note', p_note));
  return jsonb_build_object('ok', true);
end $$;

-- =====================================================================
-- Client-callable (authenticated) — every check is done here, server side
-- =====================================================================
create or replace function public.init_my_account()
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid();
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  if not exists (select 1 from profiles where id = v) then
    raise exception 'NO_PROFILE';
  end if;
  perform ensure_player_economy(v);
  return (select to_jsonb(w) from wallets w where player_id = v);
end $$;

create or replace function public.claim_daily_login()
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid(); d date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  insert into daily_logins (player_id, day) values (v, d) on conflict do nothing;
  if not found then return '{"claimed":false}'; end if;
  perform grant_reward(v, 'daily_login:' || v || ':' || d, 'daily_login', '{"gold":100}'::jsonb);
  return '{"claimed":true,"gold":100}';
end $$;

create or replace function public.claim_quest(p_quest text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid(); q quests; v_period text; o record; v_prog bigint;
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  select * into q from quests where id = p_quest and enabled;
  if not found then raise exception 'QUEST_NOT_FOUND'; end if;
  v_period := quest_period(q.type);
  for o in select * from quest_objectives where quest_id = q.id loop
    select progress into v_prog from player_quest_progress
     where player_id = v and quest_id = q.id and period = v_period and metric = o.metric;
    if coalesce(v_prog, 0) < o.target then raise exception 'QUEST_NOT_COMPLETE'; end if;
  end loop;
  insert into quest_reward_claims (player_id, quest_id, period) values (v, q.id, v_period) on conflict do nothing;
  if not found then raise exception 'ALREADY_CLAIMED'; end if;
  perform grant_reward(v, 'quest:' || v || ':' || q.id || ':' || v_period, 'quest:' || q.id, q.reward);
  return jsonb_build_object('claimed', true, 'reward', q.reward);
end $$;

-- Buy a catalog item with Gold or Gems. Gems packs (real money) are NOT sold here.
create or replace function public.buy_product(p_product text, p_request_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid(); pr shop_products; it items; v_ref text;
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  v_ref := 'buy:' || v || ':' || p_request_id;
  if exists (select 1 from purchase_history where ref = v_ref) then return '{"ok":true,"duplicate":true}'; end if;
  select * into pr from shop_products where id = p_product and enabled and kind = 'item'
    and (available_until is null or available_until > now());
  if not found then raise exception 'PRODUCT_NOT_AVAILABLE'; end if;
  select * into it from items where id = pr.item_id and enabled;
  if not found then raise exception 'ITEM_DISABLED'; end if;
  if not it.stackable and exists (select 1 from player_inventory where player_id = v and item_id = it.id
                                  and (expires_at is null or expires_at > now())) then
    raise exception 'ALREADY_OWNED';
  end if;
  perform ensure_player_economy(v);
  if (pr.currency = 'gold' and (select gold from wallets where player_id = v) < pr.price)
     or (pr.currency = 'gems' and (select gems from wallets where player_id = v) < pr.price) then
    raise exception 'INSUFFICIENT_FUNDS';
  end if;
  perform add_currency(v, pr.currency, -pr.price, 'purchase:' || pr.id, v_ref);
  perform add_item(v, it.id, pr.quantity, 'shop');
  insert into purchase_history (player_id, product_id, item_id, quantity, currency, price, ref)
  values (v, pr.id, it.id, pr.quantity, pr.currency, pr.price, v_ref);
  return jsonb_build_object('ok', true, 'item', it.id, 'quantity', pr.quantity);
end $$;

create or replace function public.equip_item(p_item text, p_character text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid(); it items; v_slot text; v_char text;
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  select * into it from items where id = p_item;
  if not found then raise exception 'ITEM_NOT_FOUND'; end if;
  if not exists (select 1 from player_inventory where player_id = v and item_id = p_item and quantity > 0
                 and (expires_at is null or expires_at > now())) then
    raise exception 'NOT_OWNED';
  end if;
  v_slot := case it.category when 'skin' then 'skin' when 'weapon' then 'weapon' when 'equipment' then 'equipment'
                             when 'effect' then 'effect' when 'emote' then 'emote' when 'nameplate' then 'nameplate' end;
  if v_slot is null then raise exception 'NOT_EQUIPPABLE'; end if;
  v_char := coalesce(it.character_id, case when v_slot in ('skin', 'weapon') then p_character else '*' end, '*');
  if it.character_id is not null and p_character is not null and it.character_id <> p_character then
    raise exception 'WRONG_CHARACTER';
  end if;
  insert into player_equipment (player_id, slot, character_id, item_id) values (v, v_slot, v_char, p_item)
  on conflict (player_id, slot, character_id) do update set item_id = excluded.item_id, equipped_at = now();
  return jsonb_build_object('ok', true, 'slot', v_slot, 'character', v_char);
end $$;

create or replace function public.unequip_item(p_slot text, p_character text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid();
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  delete from player_equipment where player_id = v and slot = p_slot and character_id in (coalesce(p_character, '*'), '*');
  return '{"ok":true}';
end $$;

-- Consume one unit. Consumables only take effect in Training/PvE (enforced by the client game mode
-- and never accepted by the PvP server); chests open into their announced fixed contents.
create or replace function public.use_item(p_item text, p_request_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v uuid := auth.uid(); it items; v_ref text;
begin
  if v is null then raise exception 'NOT_AUTHENTICATED'; end if;
  v_ref := 'use:' || v || ':' || p_request_id;
  if exists (select 1 from reward_grants where ref = v_ref) then return '{"ok":true,"duplicate":true}'; end if;
  select * into it from items where id = p_item;
  if not found or it.category not in ('consumable', 'chest') then raise exception 'NOT_USABLE'; end if;
  update player_inventory set quantity = quantity - 1
   where player_id = v and item_id = p_item and quantity > 0 and (expires_at is null or expires_at > now());
  if not found then raise exception 'NOT_OWNED'; end if;
  delete from player_inventory where player_id = v and item_id = p_item and quantity = 0;
  if it.category = 'chest' then
    perform grant_reward(v, v_ref, 'chest:' || it.id, coalesce(it.gameplay_effect -> 'contents', '{}'::jsonb));
  else
    insert into reward_grants (player_id, ref, source, rewards) values (v, v_ref, 'use:' || it.id, '{}'::jsonb);
  end if;
  return jsonb_build_object('ok', true, 'effect', it.gameplay_effect);
end $$;

-- =====================================================================
-- Seed data (editable later through the admin API)
-- =====================================================================
insert into public.items (id, name, description, category, rarity, acquisition_type, price, currency_type, stackable, max_stack, duration_hours, character_id, gameplay_effect) values
 ('health_potion', 'Health Potion', 'Hồi 300 HP (Training / PvE).', 'consumable', 'common', 'shop', 120, 'gold', true, 99, null, null, '{"training":{"heal":300}}'),
 ('energy_potion', 'Energy Potion', 'Hồi đầy năng lượng (Training / PvE).', 'consumable', 'common', 'shop', 150, 'gold', true, 99, null, null, '{"training":{"energy":100}}'),
 ('shield_scroll', 'Shield Scroll', 'Bất tử 3 giây (Training / PvE).', 'consumable', 'rare', 'shop', 300, 'gold', true, 20, null, null, '{"training":{"shield_ticks":90}}'),
 ('speed_boost', 'Speed Boost', 'Tăng tốc 5 giây (Training / PvE).', 'consumable', 'rare', 'shop', 250, 'gold', true, 20, null, null, '{"training":{"speed_ticks":150}}'),
 ('chest_basic', 'Rương cơ bản', 'Luôn chứa: 2 Health Potion, 1 Energy Potion và 150 Gold.', 'chest', 'common', 'quest', 0, 'none', true, 50, null, null, '{"contents":{"gold":150,"items":[{"id":"health_potion","qty":2},{"id":"energy_potion","qty":1}]}}'),
 ('shadow_katana', 'Shadow Katana', 'Lưỡi kiếm tím phát sáng (ngoại hình).', 'weapon', 'epic', 'shop', 400, 'gems', false, 1, null, 'kaze', '{"trail":"#B34BFF","blade":"#E9D5FF"}'),
 ('flame_sword', 'Flame Sword', 'Găng lửa rực cháy (ngoại hình).', 'weapon', 'epic', 'shop', 400, 'gems', false, 1, null, 'ryujin', '{"trail":"#FF7A1F","blade":"#FFD23B"}'),
 ('thunder_gauntlets', 'Thunder Gauntlets', 'Găng sét xanh (ngoại hình).', 'weapon', 'epic', 'shop', 400, 'gems', false, 1, null, 'akira', '{"trail":"#3BD5FF","blade":"#FFFFFF"}'),
 ('guardian_armor', 'Guardian Armor', '+15% phòng thủ chỉ trong Training/PvE. Ranked dùng chỉ số chuẩn.', 'equipment', 'rare', 'shop', 2000, 'gold', false, 1, null, null, '{"training":{"defense_pct":15}}'),
 ('skin_kaze_crimson', 'Kaze · Crimson Night', 'Trang phục đỏ thẫm.', 'skin', 'rare', 'shop', 3000, 'gold', false, 1, null, 'kaze', '{"palette":{"main":"#7A1F2B","dark":"#2A0A10","accent":"#FF3B5C","hair":"#1C1A33"}}'),
 ('skin_kaze_gold', 'Kaze · Golden Shinobi', 'Trang phục vàng huyền thoại.', 'skin', 'legendary', 'premium', 900, 'gems', false, 1, null, 'kaze', '{"palette":{"main":"#3A2A0A","dark":"#1A1204","accent":"#FFC93B","hair":"#F5F5F5"}}'),
 ('skin_ryujin_frost', 'Ryujin · Frost Dragon', 'Rồng băng.', 'skin', 'epic', 'shop', 600, 'gems', false, 1, null, 'ryujin', '{"palette":{"main":"#2F6BFF","dark":"#0E1F4A","accent":"#7FE3FF","hair":"#DDF3FF"}}'),
 ('skin_ryujin_obsidian', 'Ryujin · Obsidian', 'Giáp đá đen.', 'skin', 'rare', 'shop', 3000, 'gold', false, 1, null, 'ryujin', '{"palette":{"main":"#2A2A33","dark":"#0E0E12","accent":"#FF5A12","hair":"#FF3B1F"}}'),
 ('skin_akira_neon', 'Akira · Neon Runner', 'Áo khoác neon.', 'skin', 'rare', 'shop', 3000, 'gold', false, 1, null, 'akira', '{"palette":{"main":"#1A1F38","dark":"#FF3BD5","accent":"#FF3BD5","hair":"#3BD5FF"}}'),
 ('skin_akira_storm', 'Akira · Storm God', 'Thần sấm thần thoại.', 'skin', 'mythic', 'premium', 1500, 'gems', false, 1, null, 'akira', '{"palette":{"main":"#FFF27A","dark":"#3B2A8F","accent":"#FFF27A","hair":"#FFFFFF"}}'),
 ('fx_kill_fireworks', 'Kill Effect · Fireworks', 'Pháo hoa khi hạ gục.', 'effect', 'epic', 'shop', 350, 'gems', false, 1, null, null, '{"kill":"fireworks"}'),
 ('fx_spawn_lightning', 'Spawn Effect · Lightning', 'Xuất hiện bằng tia sét.', 'effect', 'rare', 'shop', 1500, 'gold', false, 1, null, null, '{"spawn":"lightning"}'),
 ('emote_gg', 'Emote · GG', 'Chào trận đấu.', 'emote', 'common', 'shop', 500, 'gold', false, 1, null, null, '{}'),
 ('nameplate_rookie', 'Nameplate · Rookie', 'Bảng tên tân binh.', 'nameplate', 'common', 'starter', 0, 'none', false, 1, null, null, '{"color":"#8FA3FF"}'),
 ('nameplate_champion', 'Nameplate · Champion', 'Dành cho nhà vô địch.', 'nameplate', 'legendary', 'quest', 0, 'none', false, 1, null, null, '{"color":"#FFC93B"}'),
 ('skin_trial_akira_storm', 'Akira · Storm God (7 ngày)', 'Bản dùng thử 7 ngày.', 'skin', 'epic', 'event', 0, 'none', false, 1, 168, 'akira', '{"palette":{"main":"#FFF27A","dark":"#3B2A8F","accent":"#FFF27A","hair":"#FFFFFF"}}')
on conflict (id) do nothing;

insert into public.quests (id, type, title, description, reward, sort) values
 ('d_play3', 'daily', 'Chơi 3 trận', 'Hoàn thành 3 trận online.', '{"gold":200}', 1),
 ('d_kill5', 'daily', 'Hạ gục 5 đối thủ', 'Hạ gục 5 lần trong các trận online.', '{"gold":100}', 2),
 ('d_win2', 'daily', 'Thắng 2 trận', 'Đứng hạng nhất 2 trận.', '{"gold":150}', 3),
 ('d_dmg5000', 'daily', 'Gây 5.000 sát thương', 'Sát thương hợp lệ do server ghi nhận.', '{"items":[{"id":"chest_basic","qty":1}]}', 4),
 ('d_tdm1', 'daily', 'Một trận Team Deathmatch', 'Hoàn thành 1 trận TDM.', '{"gold":100}', 5),
 ('w_win15', 'weekly', 'Thắng 15 trận', 'Trong tuần này.', '{"gold":1500,"items":[{"id":"chest_basic","qty":2}]}', 10),
 ('w_kill50', 'weekly', 'Hạ gục 50 đối thủ', 'Trong tuần này.', '{"gold":1000}', 11),
 ('w_play20', 'weekly', 'Hoàn thành 20 trận', 'Trong tuần này.', '{"gold":800,"items":[{"id":"shield_scroll","qty":2}]}', 12),
 ('w_assist10', 'weekly', '10 lần hỗ trợ', 'Trong tuần này.', '{"gold":600}', 13),
 ('a_first_blood', 'achievement', 'First Blood', 'Hạ gục người chơi đầu tiên.', '{"gold":300}', 20),
 ('a_hunter', 'achievement', 'Arena Hunter', 'Đạt tổng 100 hạ gục.', '{"gold":2000,"items":[{"id":"fx_spawn_lightning","qty":1}]}', 21),
 ('a_untouchable', 'achievement', 'Untouchable', 'Thắng 5 trận liên tiếp.', '{"gems":50}', 22),
 ('a_champion', 'achievement', 'Champion', 'Đạt rating 1400 (Rank Gold).', '{"items":[{"id":"nameplate_champion","qty":1}]}', 23),
 ('a_veteran', 'achievement', 'Veteran', 'Hoàn thành 100 trận.', '{"gold":3000}', 24)
on conflict (id) do nothing;

insert into public.quest_objectives (quest_id, metric, target) values
 ('d_play3', 'matches_completed', 3), ('d_kill5', 'kills', 5), ('d_win2', 'wins', 2), ('d_dmg5000', 'damage', 5000),
 ('d_tdm1', 'tdm_completed', 1), ('w_win15', 'wins', 15), ('w_kill50', 'kills', 50), ('w_play20', 'matches_completed', 20),
 ('w_assist10', 'assists', 10), ('a_first_blood', 'kills', 1), ('a_hunter', 'kills', 100), ('a_untouchable', 'win_streak', 5),
 ('a_champion', 'rating', 1400), ('a_veteran', 'matches_completed', 100)
on conflict (quest_id, metric) do nothing;

insert into public.shop_products (id, section, kind, item_id, quantity, gems, price, currency, title, sort) values
 ('p_health_potion', 'consumables', 'item', 'health_potion', 1, 0, 120, 'gold', 'Health Potion', 1),
 ('p_energy_potion', 'consumables', 'item', 'energy_potion', 1, 0, 150, 'gold', 'Energy Potion', 2),
 ('p_shield_scroll', 'consumables', 'item', 'shield_scroll', 1, 0, 300, 'gold', 'Shield Scroll', 3),
 ('p_speed_boost', 'consumables', 'item', 'speed_boost', 1, 0, 250, 'gold', 'Speed Boost', 4),
 ('p_guardian_armor', 'weapons', 'item', 'guardian_armor', 1, 0, 2000, 'gold', 'Guardian Armor (Training)', 5),
 ('p_shadow_katana', 'weapons', 'item', 'shadow_katana', 1, 0, 400, 'gems', 'Shadow Katana', 6),
 ('p_flame_sword', 'weapons', 'item', 'flame_sword', 1, 0, 400, 'gems', 'Flame Sword', 7),
 ('p_thunder_gauntlets', 'weapons', 'item', 'thunder_gauntlets', 1, 0, 400, 'gems', 'Thunder Gauntlets', 8),
 ('p_skin_kaze_crimson', 'skins', 'item', 'skin_kaze_crimson', 1, 0, 3000, 'gold', 'Kaze · Crimson Night', 10),
 ('p_skin_ryujin_obsidian', 'skins', 'item', 'skin_ryujin_obsidian', 1, 0, 3000, 'gold', 'Ryujin · Obsidian', 11),
 ('p_skin_akira_neon', 'skins', 'item', 'skin_akira_neon', 1, 0, 3000, 'gold', 'Akira · Neon Runner', 12),
 ('p_skin_ryujin_frost', 'featured', 'item', 'skin_ryujin_frost', 1, 0, 600, 'gems', 'Ryujin · Frost Dragon', 13),
 ('p_skin_kaze_gold', 'featured', 'item', 'skin_kaze_gold', 1, 0, 900, 'gems', 'Kaze · Golden Shinobi', 14),
 ('p_skin_akira_storm', 'limited', 'item', 'skin_akira_storm', 1, 0, 1500, 'gems', 'Akira · Storm God', 15),
 ('p_fx_kill_fireworks', 'effects', 'item', 'fx_kill_fireworks', 1, 0, 350, 'gems', 'Kill Effect · Fireworks', 16),
 ('p_fx_spawn_lightning', 'effects', 'item', 'fx_spawn_lightning', 1, 0, 1500, 'gold', 'Spawn Effect · Lightning', 17),
 ('p_emote_gg', 'effects', 'item', 'emote_gg', 1, 0, 500, 'gold', 'Emote · GG', 18),
 ('gems_starter', 'currency', 'gems_pack', null, 1, 100, 20000, 'vnd', 'Starter Pack · 100 Gems', 30),
 ('gems_warrior', 'currency', 'gems_pack', null, 1, 300, 50000, 'vnd', 'Warrior Pack · 300 Gems', 31),
 ('gems_hero', 'currency', 'gems_pack', null, 1, 700, 100000, 'vnd', 'Hero Pack · 700 Gems', 32),
 ('gems_legend', 'currency', 'gems_pack', null, 1, 1500, 200000, 'vnd', 'Legend Pack · 1.500 Gems', 33)
on conflict (id) do nothing;

-- existing accounts get a wallet + starter kit (idempotent)
do $$ declare r record; begin
  for r in select id from public.profiles loop perform public.ensure_player_economy(r.id); end loop;
end $$;

-- =====================================================================
-- Privileges + RLS
-- =====================================================================
revoke all on function public.add_currency(uuid, text, bigint, text, text) from public, anon, authenticated;
revoke all on function public.add_item(uuid, text, int, text) from public, anon, authenticated;
revoke all on function public.grant_reward(uuid, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.ensure_player_economy(uuid) from public, anon, authenticated;
revoke all on function public.record_player_match(jsonb) from public, anon, authenticated;
revoke all on function public.create_payment_order(uuid, text, text, int) from public, anon, authenticated;
revoke all on function public.fulfill_payment(jsonb) from public, anon, authenticated;
revoke all on function public.expire_payment_orders() from public, anon, authenticated;
revoke all on function public.admin_resolve_order(uuid, uuid, text, text) from public, anon, authenticated;
grant execute on function public.record_player_match(jsonb), public.create_payment_order(uuid, text, text, int),
  public.fulfill_payment(jsonb), public.expire_payment_orders(), public.admin_resolve_order(uuid, uuid, text, text),
  public.ensure_player_economy(uuid), public.grant_reward(uuid, text, text, jsonb) to service_role;

revoke all on function public.init_my_account(), public.claim_daily_login(), public.claim_quest(text),
  public.buy_product(text, uuid), public.equip_item(text, text), public.unequip_item(text, text), public.use_item(text, uuid) from public, anon;
grant execute on function public.init_my_account(), public.claim_daily_login(), public.claim_quest(text),
  public.buy_product(text, uuid), public.equip_item(text, text), public.unequip_item(text, text), public.use_item(text, uuid) to authenticated;

alter table public.admins enable row level security;
alter table public.audit_log enable row level security;
alter table public.items enable row level security;
alter table public.player_equipment enable row level security;
alter table public.wallets enable row level security;
alter table public.currency_ledger enable row level security;
alter table public.reward_grants enable row level security;
alter table public.quests enable row level security;
alter table public.quest_objectives enable row level security;
alter table public.player_quest_progress enable row level security;
alter table public.quest_reward_claims enable row level security;
alter table public.quest_events enable row level security;
alter table public.player_counters enable row level security;
alter table public.daily_logins enable row level security;
alter table public.shop_products enable row level security;
alter table public.payment_orders enable row level security;
alter table public.payment_transactions enable row level security;
alter table public.purchase_history enable row level security;

drop policy if exists "catalog items" on public.items;
create policy "catalog items" on public.items for select to anon, authenticated using (enabled);
drop policy if exists "catalog quests" on public.quests;
create policy "catalog quests" on public.quests for select to authenticated using (enabled);
drop policy if exists "catalog objectives" on public.quest_objectives;
create policy "catalog objectives" on public.quest_objectives for select to authenticated using (true);
drop policy if exists "catalog shop" on public.shop_products;
create policy "catalog shop" on public.shop_products for select to anon, authenticated using (enabled);

drop policy if exists "own equipment" on public.player_equipment;
create policy "own equipment" on public.player_equipment for select to authenticated using (true); -- cosmetics are public
drop policy if exists "own wallet" on public.wallets;
create policy "own wallet" on public.wallets for select to authenticated using (player_id = auth.uid());
drop policy if exists "own ledger" on public.currency_ledger;
create policy "own ledger" on public.currency_ledger for select to authenticated using (player_id = auth.uid());
drop policy if exists "own grants" on public.reward_grants;
create policy "own grants" on public.reward_grants for select to authenticated using (player_id = auth.uid());
drop policy if exists "own progress" on public.player_quest_progress;
create policy "own progress" on public.player_quest_progress for select to authenticated using (player_id = auth.uid());
drop policy if exists "own claims" on public.quest_reward_claims;
create policy "own claims" on public.quest_reward_claims for select to authenticated using (player_id = auth.uid());
drop policy if exists "own counters" on public.player_counters;
create policy "own counters" on public.player_counters for select to authenticated using (player_id = auth.uid());
drop policy if exists "own logins" on public.daily_logins;
create policy "own logins" on public.daily_logins for select to authenticated using (player_id = auth.uid());
drop policy if exists "own orders" on public.payment_orders;
create policy "own orders" on public.payment_orders for select to authenticated using (player_id = auth.uid());
drop policy if exists "own purchases" on public.purchase_history;
create policy "own purchases" on public.purchase_history for select to authenticated using (player_id = auth.uid());
-- admins, audit_log, quest_events, payment_transactions: no client policies at all (server only)

revoke insert, update, delete on all tables in schema public from anon, authenticated;
grant select on all tables in schema public to anon, authenticated;
grant update (username, avatar_character, updated_at) on public.profiles to authenticated;
grant all on all tables in schema public to service_role;
grant all on all sequences in schema public to service_role;

-- payment status updates reach the phone through Realtime (RLS: only the owner receives them)
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin alter publication supabase_realtime add table public.payment_orders; exception when duplicate_object then null; end;
    begin alter publication supabase_realtime add table public.wallets; exception when duplicate_object then null; end;
  end if;
end $$;
