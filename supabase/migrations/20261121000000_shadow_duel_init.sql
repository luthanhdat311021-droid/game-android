-- =====================================================================
-- SHADOW DUEL: MULTIPLAYER BATTLE ARENA — initial schema
-- Run in Supabase SQL Editor (or `supabase db push`).
--
-- Security model:
--   * Clients (anon / authenticated) can READ public data and edit only
--     their own profile name/avatar. They cannot write rooms, matches,
--     stats or rankings.
--   * The Node.js game server uses the service_role key (server-side only)
--     and writes results through record_match_result(), which is
--     idempotent on match_id and computes ratings inside one transaction.
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- profiles
create table if not exists public.profiles (
  id               uuid primary key references auth.users (id) on delete cascade,
  username         text not null unique
                   check (char_length(username) between 3 and 20 and username ~ '^[A-Za-z0-9_.-]+$'),
  avatar_character text not null default 'kaze',
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

-- ---------------------------------------------------------------- characters
create table if not exists public.characters (
  id           text primary key,
  name         text not null,
  title        text not null,
  hp           int  not null check (hp > 0),
  attack       int  not null check (attack > 0),
  defense      int  not null check (defense >= 0),
  speed        int  not null check (speed > 0),
  energy       int  not null default 100 check (energy > 0),
  special_name text not null,
  description  text not null default ''
);

insert into public.characters (id, name, title, hp, attack, defense, speed, special_name, description) values
  ('kaze',   'Kaze',   'Shadow Ninja',    900,  100, 35, 340, 'Shadow Slash',   'Nhanh nhẹn, combo liên tục, phi tiêu tầm xa.'),
  ('ryujin', 'Ryujin', 'Flame Warrior',   1100, 115, 60, 285, 'Flame Burst',    'Trâu bò, đòn nặng hất văng, vùng lửa diện rộng.'),
  ('akira',  'Akira',  'Thunder Fighter', 1000, 105, 45, 310, 'Thunder Strike', 'Cân bằng, tia sét xuyên thấu đánh trúng nhiều mục tiêu.')
on conflict (id) do update set
  name = excluded.name, title = excluded.title, hp = excluded.hp, attack = excluded.attack,
  defense = excluded.defense, speed = excluded.speed, special_name = excluded.special_name,
  description = excluded.description;

alter table public.profiles
  drop constraint if exists profiles_avatar_character_fkey,
  add constraint profiles_avatar_character_fkey foreign key (avatar_character) references public.characters (id);

-- ---------------------------------------------------------------- player_stats
create table if not exists public.player_stats (
  player_id          uuid primary key references public.profiles (id) on delete cascade,
  matches_played     int    not null default 0 check (matches_played >= 0),
  wins               int    not null default 0 check (wins >= 0),
  draws              int    not null default 0 check (draws >= 0),
  losses             int    not null default 0 check (losses >= 0),
  kills              int    not null default 0 check (kills >= 0),
  deaths             int    not null default 0 check (deaths >= 0),
  assists            int    not null default 0 check (assists >= 0),
  damage_dealt       bigint not null default 0 check (damage_dealt >= 0),
  damage_received    bigint not null default 0 check (damage_received >= 0),
  mvp_count          int    not null default 0 check (mvp_count >= 0),
  favorite_character text references public.characters (id),
  updated_at         timestamptz not null default now(),
  check (wins + draws + losses <= matches_played)
);

-- ---------------------------------------------------------------- rankings
create table if not exists public.rankings (
  player_id   uuid primary key references public.profiles (id) on delete cascade,
  rating      int not null default 1000 check (rating between 0 and 5000),
  peak_rating int not null default 1000 check (peak_rating between 0 and 5000),
  matches     int not null default 0 check (matches >= 0),
  wins        int not null default 0 check (wins >= 0),
  updated_at  timestamptz not null default now()
);
create index if not exists rankings_rating_idx on public.rankings (rating desc);

-- ---------------------------------------------------------------- rooms (mirror of live game-server rooms)
create table if not exists public.rooms (
  id              uuid primary key,
  code            text not null unique check (code ~ '^[A-Z0-9]{3,8}$'),
  name            text not null check (char_length(name) between 1 and 32),
  mode            text not null check (mode in ('ffa', 'tdm', 'survival')),
  map_id          text not null check (map_id in ('shadow_temple', 'flame_kingdom', 'cyber_city')),
  max_players     int  not null check (max_players between 2 and 8),
  current_players int  not null default 0,
  status          text not null default 'lobby' check (status in ('lobby', 'starting', 'in_match', 'results')),
  is_private      boolean not null default false,
  host_id         uuid references public.profiles (id) on delete set null,
  host_name       text not null default '',
  server_id       text not null,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  check (current_players between 0 and max_players)
);
create index if not exists rooms_status_idx on public.rooms (status, is_private);
create index if not exists rooms_server_idx on public.rooms (server_id);

create table if not exists public.room_players (
  room_id      uuid not null references public.rooms (id) on delete cascade,
  slot         int  not null check (slot between 0 and 7),
  player_id    uuid references public.profiles (id) on delete cascade,
  display_name text not null,
  character_id text not null references public.characters (id),
  team_id      int  not null default 0 check (team_id in (0, 1, 2)),
  is_ready     boolean not null default false,
  is_bot       boolean not null default false,
  is_host      boolean not null default false,
  joined_at    timestamptz not null default now(),
  primary key (room_id, slot)
);
create unique index if not exists room_players_unique_player on public.room_players (room_id, player_id) where player_id is not null;
create index if not exists room_players_player_idx on public.room_players (player_id);

-- capacity guard (defence in depth: the game server already enforces it atomically)
create or replace function public.enforce_room_capacity() returns trigger
language plpgsql as $$
declare
  cap int;
  n   int;
begin
  select max_players into cap from public.rooms where id = new.room_id for update;
  select count(*) into n from public.room_players where room_id = new.room_id;
  if n >= cap then
    raise exception 'room % is full (% / %)', new.room_id, n, cap using errcode = 'check_violation';
  end if;
  return new;
end $$;

drop trigger if exists room_players_capacity on public.room_players;
create trigger room_players_capacity before insert on public.room_players
  for each row execute function public.enforce_room_capacity();

-- ---------------------------------------------------------------- matches
create table if not exists public.matches (
  id         uuid primary key,
  room_code  text not null,
  mode       text not null check (mode in ('ffa', 'tdm', 'survival')),
  map_id     text not null,
  status     text not null default 'in_progress' check (status in ('in_progress', 'finished', 'aborted')),
  started_at timestamptz not null default now(),
  ended_at   timestamptz,
  duration_s int check (duration_s >= 0),
  created_at timestamptz not null default now()
);
create index if not exists matches_started_idx on public.matches (started_at desc);

create table if not exists public.match_participants (
  id              bigint generated always as identity primary key,
  match_id        uuid not null references public.matches (id) on delete cascade,
  slot            int  not null check (slot between 0 and 7),
  player_id       uuid references public.profiles (id) on delete set null,
  display_name    text not null,
  is_bot          boolean not null default false,
  team_id         int  not null default 0 check (team_id in (0, 1, 2)),
  character_id    text not null references public.characters (id),
  kills           int  not null default 0 check (kills >= 0),
  deaths          int  not null default 0 check (deaths >= 0),
  assists         int  not null default 0 check (assists >= 0),
  damage_dealt    int  not null default 0 check (damage_dealt >= 0),
  damage_received int  not null default 0 check (damage_received >= 0),
  score           int  not null default 0,
  placement       int  not null check (placement between 1 and 8),
  rating_change   int  not null default 0,
  is_mvp          boolean not null default false,
  left_early      boolean not null default false,
  unique (match_id, slot)
);
create unique index if not exists match_participants_player on public.match_participants (match_id, player_id) where player_id is not null;
create index if not exists match_participants_player_idx on public.match_participants (player_id, match_id);

create table if not exists public.match_results (
  match_id              uuid primary key references public.matches (id) on delete cascade,
  is_draw               boolean not null default false,
  winner_player_id      uuid references public.profiles (id) on delete set null,
  winner_team           int check (winner_team in (1, 2)),
  mvp_player_id         uuid references public.profiles (id) on delete set null,
  top_kills_player_id   uuid references public.profiles (id) on delete set null,
  most_damage_player_id uuid references public.profiles (id) on delete set null,
  summary               jsonb not null default '{}'::jsonb,
  recorded_at           timestamptz not null default now()
);

-- ---------------------------------------------------------------- inventory (cosmetics)
create table if not exists public.player_inventory (
  player_id   uuid not null references public.profiles (id) on delete cascade,
  item_id     text not null check (char_length(item_id) between 1 and 64),
  quantity    int  not null default 1 check (quantity >= 0),
  acquired_at timestamptz not null default now(),
  primary key (player_id, item_id)
);

-- ---------------------------------------------------------------- leaderboard view
create or replace view public.leaderboard with (security_invoker = true) as
select r.player_id, p.username, p.avatar_character, r.rating, r.peak_rating, r.matches, r.wins,
       s.kills, s.deaths, s.assists, s.mvp_count,
       rank() over (order by r.rating desc, r.wins desc) as position
from public.rankings r
join public.profiles p on p.id = r.player_id
left join public.player_stats s on s.player_id = r.player_id;

-- ---------------------------------------------------------------- new user bootstrap
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  base text;
  uname text;
  i int := 0;
begin
  base := regexp_replace(coalesce(new.raw_user_meta_data ->> 'username', split_part(new.email, '@', 1), 'player'),
                         '[^A-Za-z0-9_.-]', '', 'g');
  if char_length(base) < 3 then base := base || 'player'; end if;
  base := left(base, 15);
  uname := base;
  while exists (select 1 from public.profiles where username = uname) loop
    i := i + 1;
    uname := base || (floor(random() * 9000) + 1000)::int;
    exit when i > 20;
  end loop;
  insert into public.profiles (id, username) values (new.id, uname);
  insert into public.player_stats (player_id) values (new.id);
  insert into public.rankings (player_id) values (new.id);
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------- match start (service role only)
create or replace function public.record_match_start(p_match_id uuid, p_room_code text, p_mode text,
                                                     p_map_id text, p_started_at timestamptz)
returns void language sql security definer set search_path = public as $$
  insert into public.matches (id, room_code, mode, map_id, started_at)
  values (p_match_id, p_room_code, p_mode, p_map_id, p_started_at)
  on conflict (id) do nothing;
$$;

-- ---------------------------------------------------------------- match result (service role only, idempotent)
-- p_result = { match_id, room_code, mode, map_id, started_at, ended_at, duration_s, is_draw, winner_team,
--              winner_player_id, mvp_player_id, top_kills_player_id, most_damage_player_id, summary,
--              participants: [{ slot, player_id, display_name, is_bot, team_id, character_id, kills, deaths,
--                               assists, damage_dealt, damage_received, score, placement, is_mvp, left_early }] }
-- Returns { "<player uuid>": rating_change, ... }.
create or replace function public.record_match_result(p_result jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_match  uuid := (p_result ->> 'match_id')::uuid;
  v_k      numeric;
  v_n      int;
  v_out    jsonb := '{}'::jsonb;
  a        record;
  b        record;
  v_delta  numeric;
  v_expect numeric;
  v_actual numeric;
begin
  if v_match is null then raise exception 'match_id required'; end if;

  -- idempotency: a result that is already stored is returned unchanged
  if exists (select 1 from public.match_results where match_id = v_match) then
    select coalesce(jsonb_object_agg(player_id::text, rating_change), '{}'::jsonb) into v_out
      from public.match_participants where match_id = v_match and player_id is not null;
    return v_out;
  end if;

  -- serialise concurrent retries of the same match
  perform pg_advisory_xact_lock(hashtext(v_match::text));
  if exists (select 1 from public.match_results where match_id = v_match) then
    select coalesce(jsonb_object_agg(player_id::text, rating_change), '{}'::jsonb) into v_out
      from public.match_participants where match_id = v_match and player_id is not null;
    return v_out;
  end if;

  insert into public.matches (id, room_code, mode, map_id, status, started_at, ended_at, duration_s)
  values (v_match, p_result ->> 'room_code', p_result ->> 'mode', p_result ->> 'map_id', 'finished',
          (p_result ->> 'started_at')::timestamptz, (p_result ->> 'ended_at')::timestamptz,
          (p_result ->> 'duration_s')::int)
  on conflict (id) do update set status = 'finished', ended_at = excluded.ended_at, duration_s = excluded.duration_s;

  create temporary table tmp_p on commit drop as
  select x.slot, x.player_id, x.display_name, x.is_bot, x.team_id, x.character_id, x.kills, x.deaths, x.assists,
         x.damage_dealt, x.damage_received, x.score, x.placement, x.is_mvp, coalesce(x.left_early, false) as left_early,
         0::numeric as delta, 1000 as rating
  from jsonb_to_recordset(p_result -> 'participants') as x(
    slot int, player_id uuid, display_name text, is_bot boolean, team_id int, character_id text, kills int,
    deaths int, assists int, damage_dealt int, damage_received int, score int, placement int, is_mvp boolean,
    left_early boolean);

  -- unknown / deleted accounts are stored as anonymous rows
  update tmp_p t set player_id = null
   where t.player_id is not null and not exists (select 1 from public.profiles p where p.id = t.player_id);

  -- lock rankings rows of the human players and read current ratings
  perform 1 from public.rankings r where r.player_id in (select player_id from tmp_p where player_id is not null)
    order by r.player_id for update;
  update tmp_p t set rating = r.rating from public.rankings r where r.player_id = t.player_id;

  -- multiplayer Elo: every pair of ranked humans is a mini-duel decided by placement
  select count(*) into v_n from tmp_p where player_id is not null;
  if v_n >= 2 then
    v_k := 32.0 / (v_n - 1);
    for a in select * from tmp_p where player_id is not null loop
      v_delta := 0;
      for b in select * from tmp_p where player_id is not null and player_id <> a.player_id loop
        v_expect := 1.0 / (1.0 + power(10.0, (b.rating - a.rating) / 400.0));
        v_actual := case when a.placement < b.placement then 1.0 when a.placement = b.placement then 0.5 else 0.0 end;
        v_delta := v_delta + v_k * (v_actual - v_expect);
      end loop;
      update tmp_p set delta = round(v_delta) where player_id = a.player_id;
    end loop;
  end if;

  insert into public.match_participants (match_id, slot, player_id, display_name, is_bot, team_id, character_id,
      kills, deaths, assists, damage_dealt, damage_received, score, placement, rating_change, is_mvp, left_early)
  select v_match, slot, player_id, left(display_name, 32), coalesce(is_bot, false), coalesce(team_id, 0), character_id,
         greatest(kills, 0), greatest(deaths, 0), greatest(assists, 0), greatest(damage_dealt, 0), greatest(damage_received, 0),
         coalesce(score, 0), greatest(1, least(8, placement)), delta::int, coalesce(is_mvp, false), left_early
  from tmp_p;

  insert into public.match_results (match_id, is_draw, winner_player_id, winner_team, mvp_player_id,
      top_kills_player_id, most_damage_player_id, summary)
  values (v_match, coalesce((p_result ->> 'is_draw')::boolean, false),
          (select p.id from public.profiles p where p.id = (p_result ->> 'winner_player_id')::uuid),
          nullif(p_result ->> 'winner_team', '')::int,
          (select p.id from public.profiles p where p.id = (p_result ->> 'mvp_player_id')::uuid),
          (select p.id from public.profiles p where p.id = (p_result ->> 'top_kills_player_id')::uuid),
          (select p.id from public.profiles p where p.id = (p_result ->> 'most_damage_player_id')::uuid),
          coalesce(p_result -> 'summary', '{}'::jsonb));

  -- lifetime statistics
  insert into public.player_stats as s (player_id, matches_played, wins, draws, losses, kills, deaths, assists,
      damage_dealt, damage_received, mvp_count, favorite_character, updated_at)
  select t.player_id, 1,
         (t.placement = 1 and not coalesce((p_result ->> 'is_draw')::boolean, false))::int,
         (t.placement = 1 and coalesce((p_result ->> 'is_draw')::boolean, false))::int,
         (t.placement > 1)::int,
         t.kills, t.deaths, t.assists, t.damage_dealt, t.damage_received, t.is_mvp::int, t.character_id, now()
  from tmp_p t where t.player_id is not null
  on conflict (player_id) do update set
    matches_played  = s.matches_played + 1,
    wins            = s.wins + excluded.wins,
    draws           = s.draws + excluded.draws,
    losses          = s.losses + excluded.losses,
    kills           = s.kills + excluded.kills,
    deaths          = s.deaths + excluded.deaths,
    assists         = s.assists + excluded.assists,
    damage_dealt    = s.damage_dealt + excluded.damage_dealt,
    damage_received = s.damage_received + excluded.damage_received,
    mvp_count       = s.mvp_count + excluded.mvp_count,
    favorite_character = excluded.favorite_character,
    updated_at      = now();

  insert into public.rankings as r (player_id, rating, peak_rating, matches, wins, updated_at)
  select t.player_id, greatest(0, least(5000, t.rating + t.delta::int)), greatest(t.rating, t.rating + t.delta::int),
         1, (t.placement = 1 and not coalesce((p_result ->> 'is_draw')::boolean, false))::int, now()
  from tmp_p t where t.player_id is not null
  on conflict (player_id) do update set
    rating      = excluded.rating,
    peak_rating = greatest(r.peak_rating, excluded.rating),
    matches     = r.matches + 1,
    wins        = r.wins + excluded.wins,
    updated_at  = now();

  select coalesce(jsonb_object_agg(player_id::text, delta::int), '{}'::jsonb) into v_out
    from tmp_p where player_id is not null;
  return v_out;
end $$;

-- ---------------------------------------------------------------- privileges
revoke all on function public.record_match_result(jsonb) from public, anon, authenticated;
revoke all on function public.record_match_start(uuid, text, text, text, timestamptz) from public, anon, authenticated;
revoke all on function public.enforce_room_capacity() from public, anon, authenticated;
revoke all on function public.handle_new_user() from public, anon, authenticated;
grant execute on function public.record_match_result(jsonb) to service_role;
grant execute on function public.record_match_start(uuid, text, text, text, timestamptz) to service_role;

-- ---------------------------------------------------------------- Row Level Security
alter table public.profiles           enable row level security;
alter table public.characters         enable row level security;
alter table public.player_stats       enable row level security;
alter table public.rankings           enable row level security;
alter table public.rooms              enable row level security;
alter table public.room_players       enable row level security;
alter table public.matches            enable row level security;
alter table public.match_participants enable row level security;
alter table public.match_results      enable row level security;
alter table public.player_inventory   enable row level security;

-- public read
drop policy if exists "read profiles" on public.profiles;
create policy "read profiles" on public.profiles for select to anon, authenticated using (true);
drop policy if exists "read characters" on public.characters;
create policy "read characters" on public.characters for select to anon, authenticated using (true);
drop policy if exists "read stats" on public.player_stats;
create policy "read stats" on public.player_stats for select to anon, authenticated using (true);
drop policy if exists "read rankings" on public.rankings;
create policy "read rankings" on public.rankings for select to anon, authenticated using (true);
drop policy if exists "read public rooms" on public.rooms;
create policy "read public rooms" on public.rooms for select to authenticated
  using (not is_private or host_id = auth.uid()
         or exists (select 1 from public.room_players rp where rp.room_id = id and rp.player_id = auth.uid()));
drop policy if exists "read room players" on public.room_players;
-- (no sub-query on rooms here: rooms' policy already reads room_players, and
--  policies that query each other recurse forever)
create policy "read room players" on public.room_players for select to authenticated using (true);
drop policy if exists "read matches" on public.matches;
create policy "read matches" on public.matches for select to authenticated using (true);
drop policy if exists "read participants" on public.match_participants;
create policy "read participants" on public.match_participants for select to authenticated using (true);
drop policy if exists "read results" on public.match_results;
create policy "read results" on public.match_results for select to authenticated using (true);
drop policy if exists "read own inventory" on public.player_inventory;
create policy "read own inventory" on public.player_inventory for select to authenticated using (player_id = auth.uid());

-- a player may only change their own display name and avatar; nothing else
drop policy if exists "update own profile" on public.profiles;
create policy "update own profile" on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());
revoke insert, update, delete on all tables in schema public from anon, authenticated;
grant select on all tables in schema public to anon, authenticated;
grant update (username, avatar_character, updated_at) on public.profiles to authenticated;
-- newer Supabase projects no longer auto-grant table privileges to the server role
grant usage on schema public to service_role;
grant all on all tables in schema public to service_role;
grant all on all sequences in schema public to service_role;
-- Note: no INSERT/UPDATE/DELETE policies exist for stats, rankings, rooms, matches or results,
-- so clients cannot change kills, wins or rating even with a valid session.

-- ---------------------------------------------------------------- Realtime (lobby)
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin
      alter publication supabase_realtime add table public.rooms;
    exception when duplicate_object then null;
    end;
    begin
      alter publication supabase_realtime add table public.room_players;
    exception when duplicate_object then null;
    end;
  end if;
end $$;
