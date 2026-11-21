-- =====================================================================
-- Ranked matchmaking: only matches flagged "ranked" by the game server
-- change rating / the rankings table. Casual matches still save history,
-- stats and quests. (Replaces public.record_match_result.)
-- =====================================================================
alter table public.matches add column if not exists ranked boolean not null default false;

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
  v_ranked boolean := coalesce((p_result ->> 'ranked')::boolean, false);
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
  if v_ranked and v_n >= 2 then
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

  if v_ranked then
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
  end if;

  select coalesce(jsonb_object_agg(player_id::text, delta::int), '{}'::jsonb) into v_out
    from tmp_p where player_id is not null;
  return v_out;
end $$;

-- remember which matches were ranked
create or replace function public.mark_match_ranked() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if coalesce((new.summary ->> 'ranked')::boolean, false) then
    update public.matches set ranked = true where id = new.match_id;
  end if;
  return new;
end $$;
drop trigger if exists match_results_ranked on public.match_results;
create trigger match_results_ranked after insert on public.match_results
  for each row execute function public.mark_match_ranked();

revoke all on function public.record_match_result(jsonb) from public, anon, authenticated;
revoke all on function public.mark_match_ranked() from public, anon, authenticated;
grant execute on function public.record_match_result(jsonb) to service_role;
