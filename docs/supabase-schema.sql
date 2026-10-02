-- Supabase 운영 거울 테이블 (com.trading.mirror)
--
-- 실행 방법: Supabase 웹 → 왼쪽 메뉴 "SQL Editor" → 이 파일 내용을 붙여넣고 Run.
-- 한 번만 실행하면 된다. 앱은 이 테이블의 'paper' 행 하나를 5분마다 덮어쓴다.
--
-- 보안: RLS(행 보안)를 켜고 정책을 만들지 않는다.
--   → 공개용 publishable key로는 읽기도 쓰기도 막힌다(=인터넷에 새지 않는다).
--   → 앱이 쓰는 secret key(sb_secret_...)만 RLS를 통과한다.
--   → 사람은 Supabase 웹에 로그인해서 Table Editor로 본다.

create table if not exists public.trading_mirror (
  id                     text primary key,       -- 스냅샷 이름표 ('paper')
  updated_at             timestamptz not null,   -- 이 줄이 갱신된 시각(KST) = 앱이 살아있던 마지막 시각
  trading_mode           text,                   -- RUNNING / SAFE_MODE / EMERGENCY_STOPPED 등 운전 상태
  account_fresh          boolean,                -- false면 잔고 조회 실패로 낡은 값(참고용으로만 볼 것)
  total_asset_value      bigint,                 -- 예수금 포함 총자산(원)
  daily_pnl_percent      numeric,                -- 오늘 등락률(%) — 1.5면 +1.5%
  consecutive_loss_count integer,                -- 연속 손실 횟수(3회면 1시간 매수 중지)
  run_streak_days        integer,                -- 연속 무중단 가동 거래일수(릴리즈 목표 5일)
  unrealized_pnl         bigint,                 -- 보유 중 평가손익 합계(원)
  realized_pnl_today     bigint,                 -- 오늘 확정된 손익(원)
  holdings               jsonb                   -- 보유 종목 배열(종목코드·수량·평단가·현재가·손절선·지갑칸)
);

alter table public.trading_mirror enable row level security;

-- ---------------------------------------------------------------------------
-- DB 파일 백업 보관함 (backup-to-supabase.ps1이 쓴다)
--
-- 비공개 버킷: secret key로만 올리고 내려받을 수 있다.
-- 이 SQL 대신 Supabase 웹의 Storage 메뉴에서 'db-backup' 버킷을
-- Public 체크 없이 만들어도 똑같다.
-- ---------------------------------------------------------------------------

insert into storage.buckets (id, name, public)
values ('db-backup', 'db-backup', false)
on conflict (id) do nothing;
