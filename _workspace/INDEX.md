# _workspace 작업 목록 (INDEX)
모든 작업 보고서의 목차이자, 사람이 정해야 할 일을 모아 두는 결재함이다.

## 번호 규칙
- 다음 번호 = 이 파일 작업 목록의 마지막 번호 + 1. **지금 다음 번호: 64**
- `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 옮겨 정리할 때도 번호를 1로 되돌리지 않는다.
  (사고: 번호가 다시 시작돼 `7_quant_baseline-drift.md`·`12_impl_paper-file-logging.md` 인용이 옛 폴더 파일을 가리키게 됐다.)
- 옮긴 파일은 이름을 바꾸지 않고, 작업 목록의 "파일" 칸에 폴더째 적는다.
- 보고서를 새로 만들면 그 자리에서 작업 목록에 한 줄을 넣는다(리더 몫).

## 보고서 머리말 (모든 보고서 첫 3줄)
~~~
종류: 판정 | 요청 | 알림
사람 결정 필요: 예 | 아니오 (예면 무엇을)
이어지는 작업: 번호 또는 없음
~~~
- "사람 결정 필요: 예"인 보고서는 아래 결재함에 한 줄로 올린다. 결정이 나면 그 줄을 지우고 작업 목록의 상태를 바꾼다.
- 이 규칙 이전(63번까지)의 보고서에는 머리말이 없다 — 고쳐 쓰지 않는다.

## 문서 주인 (한 문서는 한 명만 고친다)
| 문서 | 주인 | 다른 사람은 |
|---|---|---|
| `CLAUDE.md` | 리더(오케스트레이터) | 보고서에 제안만 |
| `SCOPE_GUARDIAN.md` · `BACKLOG.md` | scope-guardian (안 부를 땐 리더) | 제안만 |
| `docs/BACKTEST-DESIGN.md` | strategy-quant | 제안만 |
| `_workspace/INDEX.md` | 리더 | 제안만 |

제안은 보고서의 `## 문서 수정 제안` 절에 "파일 · 고칠 줄 · 근거"로 적는다. 주인이 증거를 확인한 뒤 반영한다.
(사고: `SCOPE_GUARDIAN.md`에 "데드맨 스위치 실동작 확인"이 사실과 다르게 남아 있었다 — 2026-10-11 정정.)

## 결재함 (사람 결정 대기)
| 번호 | 무엇을 정하나 | 출처 | 올린 날 |
|---|---|---|---|
| 1 | 실전 계좌 전환(게이트 G2) — 모의 실적이 나면 넘어간다는 방침(2026-11-17). 선행: `docs/TRADING-RULES-AUDIT.md` CRITICAL 해소 | 사용자 지시 · CLAUDE.md 규칙 6 | 2026-11-17 |
| 2 | A동 왕복 비용 0.80% 칸 불합격을 예외로 둘지 (실전 승격 판단 때) | `59_quant` | 2026-11-14 |
| 3 | 1주 매수 상한 3배 유지 vs 사전 등록 규칙(2배) 복귀 | `61_quant` · `62_audit` M-1 | 2026-11-14 |
| 4 | 누적 낙폭 가드 8%를 코드 기본값으로 고정(지금은 대시보드 저장값에만 있음) | `38_ops` · CLAUDE.md 규칙 6 | 2026-10-10 |
| 5 | 기준선 yml(`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`)을 고친 엔진 + 현 A동 설정으로 다시 찍을지 | `56_quant` | 2026-11-13 |
| 6 | 종목당 10% 룰이 신규 매수 수량을 못 막음(M-4) · 칸 이름표 유실(M-3) 수정 — 백테스트 A/B 후 | `62_audit` | 2026-11-14 |
| 7 | 데드맨 스위치 감시 서비스 계정 만들고 `HEARTBEAT_URL` 넣기(사람만 가능) | `44_audit` §3-B | 2026-10-11 |
| 8 | 루트의 커밋된 `auto-start-paper.bat`(미추적)·저장소 루트 낡은 `.java` 중복 파일 정리 여부 | CLAUDE.md "낡은 중복 파일" | 2026-11-17 |
<!-- INBOX -->

## 작업 목록
상태 = 진행 | 끝 | 결정 대기 | 기록(옛 작업 — 상태는 파일 참고)

| 번호 | 주제 | 종류 | 상태 | 파일 |
|---|---|---|---|---|
| 1 | cost-lab | 구현 | 기록 | `_workspace_20260812_085606/1_impl_cost-lab.md` |
| 1.3 | fill-tracking-diagnosis | 설계 | 기록 | `_workspace_20260812_085606/1.3_design_fill-tracking-diagnosis.md` |
| 2 | cost-lab | 감사 | 기록 | `_workspace_20260812_085606/2_audit_cost-lab.md` |
| 3 | build | 검증 | 기록 | `_workspace_20260812_085606/3_verify_build.md` |
| 4 | cost-sensitivity | 판정 | 기록 | `_workspace_20260812_085606/4_quant_cost-sensitivity.md` |
| 5 | reproducibility | 구현 | 기록 | `_workspace_20260812_085606/5_impl_reproducibility.md` |
| 6 | reproducibility | 감사 | 기록 | `_workspace_20260812_085606/6_audit_reproducibility.md` |
| 7 | reproducibility | 검증 | 기록 | `_workspace_20260812_085606/7_verify_reproducibility.md` |
| 8 | rejudge | 판정 | 기록 | `_workspace_20260812_085606/8_quant_rejudge.md` |
| 9 | crash-vol | 구현 | 기록 | `_workspace_20260812_085606/9_impl_crash-vol.md` |
| 10 | crash-vol | 감사 | 기록 | `_workspace_20260812_085606/10_audit_crash-vol.md` |
| 11 | crash-vol | 검증 | 기록 | `_workspace_20260812_085606/11_verify_crash-vol.md` |
| 12 | crash-vol-refix | 감사 | 기록 | `_workspace_20260812_085606/12_audit_crash-vol-refix.md` |
| 13 | crash-vol | 판정 | 기록 | `_workspace_20260812_085606/13_quant_crash-vol.md` |
| 14 | extend-data | 구현 | 기록 | `_workspace_20260812_085606/14_impl_extend-data.md` |
| 15 | extended-crashvol | 판정 | 기록 | `_workspace_20260812_085606/15_quant_extended-crashvol.md` |
| 17 | bear-stress | 판정 | 기록 | `_workspace_20260812_085606/17_quant_bear-stress.md` |
| 18 | regime-filter | 구현 | 기록 | `_workspace_20260812_085606/18_impl_regime-filter.md` |
| 19 | regime-filter | 감사 | 기록 | `_workspace_20260812_085606/19_audit_regime-filter.md` |
| 20 | regime-lab | 판정 | 기록 | `_workspace_20260812_085606/20_quant_regime-lab.md` |
| 21 | regime-sens | 구현 | 기록 | `_workspace_20260812_085606/21_impl_regime-sens.md` |
| 22 | regime-sens | 감사 | 기록 | `_workspace_20260812_085606/22_audit_regime-sens.md` |
| 23 | regime-sens | 판정 | 기록 | `_workspace_20260812_085606/23_quant_regime-sens.md` |
| - | ADR-001-revision-draft | 기타 | 기록 | `_workspace_20260812_085606/ADR-001-revision-draft.md` |
| - | HANDOFF | 기타 | 기록 | `_workspace_20260812_085606/HANDOFF.md` |
| 1 | manual-buy-drill | 구현 | 기록 | `_workspace_20260901_203948/1_impl_manual-buy-drill.md` |
| 1 | peak-equity-fix | 구현 | 기록 | `_workspace_20260901_203948/1_impl_peak-equity-fix.md` |
| 2 | manual-buy-drill | 감사 | 기록 | `_workspace_20260901_203948/2_audit_manual-buy-drill.md` |
| 2 | peak-equity-fix | 감사 | 기록 | `_workspace_20260901_203948/2_audit_peak-equity-fix.md` |
| 3 | manual-buy-drill | 검증 | 기록 | `_workspace_20260901_203948/3_verify_manual-buy-drill.md` |
| 3 | peak-equity-fix | 검증 | 기록 | `_workspace_20260901_203948/3_verify_peak-equity-fix.md` |
| 4 | backtest-refactor | 구현 | 기록 | `_workspace_20260901_203948/4_impl_backtest-refactor.md` |
| 5 | backtest-refactor | 검증 | 기록 | `_workspace_20260901_203948/5_verify_backtest-refactor.md` |
| 6 | backtest-refactor | 감사 | 기록 | `_workspace_20260901_203948/6_audit_backtest-refactor.md` |
| 7 | baseline-drift | 판정 | 기록 | `_workspace_20260901_203948/7_quant_baseline-drift.md` |
| 7 | backtest-refactor | 리뷰 | 기록 | `_workspace_20260901_203948/7_review_backtest-refactor.md` |
| 8 | anchor-sot-and-coverage | 구현 | 기록 | `_workspace_20260901_203948/8_impl_anchor-sot-and-coverage.md` |
| 9 | anchor-sot-and-coverage | 감사 | 기록 | `_workspace_20260901_203948/9_audit_anchor-sot-and-coverage.md` |
| 10 | anchor-sot-and-coverage | 검증 | 기록 | `_workspace_20260901_203948/10_verify_anchor-sot-and-coverage.md` |
| 11 | audit-followup-3fixes | 구현 | 기록 | `_workspace_20260901_203948/11_impl_audit-followup-3fixes.md` |
| 12 | paper-file-logging | 구현 | 기록 | `_workspace_20260901_203948/12_impl_paper-file-logging.md` |
| 1 | posttimecut-buy-block | 구현 | 기록 | `_workspace/1_impl_posttimecut-buy-block.md` |
| 2 | posttimecut-buy-block | 감사 | 기록 | `_workspace/2_audit_posttimecut-buy-block.md` |
| 3 | posttimecut-buy-block | 검증 | 기록 | `_workspace/3_verify_posttimecut-buy-block.md` |
| 4 | timecut-retry-sweep | 구현 | 기록 | `_workspace/4_impl_timecut-retry-sweep.md` |
| 5 | timecut-retry-sweep | 감사 | 기록 | `_workspace/5_audit_timecut-retry-sweep.md` |
| 6 | timecut-retry-sweep | 검증 | 기록 | `_workspace/6_verify_timecut-retry-sweep.md` |
| 7 | equitystop-guard-ab | 판정 | 기록 | `_workspace/7_quant_equitystop-guard-ab.md` |
| 8 | equitystop-guard-bear-window | 판정 | 기록 | `_workspace/8_quant_equitystop-guard-bear-window.md` |
| 9 | equitystop-buy-guard | 감사 | 기록 | `_workspace/9_audit_equitystop-buy-guard.md` |
| 10 | donchian-stress-sizing | 판정 | 기록 | `_workspace/10_quant_donchian-stress-sizing.md` |
| 11 | cash-based-pnl | 구현 | 기록 | `_workspace/11_impl_cash-based-pnl.md` |
| 12 | cash-based-pnl | 감사 | 기록 | `_workspace/12_audit_cash-based-pnl.md` |
| 13 | daily-pnl-notify | 감사 | 기록 | `_workspace/13_audit_daily-pnl-notify.md` |
| 14 | pnl-notify-catchup | 구현 | 기록 | `_workspace/14_impl_pnl-notify-catchup.md` |
| 15 | pnl-notify-catchup | 감사 | 기록 | `_workspace/15_audit_pnl-notify-catchup.md` |
| 16 | overnight-prescreen | 판정 | 기록 | `_workspace/16_quant_overnight-prescreen.md` |
| 17 | peak-equity-fix | 운영 | 기록 | `_workspace/17_ops_peak-equity-fix.md` |
| 18 | peak-equity-applied | 운영 | 기록 | `_workspace/18_ops_peak-equity-applied.md` |
| 19 | account-performance | 구현 | 기록 | `_workspace/19_impl_account-performance.md` |
| 20 | account-performance | 감사 | 기록 | `_workspace/20_audit_account-performance.md` |
| 21 | bracket-exit | 판정 | 기록 | `_workspace/21_quant_bracket-exit.md` |
| 22 | dashboard-frontend | 구현 | 기록 | `_workspace/22_impl_dashboard-frontend.md` |
| 23 | history-and-trade-stats | 구현 | 기록 | `_workspace/23_impl_history-and-trade-stats.md` |
| 24 | history-and-trade-stats | 감사 | 기록 | `_workspace/24_audit_history-and-trade-stats.md` |
| 25 | safemode-flapping-diagnosis | 운영 | 기록 | `_workspace/25_ops_safemode-flapping-diagnosis.md` |
| 26 | cancel-retry-giveup | 구현 | 기록 | `_workspace/26_impl_cancel-retry-giveup.md` |
| 27 | safemode-flapping-fix | 구현 | 기록 | `_workspace/27_impl_safemode-flapping-fix.md` |
| 28 | cancel-gate-and-flapping | 감사 | 기록 | `_workspace/28_audit_cancel-gate-and-flapping.md` |
| 29 | trend-sleeve-switch | 구현 | 기록 | `_workspace/29_impl_trend-sleeve-switch.md` |
| 30 | trend-sleeve-switch | 감사 | 기록 | `_workspace/30_audit_trend-sleeve-switch.md` |
| 31 | after-hours-guards | 구현 | 기록 | `_workspace/31_impl_after-hours-guards.md` |
| 32 | after-hours-guards | 감사 | 기록 | `_workspace/32_audit_after-hours-guards.md` |
| 33 | trend-sleeve-day1 | 검증 | 기록 | `_workspace/33_verify_trend-sleeve-day1.md` |
| 34 | disclosure-cooldown-off | 운영 | 기록 | `_workspace/34_ops_disclosure-cooldown-off.md` |
| 35 | disclosure-cooldown-off | 감사 | 기록 | `_workspace/35_audit_disclosure-cooldown-off.md` |
| 36 | kis-server-stoploss | 조사 | 기록 | `_workspace/36_research_kis-server-stoploss.md` |
| 37 | mdd-guard-8pct | 감사 | 기록 | `_workspace/37_audit_mdd-guard-8pct.md` |
| 38 | mdd-guard-8pct | 운영 | 기록 | `_workspace/38_ops_mdd-guard-8pct.md` |
| 39 | nan-guard-stale-account | 구현 | 기록 | `_workspace/39_impl_nan-guard-stale-account.md` |
| 40 | nan-guard-stale-account | 감사 | 기록 | `_workspace/40_audit_nan-guard-stale-account.md` |
| 41 | scheduler-io-isolation | 구현 | 기록 | `_workspace/41_impl_scheduler-io-isolation.md` |
| 42 | scheduler-io-isolation | 감사 | 기록 | `_workspace/42_audit_scheduler-io-isolation.md` |
| 43 | feed-timeouts-token-mask | 구현 | 기록 | `_workspace/43_impl_feed-timeouts-token-mask.md` |
| 44 | feed-timeouts-token-mask | 감사 | 기록 | `_workspace/44_audit_feed-timeouts-token-mask.md` |
| 45 | sleeve-drawdown-caps | 구현 | 기록 | `_workspace/45_impl_sleeve-drawdown-caps.md` |
| 46 | sleeve-drawdown-caps | 감사 | 기록 | `_workspace/46_audit_sleeve-drawdown-caps.md` |
| 46b | sleeve-followups | 감사 | 기록 | `_workspace/46b_audit_sleeve-followups.md` |
| 47 | peak-spike-defense | 구현 | 기록 | `_workspace/47_impl_peak-spike-defense.md` |
| 48 | peak-spike-defense | 감사 | 기록 | `_workspace/48_audit_peak-spike-defense.md` |
| 49 | restart-sleeve-peak | 운영 | 기록 | `_workspace/49_ops_restart-sleeve-peak.md` |
| 50 | market-day-1012 | 운영 | 기록 | `_workspace/50_ops_market-day-1012.md` |
| 51 | accumulation-radar | 판정 | 기록 | `_workspace/51_quant_accumulation-radar.md` |
| 52 | accumulation-lab | 감사 | 기록 | `_workspace/52_audit_accumulation-lab.md` |
| 53 | reconnect-1013 | 운영 | 기록 | `_workspace/53_ops_reconnect-1013.md` |
| 54 | scale-in-full-account | 판정 | 끝(판정 보류 — 엔진 결함 발견) | `_workspace/54_quant_scale-in-full-account.md` |
| 55 | bear-relative-strength | 판정 | 끝(기각) | `_workspace/55_quant_bear-relative-strength.md` |
| 56 | stop-width-sweep | 판정 | 끝 | `_workspace/56_quant_stop-width-sweep.md` |
| 57 | no-stop-sensitivity | 판정 | 끝 | `_workspace/57_quant_no-stop-sensitivity.md` |
| 58 | fixed-pct-stop-with-trail | 판정 | 끝 | `_workspace/58_quant_fixed-pct-stop-with-trail.md` |
| 59 | sizing-up-with-15pct-stop | 판정 | 끝 | `_workspace/59_quant_sizing-up-with-15pct-stop.md` |
| 60 | stale-watchdog-pending-minshare | 감사 | 끝 | `_workspace/60_audit_stale-watchdog-pending-minshare.md` |
| 61 | min-one-share-cap | 판정 | 끝 | `_workspace/61_quant_min-one-share-cap.md` |
| 62 | trend-sleeve-100pct-fixed-stop | 감사 | 끝 | `_workspace/62_audit_trend-sleeve-100pct-fixed-stop.md` |
| 63 | bear-inverse-etf | 판정 | 끝(기각) | `_workspace/63_quant_bear-inverse-etf.md` |
<!-- ROWS -->
