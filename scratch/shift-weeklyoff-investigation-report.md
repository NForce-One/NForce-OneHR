# Shift + Weekly Off — Investigation Report (Pre-Implementation, v2)

**Scope:** Investigation only. No files modified, no migrations created, no UI changed, no code implemented.
**Date:** 2026-09-04
**Branch at time of investigation:** `enhancement/check-in_check-out`
**Reference image:** `prototypes/Shifts.png` (Keka-style Organization Masters → Shifts/Weekly Off screen)

This version reorganizes the same full-codebase investigation (Shift entity/DTOs/repos/services/OrgService/migrations/correctors, Employee↔Shift, all attendance logic, Weekly Off, and the frontend) into the 23-section structure requested, with "Historical Attendance Risks" pulled out as its own section.

---

## 1. Current Shift Architecture

- **Entity:** `entity/Shift.java` — flat JPA entity, no versioning, no history table, no relationship to `WeeklyOffPolicy`.
- **Fields:** `id, name, code, description, startTime, endTime, flexible, breakMinutes, workingDays, active, createdAt`.
- **Repository:** `ShiftRepository` — CRUD + name/code uniqueness lookups.
- **Service:** `OrgService` owns all Shift business logic — `listShifts`, `listShiftEmployees`, `createShift`, `updateShift`, `toggleShiftActive`, `deleteShift`. All mutating methods are `@PreAuthorize("hasRole('SUPER_ADMIN')")`; `listShifts` (read) is open to any authenticated user.
- **Controller:** `OrgController` (`/api/org/shifts*`) — thin passthrough to `OrgService`.
- **DTOs:** `CreateShiftRequest`/`UpdateShiftRequest` (identical field sets: name, code, description, startTime, endTime, flexible, breakMinutes, workingDays — `id`/`active`/`createdAt` never client-settable), `ShiftResponse` (adds `id, active, employeeCount, createdAt`), `ShiftEmployeeResponse` (employee drill-down).
- **Employee relation:** `Employee.shift` — `@ManyToOne(LAZY)`, FK `employees.shift_id`, nullable, no cascade.
- **Migrations:** `V95` (base table), `V129` (added code/description/flexible/break_minutes/working_days/active), `V100/V101/V117` (timing corrections to the seeded "Regular Shift"), `V123` (seeded additional overnight shifts).
- **Startup correctors:** `MigrationSeededShiftTimeFix` (one-time seed-timing correction) and `ShiftSeedCorrector` (runs on **every** startup, `@Order(HIGHEST_PRECEDENCE)`; backfills missing `employee.shift` and — critically — **rewrites historical `Attendance.lateByMinutes`/`status`** using each employee's *current* shift — see §8).
- **Frontend:** `OrgSetupPage.tsx` — one flat "Shifts" tab (table: Name/Code/Timing/Fixed-Flexible/Break/Employees/Status), `ShiftFormModal` (Add/Edit), `ShiftEmployeesModal` (drill-down). `ShiftFormModal` is reused by `UserManagementPage.tsx` for inline shift creation while assigning one to a user.

**Bottom line:** a simple, single-table, admin-managed master-data list with no assignment history, versioning, or org-level rules. Everything about "how a shift affects attendance" lives in `AttendanceProperties` (global config) plus duplicated logic in `AttendanceService` and its siblings (§3).

---

## 2. Current Weekly Off Architecture

- **Entity:** `WeeklyOffPolicy.java` — `id, name (unique), offDays (CSV of DayOfWeek), createdAt`.
- **No CRUD service or controller exists.** `OrgService` has zero `WeeklyOff*` methods. `WeeklyOffPolicyRepository` exposes only `findByName`.
- **Only created once**, by `V95`, seeding a single row: "Standard Weekly Off Policy" = `{SATURDAY, SUNDAY}`. No endpoint exists to create a second policy, edit `offDays`, or delete one.
- **Employee relation:** `Employee.weeklyOffPolicy` — a **sibling** `@ManyToOne(LAZY)` FK (`employees.weekly_off_policy_id`), added in the same migration as `Employee.shift`, structurally unrelated to `Shift` (no FK/join between `Shift` and `WeeklyOffPolicy`).
- **Only surface today:** read-only lookup (`EmployeeAssignmentService.getLookups` → `AssignmentLookupsResponse.weeklyOffPolicies`, `{id, name}` pairs) and bulk-assignment (`bulkUpdateWeeklyOff`, `POST /employee-assignments/bulk-update-weekly-off`) or CSV import (`importShiftsAndWeeklyOffs`). Admins can *assign* the one existing policy but cannot *author* one.
- **Real weekly-off logic** (`WorkingDayService.weeklyOffDaysOf`, `ExceptionService.weeklyOffDaysOf`, `AttendanceService.getConfig`) reads `Employee.weeklyOffPolicy.offDays`, falling back to hardcoded `{SATURDAY, SUNDAY}` when unassigned. **This is the real, working source of truth.**
- **Frontend:** no authoring UI exists anywhere. `MyTeamPage.tsx`'s Assignments tab has a weekly-off filter dropdown and a bulk "Update Weekly Off" action (assignment only); its own code documents the gap explicitly via a blocked "Shift & Weekly Off Requests" report card: *"Requires Shift & Weekly Off as a request workflow — not yet built."*

**Bottom line:** Weekly Off exists as a data model + assignment mechanism, but has **no authoring UI/API at all** — the single largest literal P1 gap.

---

## 3. Current Attendance/Shift-Day Flow

No single shift-day/attendance-day abstraction exists. Logic is scattered across five services with measurable drift:

| Computation | Canonical-ish owner | Duplicated in | Consistent? |
|---|---|---|---|
| Logical shift-day for a timestamp | `AttendanceService.shiftDayOf` (configurable `AttendanceProperties.shiftDayCutover`, default 07:00) | `WebClockInService.shiftDayOf` (literal copy) | Yes, between these two |
| — but also — | `RegularizationService.resolveBusinessDate` | uses **hardcoded** `REGULARIZATION_DAY_BOUNDARY = LocalTime.of(7,0)` — independent constant, not the configurable property | **No** — silently drifts if the org property is ever changed |
| Overnight rollover for regularization checkout | `RegularizationService.resolveTimes` (~lines 295-307) | ad hoc "if checkout clock-time < check-in clock-time on the submitted date, add a day" — a 4th, structurally different inference | Not shared with the other three |
| Shift start resolution (assigned shift, else global default) | `AttendanceService.resolveShiftStart` | `RegularizationService.resolveShiftStart`, `WebClockInService.resolveShiftStart`, inline in `ExceptionService.detectExceptions`, inline in `ShiftSeedCorrector.recomputeLateArrivals` — **5 independent copies** | Same rule, 5x maintenance surface |
| Shift end / overnight rollover (`end ≤ start` ⇒ next day) | `AttendanceService.shiftEndCutoff` | `WebClockInService.shiftEndCutoff` (literal copy), `WorkHoursShortageCalculationService.shiftBoundedGrossMinutes` (inlined) | Same rule, 3x copies |
| Stale/missing-checkout boundary | `AttendanceService.flagMissingCheckoutIfStale` — uses `shiftDayOf(now)` vs `record.getWorkDate()` (the **07:00 cutover**) | `AttendanceService.finalizeStatusPastShiftEnd` — uses `shiftEndCutoff` (the shift's **actual** natural end) | **No** — same sweeper answers "is this session over?" two different ways |
| Late-arrival minutes | `AttendanceService.checkIn`/`WebClockInService.recomputeDerivedFields`: from **shiftStartAt**, rounded **up** | `RegularizationService.recomputeDerivedFields`/`ShiftSeedCorrector.recomputeLateArrivals`: from **deadlineAt** (post-grace), **truncated** | **No** — same clock-in time yields different `lateByMinutes` depending which path last touched the row |
| Worked minutes | `AttendanceService.recomputeCombinedWorkedMinutes` — merges overlapping punch + web-clock-in intervals, capped at `shiftEndCutoff` | `RegularizationService.recomputeDerivedFields` — plain `Duration.between`, no cap/merge; `WorkHoursShortageCalculationService` — third, documented-approximate calc | **No** — three formulas |
| Expected work minutes | `ExpectedWorkHoursService.shiftMinutes` — `Duration.between(startTime, endTime)` on bare `LocalTime`, **no overnight handling** | n/a (single owner) but **wrong for overnight shifts** — see §7 | Confirmed bug |

**Overall assessment:** `AttendanceService` holds the closest thing to a correct "shift day policy," but as `private` methods, so every other consumer either copy-pasted it (with drift) or invented its own variant.

---

## 4. Shift-Related Parameters Audit

### Shift entity

| Field | Type | Default | Stored | Read by | Runtime effect? | Duplicated? | FE-only/BE-only | Legacy? | P1? |
|---|---|---|---|---|---|---|---|---|---|
| `id` | UUID | generated | Yes | Everywhere | Yes (PK/FK) | No | No | No | Required |
| `name` | String, unique | — | Yes | `OrgService` uniqueness, `ShiftSeedCorrector`/`MigrationSeededShiftTimeFix` (lookup by name), CSV import | Yes | No | No | No | **Required** |
| `code` | String, unique | — | Yes | Uniqueness check + `ShiftResponse` display | Display/validation only, no calc consumer | No | No | No | **Required** |
| `description` | String | — | Yes | `ShiftResponse` display | Display only (by design) | No | No | No | **Required** |
| `startTime` | LocalTime | — | Yes | `ExceptionService`, `ShiftSeedCorrector`, `AttendanceService`, `ExpectedWorkHoursService`, `WorkHoursShortageCalculationService` | **Yes — core** | No | No | No | **Required** |
| `endTime` | LocalTime | — | Yes | Same set as above + overnight rollover logic | **Yes — core** | No | No | No | **Required** |
| `flexible` | boolean, default false | Yes | Written (`OrgService` create/update) and read back (`ShiftResponse`) only | **No runtime effect anywhere** — grep across entire backend finds only these 3 references | No | No | No | **Dead / not P1-functional today** | Listed field, but functionally inert |
| `breakMinutes` | Integer | Yes | Written/read back (`ShiftResponse`) only | **No runtime effect** — actual break math uses global `AttendanceProperties.dailyBreakBudgetMinutes` (60 min) against real punch gaps, never this field | **Duplicated concept** (global break budget) | No | No | **Dead / duplicated** | Listed field, functionally inert |
| `workingDays` | String (CSV) | Yes | Only ever read in `ShiftResponse.from` for API echo | **No runtime effect** — real weekly-off/working-day decisions read exclusively `Employee.weeklyOffPolicy.offDays` | **Duplicated/conflicting concept** (Weekly Off) | No | No | **Dead / conflicting** | Conflicts with real P1 Weekly Off feature |
| `active` | boolean, default true | Yes | Read in 6 places (assignment gates + dropdown filter) | Yes, but narrowly — forward-looking assignment gate only, zero effect on attendance processing (§9) | No | No | No | **Required, semantics need documenting** |
| `createdAt` | LocalDateTime | Yes | Sort order in `listShifts` | Yes (minor) | No | No | No | Required (minor) |

### Global attendance config (`AttendanceProperties`, `@ConfigurationProperties(prefix="app.attendance")`, application.yml-backed, **not DB-backed, no runtime admin UI**)

| Property | Default | Role |
|---|---|---|
| `zone` | Asia/Kolkata | Timezone for punch attribution |
| `shiftStart` | 15:30 | Fallback shift start when employee has no assigned shift |
| `shiftDayCutover` | 07:00 | Global boundary deciding which shift-day a fresh punch belongs to — applied uniformly regardless of the employee's own shift span |
| `lateGraceMinutes` | 10 | Global grace window before LATE |
| `halfDayMaxHours`/`fullDayMinHours` | 3.5 / 7.5 | Global duration thresholds for HALF_DAY/FULL_DAY — closest existing analog to a duration-based rule, but global not per-shift |
| `dailyBreakBudgetMinutes` | 60 | Global break allowance — conceptually duplicates `Shift.breakMinutes` |
| `penalizationFallbackStrategy` | DEFAULT_POLICY | Unrelated org fallback policy |

No field named/shaped like max-duration, buffer, attendanceWindow, or cutover exists on `Shift` itself — confirmed absent by direct entity read and full-backend grep. Every "boundary" concept that exists today is global, in `AttendanceProperties`.

---

## 5. Unused / Dead / Duplicated Parameters

| Parameter | Verdict |
|---|---|
| `Shift.flexible` | **Dead.** Written & displayed, zero behavioral effect anywhere. |
| `Shift.breakMinutes` | **Dead + duplicated.** Never consumed by any calc; real break-time logic uses a global config value instead. |
| `Shift.workingDays` | **Dead + conceptually conflicting.** Never consumed for any weekly-off/working-day decision; `WeeklyOffPolicy.offDays` is the real, sole source of truth. An admin editing "Working Days" on a Shift today configures nothing. |
| `WeeklyOffPolicy.createdAt` | Dead (never read). |
| `ShiftSeedCorrector` historical rewrite | **Not dead — actively wrong.** See §8. |
| `RegularizationService.REGULARIZATION_DAY_BOUNDARY` (hardcoded 07:00) | Duplicated + drift-prone; should read `AttendanceProperties.shiftDayCutover` like everything else. |
| 5x `resolveShiftStart`, 3x `shiftEndCutoff`/rollover, 2-4x `shiftDayOf` copies | Duplicated, not dead — exercised, but via independently-maintained copies, which is exactly why they've already drifted (late-arrival formula, stale-checkout boundary). |

---

## 6. Logical-Day Problems

1. **`RegularizationService` uses its own hardcoded 07:00 shift-day boundary**, independent of the configurable `AttendanceProperties.shiftDayCutover` that `AttendanceService`/`WebClockInService` use. If the org-level cutover is ever tuned, regularization logic silently keeps behaving as if it's still 07:00.
2. **`StaleAttendanceSweeper` uses two different boundaries** for "is this attendance session over?" — `flagMissingCheckoutIfStale` keys off the global 07:00 cutover; `finalizeStatusPastShiftEnd` keys off the shift's own natural end. A real inconsistency: an employee on a shift ending 05:30 could be "finalized" by one method well before the other considers the day stale, or vice versa.
3. **Late-arrival minutes computed with two structurally different formulas** (measured from shift-start vs. from the post-grace deadline; ceiling vs. truncation) depending on whether the value came from a live check-in/web-clock-in vs. a regularization approval or the startup corrector. Same two clock-times → different `lateByMinutes` depending on which code path touched the record last.
4. **Correctness for early shifts (07:00 cutover):** the global cutover is correct for the org's overnight-default shift (15:30→00:30, since a post-midnight-pre-07:00 punch correctly still belongs to the prior day), but is a single fixed value applied to *every* shift regardless of span — an early-morning shift (e.g., 04:00 start) starting *before* 07:00 would have its own check-in misattributed to the *previous* day by `shiftDayOf`, since "before cutover ⇒ previous day" doesn't distinguish "late-night continuation of yesterday's overnight shift" from "today's legitimately-early start." This is a real correctness gap for early/non-standard shifts, not just overnight ones — confirmed by reading the cutover logic itself (`isBefore(cutover) ? minusDays(1) : today`, with no shift-awareness).
5. **Every consumer resolves shift-day/start/end by re-reading `Employee.getShift()` live**, with no historical snapshot (consistent with the explicit no-snapshot decision) — but this means the *only* safe way to avoid retroactively reinterpreting history is to stop re-deriving shift-dependent facts for *past* dates from the live shift in places that currently do so opportunistically (§8), not to add a snapshot.

---

## 7. Overnight Gaps

Overnight shifts are real and already seeded in production data (the **default** "Regular Shift" is 15:30→00:30, plus 20:30→05:30, 18:30→03:30, 17:30→02:30 from `V123`).

**Confirmed handled correctly (traced, not assumed):**
- `AttendanceService.shiftEndCutoff`/`WebClockInService.shiftEndCutoff` — correctly roll the end date to `workDate + 1` when `end ≤ start`.
- `AttendanceService.shiftDayOf`/`WebClockInService.shiftDayOf` — correctly attribute a post-midnight, pre-cutover punch back to the previous shift-day.
- `WorkHoursShortageCalculationService.shiftBoundedGrossMinutes` — correctly builds an overnight-aware window.

**Confirmed broken — `ExpectedWorkHoursService.shiftMinutes`:**
```java
long minutes = Duration.between(employee.getShift().getStartTime(), employee.getShift().getEndTime()).toMinutes();
return minutes > 0 ? minutes : null;
```
`Duration.between` on two bare `LocalTime` values does not know about "next day" — for `end < start` (every overnight shift the org has, including its own default), this returns a **negative** duration, converted by the `minutes > 0` guard to **`null`**. Confirmed with no compensating test — `ExpectedWorkHoursServiceTest` only exercises same-day shifts (9:00–18:00, 9:00–10:00).

**Cascading impact:**
- `TeamEffortEntry.expectedHours` falls back to a flat 8h constant meant for "no shift," silently also applied to real overnight-shift employees.
- `AttendanceStatsService.expectedHoursPerDay` silently skips the day, understating team averages.
- `WorkHoursShortageCalculationService` returns a `null` percent for overnight-shift employees — **Work Hours Shortage penalties can never fire for anyone on an overnight shift today**, a silent, undocumented exemption from null-propagation rather than a deliberate rule.

**Checkout after midnight, stale attendance, late calculation, regularization** — all traced and confirmed overnight-aware via the shift-end/shift-day logic in §3/§6, except for the two inconsistencies already noted there (stale-boundary split, late-arrival formula split) — those affect overnight and same-day shifts equally, not overnight-specifically.

**Overtime:** no dedicated overtime computation exists in attendance/shift codepaths at all — a separate `OvertimeRequestService` computes duration for an unrelated "Overtime Request" workflow.

This is a live bug today, not hypothetical, because the org's own default shift is overnight.

---

## 8. Historical Attendance Risks

**Confirmed: `ShiftSeedCorrector.recomputeLateArrivals` rewrites historical `Attendance` using the employee's CURRENT shift, on every application startup.** Direct code trace (`config/ShiftSeedCorrector.java:97-143`):

```java
Map<UUID, Shift> shiftByEmployeeId = new HashMap<>();
for (Employee employee : employeeRepository.findAll()) {
    shiftByEmployeeId.put(employee.getUserId(),
            employee.getShift() != null ? employee.getShift() : defaultShift);   // CURRENT shift, keyed only by employee id
}
List<Attendance> all = attendanceRepository.findAll();                            // ALL history, all time, no date scoping
for (Attendance record : all) {
    Shift employeeShift = shiftByEmployeeId.get(record.getEmployeeUserId());
    LocalTime shiftStart = employeeShift != null ? employeeShift.getStartTime() : attendanceProperties.getShiftStart();
    ... // recompute lateByMinutes / status against `shiftStart`, save if changed
}
```
Since `Attendance` has no column recording which shift applied at the time of the original event, this method has no way to know "what shift was this employee on back then" — it always substitutes today's assignment. **Reassign an employee to a different shift today, and on the next deploy/restart every one of their historical attendance rows silently gets a different `lateByMinutes`/`status`, with no audit trail.** This directly violates "historical attendance must not be retrospectively recalculated using an employee's new/current Shift."

**Other places that live-re-read `employee.getShift()` against a possibly-old date** (lower severity than the above, since they don't *persist* a rewrite, but still reinterpret history on every read):
- `WorkHoursShortageCalculationService.shiftBoundedGrossMinutes` — reads `employee.getShift()` live against `record.getWorkDate()`, whatever that date is; a shortage-percent recomputation for an old month will use *today's* shift.
- `ExceptionService.detectExceptions` — reads `employee.getShift()` live to label the `expectedTime` shown for a `LATE_ARRIVAL`/`WORK_HOURS_SHORTAGE` exception, even when detecting against a historical date.
- `RegularizationService` — if a regularization is approved for an old `attendanceDate` after the employee's shift has since changed, it recomputes `lateByMinutes`/`status`/worked-minutes against the employee's **current** shift, not whatever was active on that historical date.

**What's actually frozen (safe) today:** raw `lateByMinutes`/`workedMinutes` computed once at live check-in/checkout time are stored as plain numbers and are **not** automatically re-derived on every read — they only get rewritten if something explicitly recomputes and saves (i.e., `ShiftSeedCorrector` on startup, or a regularization approval touching that date). So the risk is concentrated in those two write paths, not in ordinary read/display.

**What should remain:** nothing about the *frozen-at-write-time* numbers needs to change — that's already the right behavior and matches "don't snapshot, but don't recompute either." **What should be removed or narrowed:** `ShiftSeedCorrector`'s blanket "recompute every historical row from the current shift" behavior — this is the one piece of code that actively violates the stated requirement and needs a product decision on exact remediation (§22/final classification).

---

## 9. Active/Inactive Semantics

Traced every reader of `Shift.active`:

| Consumer | Effect |
|---|---|
| `OrgService.toggleShiftActive` | Flips the flag (only writer) |
| `UserManagementService` (create employee) | **Blocks** assigning an inactive shift to a new employee |
| `UserManagementService` (update employee) | Blocks only if the assignment is *changing* to an inactive shift; leaves an already-assigned-then-deactivated shift alone on unrelated edits |
| `EmployeeAssignmentService.bulkUpdateShift` | Unconditionally blocks bulk-assigning an inactive shift |
| `EmployeeAssignmentService.importShiftsAndWeeklyOffs` (CSV import) | Same unconditional block |
| `EmployeeAssignmentService.getLookups` | Filters the **new-assignment picker** dropdown to active shifts only |
| `OrgService.listShifts()` | **Not filtered** — returns all shifts; this also feeds the Add/Edit-User shift dropdown per its own doc comment, so active-only filtering for that dropdown is applied client-side (`UserManagementPage.tsx`: `s.active !== false || s.id === currentShiftId` — deliberately still shows an employee's own current shift even if since deactivated) |
| Attendance/lateness/exception/expected-hours calculations | **Zero checks anywhere.** Deactivating a shift has no effect on employees already assigned — their check-ins, lateness, expected hours keep computing exactly as before. |

**Semantics as they exist today:** `active` = "can this shift be **newly picked** going forward." It is explicitly not a kill-switch for attendance processing and doesn't retroactively affect existing assignees. This is coherent and intentional-looking, not accidental, and needs no change for P1 — just explicit documentation as the agreed semantics (not assumed Keka parity, which the product brief correctly notes isn't publicly established anyway).

**Deletion dependency check already exists** — see §10.

---

## 10. Delete Safety

`OrgService.deleteShift`:
```java
long count = employeeRepo.countByShiftId(id);        // current, non-soft-deleted employees only
if (count > 0) throw new IllegalStateException(...);  // clear, actionable message
employeeRepo.clearShiftReferences(id);                 // defensive no-op if count==0
shiftRepo.delete(shift);
```
- **Only dependency check that exists:** `Employee → Shift`. No other FK references a Shift anywhere in the schema.
- **No `Attendance → Shift` FK exists** (confirmed — `Attendance` has zero shift-related columns). Deletion can never be blocked by historical attendance data, and there's no durable record of which shift applied to a historical row — which is exactly why the §8 bug is possible.
- **Recommendation (no new FK):** the current check (`employeeRepo.countByShiftId(id) > 0`) is already the correct, minimal, safe pattern for P1 and needs no structural change. The frontend already pre-checks `employeeCount > 0` before opening the delete confirmation and gracefully surfaces a backend rejection inline if a race occurs.

---

## 11. Weekly-Off Gaps

1. **No authoring UI or API exists.** `WeeklyOffPolicy` can only be created via a raw DB insert or a new migration — no `OrgService`/`OrgController` support at all, unlike Shift. This is the largest gap relative to explicit P1 scope ("Configure weekly offs").
2. **`Shift.workingDays` is a dead, conflicting duplicate.** It reads like it should represent "the days this shift operates," but nothing consumes it — the real decision is made exclusively from `Employee.weeklyOffPolicy.offDays`. **Do not treat `Shift.workingDays` as the final Weekly Off model** — confirmed it isn't even a functioning per-shift property today, let alone an employee/org-level policy; it's inert.
3. **No relationship between Shift and WeeklyOffPolicy.** Two independent `Employee`-owned FKs with no linkage — no "this shift defaults to weekly-off X" mechanism; every employee's weekly off is set independently of their shift.
4. **Weekly offs are employee-specific** (via `Employee.weeklyOffPolicy`), not shift-level or a single hardcoded org value — though in practice only one policy exists today (the seeded Sat/Sun default), so multi-policy support is unexercised.
5. **Attendance already recognizes weekly offs correctly** via `WorkingDayService`/`ExceptionService` reading `offDays` — this part works and needs no change.
6. **Full-day-only model already matches P1 scope** — `offDays` is a set of whole `DayOfWeek` values, so "support full-day weekly off" requires no schema change; only the CRUD surface is missing.

---

## 12. Reference UI vs Current UI

| UI element (reference image) | Exists? | Functional? | Backend-supported? | Persisted? | Unused param? | Missing? | P1/P2 |
|---|---|---|---|---|---|---|---|
| Combined "Shifts/Weekly Off" nav tab | No (only flat "Shifts") | — | Shift: yes; WeeklyOff: no | Shift: yes; WeeklyOff: yes but unused | — | Weekly Off tab | **P1** |
| Sub-tabs "Shift & weekly offs / Shift allowance / Assignments" | No | — | Allowance/Assignments: no backend | — | — | — | Allowance & Assignments = **P2, do not build** |
| Sub-tabs "Shifts / Weekly offs / Shift and weekly off rules" | Only "Shifts" | Partial | Weekly off CRUD: no; Rules: no | Weekly off table exists, empty of CRUD path; Rules: no table | — | Weekly Off CRUD, Rules UI | Weekly Off = **P1**; Rules = **P1-minimal** (one value only) |
| Shift list with search + Active badge | Yes | Yes | Yes | Yes | No | No | Existing, keep |
| "+ Add Shift" button | Yes | Yes | Yes | Yes | No | No | Existing, keep |
| Shift detail: Code, Name | Yes | Yes | Yes | Yes | No | No | Existing, keep |
| Detail tabs: Summary / Employees / Track Shift Versions | Only "Employees" | Partial | Versions: no | Versions: no history table | — | "Track Shift Versions" | **Versioning is P2 — do not build** |
| Banner/help text | No | — | — | — | — | Cosmetic only | Optional, skip |
| Fixed start/end time fields | Yes | Yes | Yes | Yes | No | No | **P1 — done** |
| Break duration field | Yes | **No — accepted, not consumed by any calc** | Yes | Yes | **Yes** | — | **P1 field, currently non-functional** — needs product decision |
| Working days field | Yes | **No — dead, conflicts with Weekly Off** | Yes | Yes | **Yes** | — | Conflicts with **P1** Weekly Off scope — needs product decision |
| "Flexible" checkbox | Yes | **No — dead** | Yes | Yes | **Yes** | — | Not in stated P1 required-field list — needs product decision |
| Overnight shift support | Mostly, one confirmed bug | Partially broken | Partial | N/A | N/A | — | **P1 — has a real bug to fix** (§7) |
| Org-level "Maximum Shift Day Duration" | No | No | No | No | Entirely missing | Yes | **P1 (explicit)** — see §16 |
| Shift Allowance | No | — | No | No | — | — | **P2 — skip entirely** |
| Advanced Assignments/rotation/auto-assignment | Basic bulk-assign exists; nothing advanced | Basic only | Basic: yes; advanced: no | — | — | — | **P2 for advanced parts; basic already sufficient for P1** |

---

## 13. P1/P2 Gap Matrix

| Capability | Status today | Gap type |
|---|---|---|
| Create/Edit Shift (name, code, description, start/end, break, working days) | Exists | Working Days field dead/conflicting — needs resolution, not net-new build |
| Activate/Deactivate Shift | Exists, semantics documented (§9) | None — already matches sensible P1 semantics |
| Delete Shift (safe) | Exists, correctly guarded | None |
| Overnight shift support | Mostly exists | Fix `ExpectedWorkHoursService` bug |
| Org-level Maximum Shift Day Duration | **Does not exist** | Net-new (config value + validation/warning UX) |
| Configure Weekly Off (CRUD) | **Does not exist** (assignment-only) | Net-new (service + controller + minimal UI) |
| Full-day weekly off | Data model already supports it | None beyond the CRUD surface above |
| Attendance interpreting weekly off | Exists and works | None |
| Centralized shift-day/attendance-day logic | **Does not exist** — 5x/3x/2-4x duplicated resolvers, with drift already present | Net-new consolidation (refactor, not new product surface) |
| No-recalculation-of-history-on-shift-change | **Currently violated** by `ShiftSeedCorrector` | Must be fixed |
| Shift Allowance | Doesn't exist | **P2 — explicitly out of scope** |
| Shift Assignments (advanced)/rotation/auto-assignment | Doesn't exist (basic bulk-assign is enough) | **P2 — explicitly out of scope** |
| Full "Shifts & Weekly Off Rules" module | Doesn't exist | **P2 for the full module** — P1 only needs the one max-duration value, delivered minimally (§16) |

---

## 14. Recommended Minimal Domain Model

No new entities are required for P1 as scoped:

- **Shift** stays flat. No `maxDuration`/`bufferMinutes`/`attendanceWindow`/`shiftDayCutover` fields are added (per explicit product direction). The three currently-dead fields (`flexible`, `breakMinutes`, `workingDays`) are a **product decision**, not a silent fix — see §12/§22.
- **WeeklyOffPolicy** stays structurally as-is (`id, name, offDays`) — already the right shape for full-day weekly off. Only the CRUD service/controller/UI is missing, not a schema change.
- **Attendance** stays as-is — **no** `shift_id` FK, **no** snapshot columns. The "don't reinterpret history" requirement is met by *behavior* (stop re-deriving shift-dependent facts for past dates from the live shift in the specific write path that currently does so — `ShiftSeedCorrector`), not by a new column.
- **Organization-level Maximum Shift Day Duration** — a single new numeric setting. `AttendanceProperties` already holds equivalent-shaped, config-driven, org-wide, non-per-shift values (`shiftDayCutover`, `lateGraceMinutes`, `halfDayMaxHours`, etc.). Adding `maximumShiftDayDurationHours` (default `18`) there is the minimal, safest home — zero new tables/migrations/endpoints for P1. Full design in §16.

---

## 15. Recommended ShiftDayPolicy Design

**Is a centralized abstraction required?** Yes — not because nothing exists, but because five independently-drifting copies of the same logic is exactly the duplication surfaced in §3/§6. The correct move is to **extract, not invent**: `AttendanceService`'s existing private `shiftDayOf`, `resolveShiftStart`, and `shiftEndCutoff` are already correct in their primary owner and should become the single source of truth every other consumer (`WebClockInService`, `RegularizationService`, `ExceptionService`, `WorkHoursShortageCalculationService`, and the corrected shift-seed logic) delegates to.

**Can an existing service become the source of truth?** The *content* of `AttendanceService`'s three private methods is right, but `AttendanceService` shouldn't be the *owner* — it's a consumer itself, already a very large class (1370+ lines), and other services shouldn't reach into it. The cleanest shape is a new, focused, stateless `@Component` (e.g. `ShiftDayPolicy`) that `AttendanceService` also calls through, rather than leaving the logic trapped as private methods or having every consumer hand-roll it.

**Minimum proposed API** (conceptual, not implementation):
- `LocalDate shiftDayOf(Employee employee, LocalDateTime timestamp)` — replaces the 2 duplicated `shiftDayOf` methods and the drifting `RegularizationService.resolveBusinessDate`, all reading the **same** configurable cutover.
- `LocalTime resolveShiftStart(Employee employee)` — replaces the 5 duplicated copies.
- `LocalDateTime shiftStartAt(Employee employee, LocalDate workDate)` — start of the employee's shift on `workDate`.
- `LocalDateTime shiftEndAt(Employee employee, LocalDate workDate)` — end of the shift on `workDate`, overnight-aware, replacing the 3 duplicated copies.
- `LocalDateTime maximumAttendanceBoundary(Employee employee, LocalDate workDate)` — `shiftStartAt(...) + organization.maximumShiftDayDuration` (§16); net-new capability, the natural extension point for it.
- `boolean isOvernight(Shift shift)` — small helper (`!endTime.isAfter(startTime)`), used internally and by save-time validation/UX.

**What gets removed once this exists:** the private duplicates in `WebClockInService`; `RegularizationService`'s `resolveShiftStart`/`resolveBusinessDate`/hardcoded boundary constant; `ExceptionService`'s inline shift-start read; `WorkHoursShortageCalculationService`'s inlined rollover window. This is behavior-preserving where the source was already correct (shift-end rollover, cutover-based shift-day), and **behavior-fixing** where it's currently wrong or drifted (regularization's hardcoded boundary, the two late-arrival formulas, the two stale-checkout boundaries) — those should be resolved deliberately during centralization, not left "for compatibility."

**No competing global cutover logic should remain** — `RegularizationService`'s hardcoded `REGULARIZATION_DAY_BOUNDARY` is the one clear example of a competing cutover to eliminate.

---

## 16. Organization-Level Maximum Shift Day Duration Design

**Where it should live:** `AttendanceProperties` is already, structurally, exactly the "org-level attendance/shift rules holder" the product direction describes as the eventual home — a `@ConfigurationProperties`-backed singleton already holding `shiftDayCutover`, `lateGraceMinutes`, `halfDayMaxHours`/`fullDayMinHours`, and `dailyBreakBudgetMinutes`, none of which are per-shift. Adding `maximumShiftDayDurationHours` (default `18`) there is the **minimum safe architecture**: zero new tables, zero new migrations, zero new endpoints, sitting next to conceptually identical siblings.

**Does the Rules module exist?** No — confirmed absent (§2, §11). The only comparable existing pattern for an org-level settings *document* (not a single config value) is `PenalizationPolicySection.tsx`/its backend policy — a versioned, section-based, single-document model. That pattern is the right template for the **eventual full** Rules module, but is over-scoped for a single number today.

**Trade-off to flag explicitly (needs a product decision):** `AttendanceProperties` values are application.yml-backed, not DB-backed, with **no runtime admin UI** — changing any of them (including today's `shiftDayCutover`/`lateGraceMinutes`) requires a config change and redeploy, not an in-app admin action. Two honest options:
- **(A) P1-minimal:** add it to `AttendanceProperties` now (redeploy-to-change, matching `shiftDayCutover`/`lateGraceMinutes` today), and defer an admin-editable UI until the real Rules module is built (P2+). The Shift form can still *read* the current value (via a small config-read endpoint) purely to power the save-time warning below.
- **(B) Slightly more:** promote just this one value into a tiny DB-backed org-settings row now, admin-editable without a redeploy, as a down-payment on the future Rules module.

**Recommendation: (A).** Matches the existing architecture exactly, avoids a one-off table for a single number, and doesn't complicate the future Rules module (that table can absorb `maximumShiftDayDurationHours` alongside `shiftDayCutover`/`lateGraceMinutes`/etc. all at once later — cleaner than migrating them one at a time).

**How Shift validation should consume it, and warn vs. block:** the codebase has **no existing precedent for a backend "warning" response envelope** — every existing validation (`OrgService.createShift`/`updateShift`, uniqueness, delete-safety) is a hard `throw` surfaced as an error. Since the org-level 18h default is explicitly described as *adjustable guidance*, not an inviolable constraint:
- **Backend:** keep create/update **non-blocking** for spans exceeding the current maximum (a night-shift-heavy org may legitimately need it) — but add a hard sanity ceiling (e.g., reject a shift spanning >24h outright) using the existing `throw`-based validation style, since that's a real invariant, not a policy choice.
- **Frontend:** compute the elapsed span client-side against the org's current `maximumShiftDayDurationHours` (fetched read-only) and show a **non-blocking confirmation warning** ("This shift spans 19h, which exceeds your organization's current Maximum Shift Day Duration of 18h. You can adjust this in Shifts & Weekly Off Rules.") with an explicit "Save anyway" affirmation.

This avoids inventing a new backend response contract for one use case, while still surfacing the rule clearly, and never creates a per-shift override.

**Overlap/conflict check with existing configuration** (explicitly requested):
- `shiftStart` (global fallback) — no conflict; different purpose (default when no shift assigned).
- `shiftDayCutover` — related but distinct concept (which *day* a punch belongs to, vs. how *long* a shift-day's attendance window is). Both should live in the same config surface; no overlap in what they compute, but they should be consumed through the same `ShiftDayPolicy` so they never diverge (§15).
- `lateGraceMinutes` — unrelated concern (grace before LATE), no conflict.
- `halfDayMaxHours`/`fullDayMinHours` — the closest conceptual cousin (duration thresholds), but these classify a *completed* day's worked hours, whereas Maximum Shift Day Duration bounds the *attendance window itself*. No overlap, but worth documenting the distinction so a future reader doesn't conflate them.
- `dailyBreakBudgetMinutes` — unrelated (break time), no conflict.
- **Explicitly not doing:** `shiftEnd + fixed buffer` anywhere, persisting a computed "continuation window" on `Shift`, or treating 18h as productive hours — it stays a pure elapsed-time boundary consumed only by `ShiftDayPolicy.maximumAttendanceBoundary`.

---

## 17. File-by-File Change Map

| File/Component | Current behavior | Problem | Proposed change | Why needed | Must remain unchanged | P1/P2 | Risk |
|---|---|---|---|---|---|---|---|
| `config/AttendanceProperties.java` | Global attendance config, no duration cap | No org-level max shift-day duration | Add `maximumShiftDayDurationHours` (double, default 18) | Explicit org-level rule requirement | Existing property defaults/behavior | P1 | Low — additive |
| `config/ShiftSeedCorrector.java` | `recomputeLateArrivals` rewrites ALL historical `Attendance.lateByMinutes`/`status` from current shift, every startup | Violates "no recalculation of history from current shift" | Remove, or scope strictly to rows with `lateByMinutes == null` (never-computed) — never "recompute if different" for already-computed rows | Stated correctness requirement | Any narrow, legitimate backfill-missing-value case, if one still exists | **NEEDS PRODUCT DECISION** on exact scoping and legacy-data handling | Medium — confirm nothing else depends on today's "keep history in sync" behavior |
| `service/RegularizationService.java` | Own hardcoded 07:00 boundary; own `resolveShiftStart`; own worked-minutes formula; own late-arrival formula | Drifts from `AttendanceProperties.shiftDayCutover`; duplicates logic; inconsistent late-arrival math vs. live check-in | Delegate day-boundary/shift-start/shift-end to `ShiftDayPolicy`; align late-arrival/worked-minutes formulas with the single corrected version | Removes duplication and formula drift | Approval workflow itself, audit fields | P1 (bug fix) / centralization | Medium — touches approval math, needs regression tests |
| `service/WebClockInService.java` | Duplicates `shiftDayOf`, `resolveShiftStart`, `shiftEndCutoff` | Duplication, drift risk | Delegate to `ShiftDayPolicy` | Single source of truth | Web clock-in geofencing/session logic | P1-adjacent | Low-medium |
| `service/AttendanceService.java` | Owns the best versions of the three methods, privately | Not shared; large class | Extract into `ShiftDayPolicy`, call through it | Centralization without behavior change here | Existing check-in/out/worked-minutes public behavior | P1-adjacent | Low — behavior-preserving extraction |
| `service/ExceptionService.java` | Inline shift-start read for exception labels, using current shift even for historical detection | Current-shift-for-past-date pattern | Delegate through `ShiftDayPolicy`; decide whether historical exception labels may reflect current shift (display-only) | Consistency + correctness | Exception detection thresholds/rules themselves | **NEEDS PRODUCT DECISION** | Low-medium |
| `service/WorkHoursShortageCalculationService.java` | Inlines rollover; reads current shift against possibly-old `workDate`; consumes the buggy `ExpectedWorkHoursService` | Current-shift-for-past-date pattern; source of "overnight ⇒ no shortage penalty" bug | Delegate rollover to `ShiftDayPolicy`; fix once `ExpectedWorkHoursService` is fixed | Correctness | Penalty tiering logic itself | P1 (bug fix) | Medium — changes who gets penalized for overnight shifts; needs rollout communication |
| `service/ExpectedWorkHoursService.java` | `shiftMinutes` uses bare `Duration.between`, no overnight handling, returns null | Confirmed bug | Overnight-aware elapsed-time calc (via `ShiftDayPolicy` start/end on an anchor date) | Confirmed active bug on the org's own default shift | Its legitimate "no shift assigned" null fallback | **P1 (bug fix)** | Medium — will correctly raise expected-hours numbers for overnight employees; needs stakeholder heads-up |
| `service/AttendanceStatsService.java` | Silently skips days with null `shiftMinutes` | Masks the overnight bug | No change needed once `ExpectedWorkHoursService` is fixed | — | — | Falls out of the above fix | Low |
| `entity/Shift.java` | `flexible`, `breakMinutes`, `workingDays` have no calc consumers | Dead/conflicting fields | **Product decision**: leave/defer, wire `breakMinutes` into a real break calc, remove `workingDays` (conflicts with real Weekly Off), remove/repurpose `flexible` | Avoid shipping a "revisited" Shift UI that still presents dead fields | Required P1 fields (name/code/description/start/end) | **NEEDS PRODUCT DECISION** | Low technically, UX/trust risk if left dead |
| *(new)* `service/ShiftDayPolicy.java` | Doesn't exist | No single source of truth | Create per §15 | Removes 5x/3x/2-4x duplication and its drift bugs | No new persistence, no Attendance FK | P1-adjacent infrastructure | Low if extraction is behavior-preserving where source was already correct |
| *(new, backend)* `WeeklyOffPolicyService`/`WeeklyOffPolicyController` | Doesn't exist | No CRUD | Add create/list/update/(delete-if-safe), mirroring `OrgService`'s Shift pattern | Explicit P1 requirement | Existing `WeeklyOffPolicy` shape, existing assignment endpoints | **P1** | Low — pure addition |
| *(frontend)* `OrgSetupPage.tsx` | Flat "Shifts" tab only; dead `flexible`/`workingDays` fields rendered in `ShiftFormModal` | No Weekly Off tab; misleading dead fields | Add a Weekly Off tab/CRUD UI (simple list+modal, reusing existing `ConfirmModal`/`ShiftFormModal` patterns — not the full versioned Penalization-Policy pattern, since Weekly Off is plain CRUD); resolve dead-field decision above | Explicit P1 requirement + UX correctness | Existing Shift list/table structure, `canManageShifts` access control | **P1** | Low-medium — additive |
| *(frontend, new)* minimal Rules surface | Doesn't exist | No way to view `maximumShiftDayDurationHours` | If (A) from §16: **read-only** display near the Shift form's duration warning is enough for P1; no editable screen needed yet. If (B): a minimal single-field editable form | Support the save-time warning UX | Don't build the full multi-section Rules module (P2) | **P1 (minimal)**, full module P2 | Low |

---

## 18. DB/Flyway Impact

- **Option (A) (recommended, §16): zero new migrations.** `maximumShiftDayDurationHours` lives in `application.yml`/`AttendanceProperties`.
- **Option (B):** one new migration for a small single-row org-settings table (no existing generic org-settings table was found to reuse).
- **Weekly Off CRUD:** no schema change — table/columns already support full-day weekly off; only new service/controller code.
- **No `Attendance` schema changes** of any kind (no `shift_id`, no snapshot columns) — consistent with explicit product direction.
- **`Shift` schema:** no new columns for P1 (max-duration/buffer/cutover explicitly excluded from Shift). If the `flexible`/`breakMinutes`/`workingDays` decision results in removing a column, that's the only potential Shift-table migration, and it's optional/deferred, not required for P1.
- **`ShiftSeedCorrector` fix:** no migration needed — Java startup-logic change only, assuming the fix is "stop recomputing," not "add a snapshot column."

---

## 19. API Impact

- **New endpoints (P1):** Weekly Off CRUD (`GET/POST/PUT(/DELETE)` under something like `/api/org/weekly-off-policies`), mirroring `/api/org/shifts*`'s shape and `@PreAuthorize` pattern.
- **New read-only surface (P1, small):** a way to read the current `maximumShiftDayDurationHours` — could piggyback on an existing config-read DTO (e.g. `AttendanceConfigResponse`) rather than a brand-new endpoint; either is minimal.
- **No changes required** to `CreateShiftRequest`/`UpdateShiftRequest`/`ShiftResponse` for P1 core fields — except whatever the `flexible`/`breakMinutes`/`workingDays` decision resolves to (could mean removing `workingDays` from the payload once Weekly Off supersedes it).
- **No structural change** to `Attendance`-related response DTOs, though *values* for expected-hours/work-hours-shortage-percent will correctly change for overnight-shift employees once the bug is fixed — worth flagging to stakeholders even though the contract shape is unchanged.
- **No new "warning" response envelope** — keep the existing hard-validation-only contract; the duration warning is frontend-computed, non-blocking UX.

---

## 20. Frontend Impact

- **New:** Weekly Off tab/CRUD UI in `OrgSetupPage.tsx` (list + add/edit modal, following existing `ShiftFormModal`/`ConfirmModal` patterns — no new UI framework needed).
- **New (minimal):** a duration-warning check in `ShiftFormModal` comparing entered start/end span against the org's `maximumShiftDayDurationHours` (fetched read-only), shown as a non-blocking confirmation before save.
- **Changed (pending product decision, §17):** the `flexible` checkbox and `workingDays` chip picker in `ShiftFormModal` — removed, relabeled as informational, or (if wired up on the backend) left as-is with newly-real behavior. Do **not** leave them rendered-but-dead through this revisit without an explicit decision.
- **Not required:** the full Keka-style nested tab chrome, "Track Shift Versions," Shift Allowance, or advanced Assignments UI — all P2. The existing flat tab structure in `OrgSetupPage.tsx` can simply gain one more entry for Weekly Off rather than being restructured.
- **Active/inactive, delete, list-search UI:** already correct for P1 — no changes needed.

---

## 21. Test Impact

- `ExpectedWorkHoursServiceTest` — must gain overnight-shift cases (none exist today); direct regression-proofing for the confirmed bug fix.
- `AttendanceServiceTest`, `AttendanceServicePunctualityTest`, `AttendanceServiceTeamStatsTest`, `AttendanceStatsServiceTest` — need coverage confirming unchanged behavior for same-day shifts and corrected behavior for overnight shifts once `ExpectedWorkHoursService`/`ShiftDayPolicy` land.
- `RegularizationServiceTest` — needs updated cases once the hardcoded 07:00 boundary and duplicated shift-start/late-arrival logic are replaced, confirming convergence with the (previously-diverging) `AttendanceService` formula rather than an untested silent change.
- `ConfiguredAttendancePolicyEngineTest`/`AttendancePenaltyEvaluationServiceTest`/`AttendancePenaltyServiceTest` — need a case confirming Work Hours Shortage penalties now correctly evaluate for overnight-shift employees.
- New tests needed: `ShiftDayPolicy` unit tests (shift-day, shift-start, shift-end, overnight, maximum-boundary), Weekly Off CRUD service/controller tests, and a regression test proving historical `Attendance.lateByMinutes` is **not** altered when an employee's shift assignment changes (direct test of the §8 fix).
- `WorkingDayServiceTest`/`ExceptionServiceDetectionTest`/`ExceptionServiceTest` — should keep passing unchanged since no behavior change is proposed to `WeeklyOffPolicy.offDays` consumption.

---

## 22. Regression Risks

1. **Fixing `ExpectedWorkHoursService`'s overnight bug changes real numbers** — expected hours (currently null/flat-8h-fallback) become correct, non-null values for every overnight-shift employee, and Work Hours Shortage penalties become newly *possible* where they silently never could fire before. Correct, but visible and employee-facing — needs deliberate rollout, not a silent bugfix.
2. **Removing/narrowing `ShiftSeedCorrector.recomputeLateArrivals`** touches a process that currently (incorrectly) keeps historical `lateByMinutes` "in sync" with reassignments. Removing it with no replacement means any employee reassigned *before* this fix ships keeps whatever value was last computed pre-fix — it won't self-correct. Needs an explicit decision: accept existing production drift as-is vs. a one-time, carefully-scoped, dated backfill. Silently deleting the corrector with no data conversation is itself a risk.
3. **Centralizing shift-start/late-arrival/worked-minutes logic surfaces existing formula disagreements** between live check-in, web clock-in, and regularization. Whichever formula is chosen canonical will change numbers for whichever paths currently disagree — needs a conscious "which is correct" decision (likely the live check-in formula, the primary path), not an assumption that centralization is purely mechanical.
4. **`RegularizationService`'s hardcoded 07:00 boundary** — if `shiftDayCutover` has never actually been changed from 07:00 in production, this drift is currently latent; centralizing removes the *future* risk, but implies no retroactive data-correctness cleanup unless the cutover was, in fact, ever changed.
5. **Dead `Shift.flexible`/`breakMinutes`/`workingDays` fields** — a product decision, not a pure engineering call; resolving incorrectly (e.g., removing `workingDays` if an undiscovered consumer depends on it) warrants a final sanity check immediately before implementation, even though the investigation traced this exhaustively via grep.
6. **Weekly Off CRUD is genuinely net-new, additive surface** — lower risk since it's new code touching nothing existing, but still needs its own tests and access-control wiring mirroring Shift's `SUPER_ADMIN`-gated mutation pattern.
7. **Org-level Maximum Shift Day Duration as a redeploy-only config value (§16 option A)** means an admin can't self-serve change it without engineering involvement until the full Rules module exists — acceptable per "minimum safe architecture," but a known, deliberate limitation worth flagging, not a surprise later.

---

## 23. Recommended Implementation Order

1. **Fix `ExpectedWorkHoursService` overnight bug** — isolated, well-tested, highest-value correctness fix (needs the stakeholder heads-up from §22.1 at rollout).
2. **Decide and fix the `ShiftSeedCorrector` historical-rewrite violation** (§8, §22.2) — needs a product decision on legacy-data handling before code changes.
3. **Extract `ShiftDayPolicy`** from `AttendanceService`'s existing correct private methods; migrate `WebClockInService` onto it first (lowest-risk consumer, already matches).
4. **Migrate `RegularizationService` and `ExceptionService` onto `ShiftDayPolicy`**, resolving the late-arrival-formula and hardcoded-boundary drift deliberately — higher risk, needs the §21 regression tests first.
5. **Migrate `WorkHoursShortageCalculationService` onto `ShiftDayPolicy`** and confirm penalty evaluation now correctly includes overnight-shift employees (depends on step 1).
6. **Add `maximumShiftDayDurationHours` to `AttendanceProperties`** (§16) and extend `ShiftDayPolicy` with `maximumAttendanceBoundary` — small, additive, no consumers yet.
7. **Resolve the `flexible`/`breakMinutes`/`workingDays` product decision** (§17/§22.5) — needed before touching the Shift form UI.
8. **Build Weekly Off CRUD** (backend service/controller, then frontend tab/modal) — purely additive, can run in parallel with steps 3-6 since it doesn't touch attendance calculation code.
9. **Add the Shift-form duration-warning UX** (§16) — depends on step 6 and benefits from step 7 being resolved first.
10. **(Deferred, explicitly P2):** Shift Allowance, advanced Assignments/rotation, Shift Versioning, full multi-section "Shifts & Weekly Off Rules" module.

---

## FINAL CLASSIFICATION

### SAFE TO IMPLEMENT
- `ExpectedWorkHoursService` overnight-shift fix.
- Extracting `ShiftDayPolicy` from `AttendanceService`'s already-correct private methods and pointing `WebClockInService` at it (behavior-preserving).
- Adding `maximumShiftDayDurationHours` to `AttendanceProperties` (additive, no consumers required yet).
- Weekly Off CRUD service/controller/UI (purely additive, mirrors existing Shift CRUD patterns).
- Frontend duration-warning UX on the Shift form (non-blocking, no new backend contract).
- Existing Shift active/inactive and delete-safety semantics — no change needed.

### NEEDS PRODUCT DECISION
- Exact remediation for `ShiftSeedCorrector` (remove entirely vs. narrow to a one-time, date-scoped legacy backfill) and what happens to already-corrupted historical `lateByMinutes` values from past incorrect runs.
- Canonical late-arrival formula (shift-start-anchored/ceiling vs. deadline-anchored/truncated) to standardize on when centralizing.
- Fate of `Shift.flexible`, `Shift.breakMinutes`, `Shift.workingDays` — remove, wire up, or explicitly document as reserved/inert.
- Whether `ExceptionService`'s exception labels for historical dates may reflect the employee's current shift (display-only concession) now that there's no snapshot.
- §16 option A vs. B for where `maximumShiftDayDurationHours` should live (config-only vs. small DB-backed row) — this report recommends A.
- Whether the 07:00-cutover misattribution risk for early (non-overnight) shift starts (§6.4) needs its own fix now or can be deferred, since no early-start shift currently exists in seeded data.

### DO NOT TOUCH
- `Attendance` entity/schema — no FK to Shift, no snapshot columns (explicit product direction; investigation found no functional need for one given the behavioral fix path in §8).
- Shift Allowance, advanced Shift Assignments/rotation/auto-assignment, Shift Versioning — all explicitly P2.
- The full Keka-style nested tab chrome from the reference image — existing flat tab structure just needs one more entry.
- Existing delete-dependency check (`employeeRepo.countByShiftId`) — already minimal and correct.
- Existing active/inactive semantics — already coherent as a forward-looking assignment gate.

### LEGACY / UNUSED CODE TO CLEAN UP
- `Shift.flexible` — currently dead (pending decision above).
- `Shift.breakMinutes` — currently dead/duplicated by `AttendanceProperties.dailyBreakBudgetMinutes` (pending decision).
- `Shift.workingDays` — currently dead and conceptually conflicting with `WeeklyOffPolicy.offDays` (pending decision, likely candidate for removal once real Weekly Off CRUD ships).
- `WeeklyOffPolicy.createdAt` — never read anywhere; low-priority cleanup.
- The five duplicated `resolveShiftStart` copies, three duplicated shift-end/rollover copies, and two-to-four duplicated shift-day copies — superseded by `ShiftDayPolicy` once extraction is complete.
- `RegularizationService.REGULARIZATION_DAY_BOUNDARY` hardcoded constant — replaced by the shared, configurable cutover once centralized.

---

# ADDENDUM (correction): 18h rule is a logical-workday-reset boundary ONLY

This addendum corrects and supersedes the "maximumAttendanceBoundary" framing in the sections above wherever they implied the 18h boundary triggers staleness/checkout/exception creation directly. It does not.

## Verified current lifecycle (direct code read, `AttendanceService.java`)

- `checkIn()` blocks a second check-in whenever `findOpenNormalAttendance(employeeId)` finds an open record, **with no date filter** — an open session from a prior calendar day still blocks/represents "today" until resolved. Comment: *"an open session started yesterday is still the actionable 'today' state even once the calendar date has rolled over, so it takes priority over a plain work_date lookup."*
- **No new record is created while a session is open.** The previous record is **left open** — `flagMissingCheckoutIfStale` explicitly never fabricates a `checkOutAt` or `workedMinutes`: *"the real check-out time is unknown, so none is guessed."*
- The **only** thing that ever touches an abandoned open record is `flagMissingCheckoutIfStale` (called opportunistically from `getToday`/`checkIn`/`checkOut`, and swept hourly by `StaleAttendanceSweeper`) — it does exactly one thing: sets `status = MISSING_CHECKOUT`. Pre-existing, independent mechanism, not introduced by this design.
- A brand-new `Attendance` row is only created once the prior one is **resolved** (checked out) or **already flagged** `MISSING_CHECKOUT` — at that point `checkIn()`'s guard stops blocking and a fresh row opens under whatever `shiftDayOf(now)` resolves to. This is the existing lifecycle; nothing new is being invented.
- `finalizeStatusPastShiftEnd` only ever acts on records with **no open punch** (explicitly skips open ones) and uses `shiftEndCutoff` (the shift's *scheduled* end) — never the 18h boundary. Fully untouched by this correction.

**Direct answer to "does the system create a new record, leave the previous open, or something else":** it leaves the previous record open, unconditionally, for as long as the session stays open. The logical-workday reset does not check anyone out, flag anyone, or create anything by itself.

## The one real subtlety, resolved (not left open)

`flagMissingCheckoutIfStale`'s existing condition is `shiftDayOf(now).isAfter(record.getWorkDate())`. Today `shiftDayOf` is the single fixed 07:00 cutover, so this one function already answers two different questions by coincidence of the old design. Once `shiftDayOf` becomes shift-relative, this **existing, unchanged** method's trigger point moves with it — the hourly sweep will flag a still-open session as `MISSING_CHECKOUT` once the corrected boundary passes.

**Decision:** this is correct and intentional, not new coupling. `flagMissingCheckoutIfStale`'s own doc comment already states its designed intent as *"left untouched... until the grace window has genuinely ended"* — its job was always "has this session's own logical workday truly ended," it just used the wrong (globally-fixed) definition. Feeding it the corrected, shift-relative boundary is `AttendanceService` continuing to own the exact same decision, with a more accurate input — not `ShiftDayPolicy` taking on staleness responsibility. `ShiftDayPolicy` itself never writes to `Attendance`, never sets a status, never closes a session.

**Concrete, flagged consequence:** stale-flagging timing shifts per shift span. Both of the worked examples below (09:00–18:00 and 15:30–00:30) have a 9h scheduled span, so both now get the *same* 9h grace-after-shift-end before the sweep flags them (18h − 9h = 9h uniformly) — versus today's inconsistent ~13h (09:00 shift) and ~6.5h (overnight shift) purely from where each happens to fall against the fixed clock. This is a real, testable timing change to `StaleAttendanceSweeper`'s trigger point — call it out at rollout, don't bury it.

## Corrected 5-concept separation

| # | Concept | Owner | Governed by | Changed by this correction? |
|---|---|---|---|---|
| 1 | Scheduled shift end | `Shift.endTime` | The shift's own configured end time | No — feeds worked-minutes cap and `finalizeStatusPastShiftEnd`'s HALF_DAY judgment, both untouched |
| 2 | **Logical workday reset** | **`ShiftDayPolicy`** | `shiftStartAt + maximumShiftDayDurationHours` — **and only this** | Yes — this is the corrected concept |
| 3 | Overtime | N/A — no dedicated computation exists anywhere in the codebase | N/A | Not affected |
| 4 | Stale/open attendance handling | `AttendanceService.flagMissingCheckoutIfStale`/`StaleAttendanceSweeper` — unchanged ownership | "Has this record's own logical workday ended" — now sourced from `ShiftDayPolicy.shiftDayOf` instead of the fixed cutover | Input becomes accurate; responsibility does not move |
| 5 | Auto-checkout | Does not exist anywhere in the codebase | N/A | Not being introduced |

`ShiftDayPolicy`'s final surface is narrowed to pure, side-effect-free queries: `shiftDayOf`, `shiftStartAt`, `shiftEndAt` (scheduled, concept #1), `maximumAttendanceBoundary` (concept #2), `isOvernight`. No method writes to `Attendance`, flags anything, or closes a session — all of that stays exactly where it already lives.

## Corrected worked example (15:30→00:30, max=18h)

- 00:20 / 00:30 / 01:00 / 05:00 / 07:00 — all shift-day D. If genuinely still checked in, **nothing happens to the record at any of these instants** — same open row throughout, `canCheckOut=true` the whole time.
- 09:30 (the reset) — if still open, **still nothing forced.** `ShiftDayPolicy` doesn't touch the row. The only consequence: `shiftDayOf(now)` now returns D+1, which matters solely for (a) the existing hourly stale-sweep's next pass correctly recognizing this record's workday has ended, and (b) any *fresh* check-in from this point filing under D+1.
- 10:00 — if the D-session is still open and not yet swept, a fresh check-in attempt is still blocked exactly as today. Once flagged (by the sweep, or inline by that very click), the *next* check-in creates a new D+1 record — the existing create-once-resolved lifecycle, not a new one.

## Test/deployment impact, updated

- New test: assert the reset boundary alone never changes an open record (no status change, no checkout, no new record).
- Updated test: stale-flagging timing per shift span (later for the overnight default shift, earlier for a 09:00–18:00 shift) — an intentional, called-out timing change, not a new feature.
- Deployment: unchanged conclusion (code-only, no schema change, prospective-only) — the stale-sweep timing change takes effect the moment the new code deploys; acceptable since it only ever flags a status, never fabricates a checkout or rewrites history.
