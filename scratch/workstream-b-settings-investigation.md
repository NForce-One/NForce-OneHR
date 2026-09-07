# Workstream B — Org-Wide Attendance/Regularization Settings: Investigation (Pre-Implementation)

**Scope:** Investigation only. No files modified.
**Date:** 2026-09-06
**Branch:** `enhancement/check-in_check-out`
**Precondition:** Workstream A (Employee↔Shift hard invariant, V160/V161) is COMPLETE and out of scope here.

Current YAML (`backend/src/main/resources/application.yml`, `app.attendance.*`):

```yaml
late-grace-minutes: 10
half-day-max-hours: 3.5
full-day-min-hours: 7.5
daily-break-budget-minutes: 60
regularization:
  employee-lookback-days: 7
  monthly-limit: 3
```

Bound via `AttendanceProperties` (`@ConfigurationProperties(prefix="app.attendance")`) for the first four,
and two loose `@Value("${app.attendance.regularization.*}")` fields directly on `RegularizationService`
for the last two — there is no `RegularizationProperties` class.

---

## Audit Table

| Setting | Consumers | Behavioral/display | Org-wide | Existing equivalent | Frontend usage | Recommendation |
|---|---|---|---|---|---|---|
| `late-grace-minutes` | `AttendanceService` (live check-in late-calc, HALF_DAY/status derivation x3), `WebClockInService.recomputeDerivedFields`, `RegularizationService.recomputeDerivedFields` (approval-time recompute), `AttendanceConfigResponse.lateGraceMinutes` | **Behavioral** — directly gates LATE status (`shiftStart+grace` deadline) in 3 independent call sites | Yes | `PenalizationPolicyVersion.laGracePeriodMinutes` is a **different**, already-persisted, per-policy-version concept (an extra grace subtracted from `minutesLate` only inside `ExceptionService`'s TOTAL_HOURS late-arrival penalty tally) — NOT the same as the org-wide LATE/PRESENT gate. Do not conflate. | Read-only display (`config.lateGraceMinutes` shown as "grace 10m" next to shift times, and passed into `LateBadge`/`ArrivalCell` for the turtle-icon/late-by-minutes UI) | **Migrate** (per decision) |
| `half-day-max-hours` | `AttendanceService` (x3: `getToday`, `finalizeStatusPastShiftEnd`, `checkOut`'s recompute), `WebClockInService.finalizeStatusIfShiftEnded`, `RegularizationService.recomputeDerivedFields`, `AttendanceConfigResponse.halfDayMaxHours` | **Behavioral** — directly gates HALF_DAY status in 5 independent call sites | Yes | None — absolute-hours threshold has no other representation anywhere | Read-only (not directly rendered as text, but `hasMetFullEffectiveHours`/turtle-icon UX doesn't use it — it uses `fullDayMinHours` instead; `config.halfDayMaxHours` is fetched but I found no direct render of the number itself, only piped through) | **Migrate** (per decision) — preserve absolute-hours semantics, no percentage-of-shift redesign |
| `full-day-min-hours` | `AttendanceService.toResponse` **only** — writes `AttendanceResponse.fullDay` (a boolean) | **Dead in practice.** `AttendanceResponse.fullDay` has **zero readers** anywhere in backend or frontend (grepped both — only the one writer). Frontend's real "full day" concept (`hasMetFullEffectiveHours` / `fullDayTargetMinutesFor`) is independently computed from `config.shiftEnd`+`shiftStart` (the employee's actual assigned shift span) and only falls back to `config.fullDayMinHours` when `config.shiftEnd` is null — which the DTO's own Javadoc and `getConfig()`'s code confirm **cannot happen** now that Workstream A made `employee.shift` DB-NOT-NULL (`shift != null ? ... : null` is unreachable for a real employee row) | Was org-wide | `ExpectedWorkHoursService`/`ExceptionService.computeEffectiveHoursPercent` already supersede this entirely with the employee's actual assigned-shift duration (adjusted for approved partial-day leave) — this is the real, already-shipped "full day" concept | **Confirmed dead fallback path** — `fullDayTargetMinutesFor()` in `AttendancePage.tsx` still has the `?? config.fullDayMinHours * 60` fallback, but it's unreachable given the invariant | **Remove** — drop `fullDayMinHours` from `AttendanceProperties`/YAML/`AttendanceConfigResponse`/`AttendanceService.getConfig`, drop `AttendanceResponse.fullDay` field and its one write site, drop the frontend fallback branch (keep `fullDayTargetMinutesFor` returning purely from `config.shiftEnd`/`shiftStart`) |
| `daily-break-budget-minutes` | `AttendanceService` (x3: populates `TodayAttendanceResponse.breakBudgetMinutes` and `AttendanceConfigResponse.dailyBreakBudgetMinutes`) | **Display-only** — pure denominator for the Today's Timings break progress bar (`breakUsed / breakBudget`); actual break time (`computeBreakMinutes`) is only ever summed from real punch gaps and **never clamped, validated, or enforced** against this budget anywhere | Yes | **Not the same as `ShiftVersion.breakMinutes`** — proven, not assumed: `ShiftVersion.breakMinutes` is a **per-shift**, admin-entered value on the Shift form ("Break Duration (minutes)"), stored via `OrgService.createShift/updateShift`, surfaced only as static display text ("60 minutes" / "· 60m break") in `OrgSetupPage.tsx` — it has **zero readers** anywhere in `AttendanceService`/`ShiftDayPolicy`/any attendance-calculation path (grepped `getBreakMinutes()` repo-wide: only `OrgService` writes it, only `ShiftResponse`/`ShiftVersionResponse` read it back for display). Two genuinely separate, both-currently-inert-for-computation concepts: one is org-wide and drives a live per-employee UI budget bar; the other is per-shift and is pure static metadata. Do not merge. | Displayed as "`breakUsed`/`breakBudget` min" progress bar on the Today's Timings panel, with `config.dailyBreakBudgetMinutes` as a 60-default fallback if `today.breakBudgetMinutes` is absent | **Migrate as a separate concept** (per decision) — org-wide, keep display-only, introduce **no** enforcement |
| `regularization.employee-lookback-days` | `RegularizationService.submit`/`update` → `validateLookbackWindow` (enforced, Super Admin exempt) | **Behavioral** (server-enforced) | Yes | None | **Stale-risk hardcoded duplicate**: `AttendancePage.tsx` has its own `const REGULARIZATION_LOOKBACK_DAYS = 7`, used only for the calendar's `minDate` UX affordance — explicitly documented in its own comment as "UX convenience only, not the actual security boundary." If the value is migrated to an Admin-editable setting, this frontend constant silently goes stale the moment an admin changes it (backend still enforces correctly; only the calendar's *visual* min-date would drift). **Flagging as a required companion frontend change**, not optional. | **Migrate** (per decision) — plus surface the live value to frontend (e.g. add `employeeLookbackDays` onto the existing `RegularizationBalance` response, which the frontend already fetches, rather than a new endpoint) so the hardcoded constant can be replaced |
| `regularization.monthly-limit` | `RegularizationService.submit` → `assertMonthlyLimitNotExceeded` (enforced, Super Admin exempt), `RegularizationService.getBalance` (display) | **Behavioral** (server-enforced) + already-live display | Yes | None | **Already dynamic** — `getBalance()` already returns `limitCount` sourced from this same field, and `AttendancePage.tsx` already renders `balance.remainingCount/balance.limitCount` live from that response. No frontend staleness risk here (unlike lookback-days) — migrating the backend value requires **zero** frontend change. | **Migrate** (per decision) — no frontend follow-up needed |

---

## Notes on semantics that must survive migration exactly

- **Lateness formula**: `shiftStartAt.plusMinutes(lateGraceMinutes)` compared against `checkInAt`, anchored to the record's own `workDate` (not bare time-of-day) — same formula duplicated (deliberately, per existing comments) across `AttendanceService`, `WebClockInService`, and `RegularizationService`. All three must read the new setting; none may hardcode or drift.
- **Half-day threshold**: `workedMinutes < halfDayMaxHours * 60` — plain absolute comparison, no shift-duration-relative percentage. Must stay `double` (fractional hours, e.g. 3.5) — the existing `AttendanceProperties` field comment explains why `int` was insufficient.
- **Lookback window**: inclusive of today (`windowDays=7` with today=19th allows 19th–13th), Super Admin fully exempt, applies independently in both `submit()` and `update()`.
- **Monthly limit**: counts `createdAt` in `[monthStart, monthStart+1month)` on the **business month** (`regularizationBusinessToday()`, which itself uses `RegularizationService`'s own independent 07:00 boundary, `REGULARIZATION_DAY_BOUNDARY` — explicitly NOT `ShiftDayPolicy`'s shift-relative boundary, and explicitly not to be touched here), counts every status (a rejected request still consumed a slot), enforced only on `submit()` not `update()`, Super Admin exempt.
- **07:00 boundary itself** (`REGULARIZATION_DAY_BOUNDARY`) is out of scope for this workstream per the resumed instructions — it stays a `RegularizationService`-local constant, untouched.

---

## Target Architecture (proposal — not yet implemented)

### 1. Table / entity

One new singleton table, mirroring `shift_weekly_off_rules`/`ShiftWeeklyOffRules` exactly (DB-enforced singleton via `UNIQUE(singleton)` + `CHECK(singleton)`, seeded by the migration, `updated_at` auto-touch):

```
attendance_regularization_settings
  id                          UUID PK
  singleton                   BOOLEAN NOT NULL DEFAULT TRUE  (UNIQUE, CHECK)
  late_grace_minutes          INT NOT NULL DEFAULT 10   CHECK (>= 0)
  half_day_max_hours          NUMERIC(4,1) NOT NULL DEFAULT 3.5  CHECK (> 0)
  daily_break_budget_minutes  INT NOT NULL DEFAULT 60   CHECK (>= 0)
  employee_lookback_days      INT NOT NULL DEFAULT 7    CHECK (>= 1)
  monthly_limit               INT NOT NULL DEFAULT 3    CHECK (>= 0)
  updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
```

One table, not two — all five are "organization-wide attendance/regularization policy," the same grouping the resumed scope treats them as; a single admin panel and a single settings fetch keeps this proportionate to `ShiftWeeklyOffRules`'s one-value table rather than fragmenting into two near-empty tables. (Open to splitting into `attendance_settings` vs `regularization_settings` if the Admin UI ends up wanting them on physically separate screens — doesn't change the entity-per-concern pattern either way.)

`full-day-min-hours` and `Shift.flexible`-style dead weight are **not** in this table — they're removed, not migrated.

### 2. Flyway migration

`V162__create_attendance_regularization_settings.sql` — same shape as V158: `CREATE TABLE IF NOT EXISTS`, `CHECK`s mirroring today's validation, singleton `INSERT ... ON CONFLICT (singleton) DO NOTHING` seeded with the exact current YAML defaults (10, 3.5, 60, 7, 3) so no org's behavior changes on deploy. A second migration (or the same one) drops `full_day_min_hours`'s YAML key and the `AttendanceResponse.fullDay` column — there is none; it's a computed DTO field, not a persisted column, so no schema change needed for its removal, only code deletion.

### 3. Service

`AttendanceRegularizationSettingsService` (new), same shape as `ShiftWeeklyOffRulesService`:
- `getSettings()` → response DTO (public read, same as shift-weekly-off-rules' open-read convention — `RegularizationService`/`AttendanceService`/`WebClockInService` and the Admin UI all need it)
- typed plain-value getters for internal consumers (`getLateGraceMinutes()`, `getHalfDayMaxHours()`, `getDailyBreakBudgetMinutes()`, `getEmployeeLookbackDays()`, `getMonthlyLimit()`) — mirrors `getMaximumShiftDayDurationHours()`'s "the one value the consumer needs, as a plain type" convention, so `AttendanceService`/`WebClockInService`/`RegularizationService` swap `attendanceProps.getX()` → `attendanceRegularizationSettingsService.getX()` with no other call-site change
- `updateSettings(request)` → `@PreAuthorize("hasRole('SUPER_ADMIN')")`, same bound-checking style as `updateMaximumShiftDayDurationHours` (reject out-of-range with a 400 before it ever reaches the DB CHECK)
- `loadSingleton()` → fails loudly (`IllegalStateException`) if the seeded row is ever missing, same as today

`AttendanceProperties` loses `lateGraceMinutes`/`halfDayMaxHours`/`fullDayMinHours`/`dailyBreakBudgetMinutes` (keeps `zone` and `penalizationFallbackStrategy`, which stay genuinely deploy-time/non-admin-editable). `RegularizationService` loses its two `@Value` fields, gains a constructor dependency on the new service, keeps every other line of logic (including the untouched 07:00 boundary and late-arrival formula) unchanged.

### 4. DTO / controller

`AttendanceRegularizationSettingsResponse` (all 5 fields) + `UpdateAttendanceRegularizationSettingsRequest` (bean-validated bounds), new endpoints on `OrgController` (or a small new `AttendanceSettingsController` if `OrgController` is already large — check its current line count before deciding) mirroring the exact shape:

```
GET /api/org/attendance-regularization-settings   (open read)
PUT /api/org/attendance-regularization-settings   (SUPER_ADMIN)
```

`RegularizationBalance` (existing `getBalance()` response) gains one more field, `employeeLookbackDays`, so the frontend's hardcoded `REGULARIZATION_LOOKBACK_DAYS` constant has a live value to replace it with, without a second round-trip (the balance call already happens on the same screen at the same time the lookback window matters).

### 5. Admin UI location/UX

Reuse `OrgSetupPage.tsx`'s existing `shiftweeklyoff` tab family (`shifts` / `weeklyoffs` / `rules` sub-tabs) as the precedent, but this is a distinct concern from "Shifts & Weekly Off Rules" (that panel is specifically the single logical-workday-duration boundary; conflating it with late-grace/half-day/break-budget/regularization limits would overload one panel with unrelated settings). Two reasonable placements, both consistent with existing conventions:
- **(a)** A new top-level tab, `Attendance & Regularization Settings`, alongside `shiftweeklyoff`/`penalization`, same card-based single-panel-with-Save-button UX as the existing `rules` sub-tab (`dirty`-tracking, toast-on-save, SUPER_ADMIN-only edit).
- **(b)** A 4th sub-tab (`settings`) under the existing `shiftweeklyoff` tab, next to `rules`.

Recommend **(a)** — these settings aren't shift-specific, so nesting them under a "Shifts & Weekly Off" tab is a naming mismatch; a peer top-level tab matches how `penalization` already sits alongside it.

### 6. Properties/code to remove

- `AttendanceProperties.fullDayMinHours` (+ YAML key, + its `AttendanceConfigResponse.fullDayMinHours` field, + `AttendanceService.getConfig()`'s line populating it)
- `AttendanceResponse.fullDay` field and its one write site in `AttendanceService.toResponse`
- Frontend: `fullDayTargetMinutesFor`'s `?? config.fullDayMinHours * 60` fallback branch (keep the function, drop the dead branch and the `fullDayMinHours` field from the `AttendanceConfig` TS type)
- Frontend: `AttendancePage.tsx`'s hardcoded `REGULARIZATION_LOOKBACK_DAYS = 7` constant, replaced by reading `balance.employeeLookbackDays` (or an equivalent fetched value)

### 7. Tests

- New `AttendanceRegularizationSettingsServiceTest` — same shape as `ShiftWeeklyOffRulesServiceTest` (seeded-default read, missing-singleton-throws, bound-rejection cases per field, successful update).
- `AttendanceServiceTest`/`WebClockInServiceTest`/`RegularizationServiceTest`/`AttendanceServiceTeamStatsTest`/`AttendanceStatsServiceTest` — swap their existing `attendanceProps.getLateGraceMinutes()`/`getHalfDayMaxHours()` stubs for the new service's mock; behavior-preserving, so existing assertions should pass unchanged once the mock target moves.
- Remove/update any test asserting on `AttendanceResponse.fullDay` (e.g. check `ExceptionServiceDetectionTest` for a stray assertion) and any `fullDayMinHours` stub that's now dead.
- New: a regression test proving the frontend's `fullDayTargetMinutesFor` fallback removal doesn't change any current output (since the branch was already unreachable, this should be a no-op, not a behavior change — worth a direct assertion rather than an assumption).

### 8. Migration / backward-compatibility strategy

- Purely additive + subtractive, no data backfill needed — every new column is seeded with today's exact YAML default, so no existing org's runtime behavior changes on deploy.
- `full-day-min-hours`'s removal has no data to migrate (it was never persisted, only a config value) — just delete the YAML key, the properties field, the DTO field, and the one dead write site, in that order, verifying nothing else regresses.
- No `Attendance` table changes, no retroactive recomputation of historical `lateByMinutes`/`workedMinutes`/`status` — this workstream only relocates *where the current thresholds are configured from*, not what they compute; explicitly no behavior change for any existing record.
- Roll out as: (1) migration + entity + service + tests, (2) swap consumers off `AttendanceProperties`/`@Value`, verify byte-for-byte identical behavior via existing test suite, (3) controller + DTOs, (4) frontend Admin panel, (5) frontend consumer follow-ups (`REGULARIZATION_LOOKBACK_DAYS`, `fullDayMinHours` fallback removal) last, since they're independent of the backend cutover and lowest-risk to defer if time-boxed.

---

## STOP

This is investigation + proposed architecture only. No files have been modified. Awaiting go-ahead before implementing.

---

## ADDENDUM (2026-09-07, post-implementation re-verification): `laGracePeriodMinutes` has TWO downstream consumers, not one

Re-verifying the "does Tracking Policy already own grace minutes" question before closing this workstream turned up a
second production consumer of `PenalizationPolicyVersion.laGracePeriodMinutes` that the table row above (and the
`AttendanceProperties`/`application.yml`/`V162` comments written during implementation) undercounted as "only inside
`ExceptionService`'s TOTAL_HOURS penalty tally":

- `ConfiguredAttendancePolicyEngine.evaluateLateArrival` (line ~102) **also** reads it directly: if
  `ctx.getLateMinutes() <= v.getLaGracePeriodMinutes()`, the single late-arrival incident is exempted from any Late
  Arrival penalty outright ("Late minutes are within the configured grace period"). This runs regardless of
  `laBasis` (incident-based or total-hours), whereas the TOTAL_HOURS tally subtraction in `ExceptionService` only
  runs when `laBasis == TOTAL_HOURS`.
- `ctx.getLateMinutes()` is populated from `record.getLateByMinutes()` (`ExceptionService`'s own
  `PolicyEvaluationContext` builder) — i.e. the *already-computed* `Attendance.lateByMinutes`, which is itself
  produced by the YAML-gated `shiftStart + AttendanceProperties.lateGraceMinutes` deadline in
  `AttendanceService`/`WebClockInService`/`RegularizationService`.

So the accurate chain is: **YAML `late-grace-minutes` decides whether/how-late an arrival is at all → Tracking
Policy's `laGracePeriodMinutes` decides, downstream, whether that already-flagged lateness is penalty-eligible**
(twice over: once per-incident in `ConfiguredAttendancePolicyEngine`, once again in the TOTAL_HOURS tally). Neither
consumer of `laGracePeriodMinutes` writes back to or overrides `lateByMinutes`/LATE-PRESENT status — the conclusion
that Tracking Policy is not (yet) an alternate source of truth for the attendance-classification gate stands
unchanged; only the count and description of its downstream consumers needed correcting. Code comments (
`AttendanceProperties.lateGraceMinutes`, `application.yml`, `V162`'s migration header) were updated to reflect both
consumers accurately. No field was renamed — `laGracePeriodMinutes` is the actual, intentional field name (matching
the entity's own `la*` prefix convention: `laBasis`, `laExemptCount`, `laExemptPeriod`, `laDeductionDays`,
`laDeductionPerShifts`), not a typo for some `lateGracePeriodMinutes`, which does not exist anywhere in the codebase.
