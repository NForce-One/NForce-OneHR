package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.ShiftWeeklyOffRules;
import com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * The logical-workday-reset algorithm, verified against every worked example from the design
 * discussion — see {@link ShiftDayPolicy}'s own Javadoc. Crossing the boundary is asserted to be
 * a pure query (no side effects) throughout; staleness/closure ownership is exercised separately
 * in AttendanceService's/WebClockInService's own tests.
 *
 * <p>Shift Versioning is faked here with a simple in-memory list per Shift ({@link
 * #withVersions}) rather than mocking {@link ShiftVersionResolver} per call — the resolver's own
 * "latest version with effectiveFrom <= day" semantics are exercised for real via a small stub
 * implementation, so a test that builds a shift with two versions actually proves the boundary
 * date resolves to the correct one, rather than merely asserting whatever a mock was told to say.
 */
@ExtendWith(MockitoExtension.class)
class ShiftDayPolicyTest {

    @Mock private ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;

    private ShiftDayPolicy policy;
    private final LocalDate day = LocalDate.of(2026, 8, 10);
    private final List<ShiftVersion> allVersions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                ShiftWeeklyOffRules.builder().maximumShiftDayDurationHours(BigDecimal.valueOf(18)).build()));
        // A minimal, real (not mocked) resolver — the "latest effectiveFrom <= day" query implemented
        // in-memory against whatever versions withShift/withVersions registered, exactly mirroring
        // ShiftVersionRepository's own query semantics. resolveIfPresent is the actual override
        // point (matching the real class, resolve() now delegates to it) so ShiftDayPolicy's own
        // "did the Shift already exist as of yesterday" pre-check is exercised for real too, not
        // against the real class's null-repository field.
        ShiftVersionResolver resolver = new ShiftVersionResolver(null) {
            @Override
            public Optional<ShiftVersion> resolveIfPresent(Shift shift, LocalDate workDate) {
                return allVersions.stream()
                        .filter(v -> v.getShift().getId().equals(shift.getId()))
                        .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                        .max(java.util.Comparator.comparing(ShiftVersion::getEffectiveFrom));
            }

            @Override
            public ShiftVersion resolve(Shift shift, LocalDate workDate) {
                return resolveIfPresent(shift, workDate)
                        .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
            }
        };
        policy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), resolver, employeeShiftAssignmentResolver);
    }

    // A minimal, real (not mocked) assignment resolver — mirrors the ShiftVersionResolver fake
    // above one layer up: an in-memory "latest effectiveFrom <= day" lookup against whatever
    // withAssignment registered, exercising ShiftDayPolicy's new day-aware UUID-taking overloads
    // for real rather than asserting whatever a mock was told to say.
    private final List<EmployeeShiftAssignment> allAssignments = new ArrayList<>();
    private final EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver = new EmployeeShiftAssignmentResolver(null) {
        @Override
        public Optional<EmployeeShiftAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
            return allAssignments.stream()
                    .filter(a -> a.getEmployeeUserId().equals(employeeUserId))
                    .filter(a -> !a.getEffectiveFrom().isAfter(workDate))
                    .max(java.util.Comparator.comparing(EmployeeShiftAssignment::getEffectiveFrom));
        }

        @Override
        public EmployeeShiftAssignment resolve(UUID employeeUserId, LocalDate workDate) {
            return resolveIfPresent(employeeUserId, workDate)
                    .orElseThrow(() -> new IllegalStateException("no assignment effective on or before " + workDate));
        }
    };

    /** Registers a Shift Assignment for a brand-new random employee, effective from {@code effectiveFrom}. Returns the employee's id (the day-aware overloads take a UUID, not an Employee). */
    private UUID withAssignment(Shift shift, LocalDate effectiveFrom) {
        UUID employeeUserId = UUID.randomUUID();
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shift).effectiveFrom(effectiveFrom).build());
        return employeeUserId;
    }

    /** A single-version shift, effective from the dawn of time (LocalDate.MIN) — the common case for tests that never touch versioning directly. */
    private Employee withShift(LocalTime start, LocalTime end) {
        Shift shift = Shift.builder().id(UUID.randomUUID()).name("Test Shift").build();
        allVersions.add(ShiftVersion.builder().shift(shift).startTime(start).endTime(end).effectiveFrom(LocalDate.MIN).build());
        return Employee.builder().userId(UUID.randomUUID()).fullName("Test Employee").shift(shift).build();
    }

    /** A shift with two versions: the OLD one effective from LocalDate.MIN, the NEW one effective from {@code newEffectiveFrom}. */
    private Employee withVersions(LocalTime oldStart, LocalTime oldEnd, LocalTime newStart, LocalTime newEnd, LocalDate newEffectiveFrom) {
        Shift shift = Shift.builder().id(UUID.randomUUID()).name("Test Shift").build();
        allVersions.add(ShiftVersion.builder().shift(shift).startTime(oldStart).endTime(oldEnd).effectiveFrom(LocalDate.MIN).build());
        allVersions.add(ShiftVersion.builder().shift(shift).startTime(newStart).endTime(newEnd).effectiveFrom(newEffectiveFrom).build());
        return Employee.builder().userId(UUID.randomUUID()).fullName("Test Employee").shift(shift).build();
    }

    /**
     * A shift with exactly ONE version, effective from {@code effectiveFrom} itself — no version
     * at all before it. Mirrors {@code OrgService#createShift}'s real behavior (its first version
     * is effective {@code LocalDate.now()}, never any earlier date) — used to reproduce the
     * ONEHR-336 follow-up bug: a Shift created (and assigned) TODAY has no version covering
     * yesterday, unlike every {@code withShift}/{@code withVersions} fixture above (which starts
     * at {@code LocalDate.MIN}, always covering "yesterday" implicitly).
     */
    private Employee withBrandNewShift(LocalTime start, LocalTime end, LocalDate effectiveFrom) {
        Shift shift = Shift.builder().id(UUID.randomUUID()).name("test").build();
        allVersions.add(ShiftVersion.builder().shift(shift).startTime(start).endTime(end).effectiveFrom(effectiveFrom).build());
        return Employee.builder().userId(UUID.randomUUID()).fullName("Test Employee").shift(shift).build();
    }

    @Test
    void maximumAttendanceBoundary_09to18_is_03_00_theNextDay() {
        Employee employee = withShift(LocalTime.of(9, 0), LocalTime.of(18, 0));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(3, 0)),
                policy.maximumAttendanceBoundary(employee, day));
    }

    @Test
    void maximumAttendanceBoundary_15_30to00_30_is_09_30_theNextDay() {
        Employee employee = withShift(LocalTime.of(15, 30), LocalTime.of(0, 30));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(9, 30)),
                policy.maximumAttendanceBoundary(employee, day));
    }

    /**
     * The full worked example from the design discussion: 15:30-00:30, punches at 00:20, 00:30,
     * 01:00, 05:00, 07:00 all still belong to the PREVIOUS logical workday; 09:30 and 10:00
     * already belong to the new one.
     */
    @Test
    void shiftDayOf_overnightShift_matchesEveryWorkedExample() {
        Employee employee = withShift(LocalTime.of(15, 30), LocalTime.of(0, 30));
        LocalDate nextDay = day.plusDays(1);

        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(0, 20))));
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(0, 30))));
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(1, 0))));
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(5, 0))));
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(7, 0))),
                "still shift-day D even past the OLD fixed 07:00 cutover — this is the whole point of the correction");
        assertEquals(nextDay, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(9, 30))),
                "the reset boundary itself already belongs to the new day");
        assertEquals(nextDay, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(10, 0))));
    }

    /**
     * The 09:00-18:00 example: a 01:00 punch (technically "tomorrow") is still an overtime
     * continuation of TODAY's logical workday; a 05:00 punch (past the 03:00 boundary, before
     * tomorrow's own 09:00 start) already belongs to tomorrow's — an ordinary early-arrival
     * concern, not a shift-day one.
     */
    @Test
    void shiftDayOf_09to18Shift_earlyMorningContinuationVsNewDay() {
        Employee employee = withShift(LocalTime.of(9, 0), LocalTime.of(18, 0));
        LocalDate nextDay = day.plusDays(1);

        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(1, 0))),
                "overtime continuation of today's logical workday");
        assertEquals(nextDay, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(5, 0))),
                "past the 03:00 boundary — belongs to the new logical workday even though tomorrow's own shift hasn't started yet");
        assertEquals(nextDay, policy.shiftDayOf(employee, LocalDateTime.of(nextDay, LocalTime.of(9, 0))),
                "an ordinary on-time start for the new day");
    }

    /**
     * Shift Version boundary — the exact scenario this rule was added for: old version
     * 15:30-00:30, new version effective TODAY (candidate), 06:00-15:00. A fresh check-in today
     * at 06:00 (no open session — shiftDayOf never sees that case, see AttendanceService#checkIn)
     * must resolve to TODAY, not yesterday, even though yesterday's own (old-version) 18h boundary
     * would otherwise extend to 09:30 today.
     */
    @Test
    void shiftDayOf_versionBoundary_overnightToEarlyMorning_freshCheckInBelongsToTheNewDay() {
        Employee employee = withVersions(
                LocalTime.of(15, 30), LocalTime.of(0, 30),   // old: overnight
                LocalTime.of(6, 0), LocalTime.of(15, 0),     // new: early morning
                day);
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(day, LocalTime.of(6, 0))),
                "an on-time arrival for the NEW version's own start must never be reinterpreted as yesterday's overnight tail");
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(day, LocalTime.of(9, 0))),
                "a slightly-late arrival for the new version is still today, not a continuation of yesterday");
    }

    /**
     * The reverse transition: old version 06:00-15:00 (same-day, short), new version effective
     * today, 15:30-00:30 (overnight). A fresh check-in today at 15:30 resolves to today either
     * way, since the OLD version's own (same-day) boundary never reached that far — included as
     * the asymmetric counterpart to the case above (this direction was never actually broken).
     */
    @Test
    void shiftDayOf_versionBoundary_earlyMorningToOvernight_freshCheckInBelongsToTheNewDay() {
        Employee employee = withVersions(
                LocalTime.of(6, 0), LocalTime.of(15, 0),     // old: early morning
                LocalTime.of(15, 30), LocalTime.of(0, 30),   // new: overnight
                day);
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(day, LocalTime.of(15, 30))));
    }

    /**
     * The exact scenario from the design discussion: check-in Sep 30 15:30 (old version,
     * 15:30-00:30), a NEW version (06:00-15:00) becomes effective Oct 1, checkout happens Oct 1
     * 00:30 — after the new version's own effective date has technically arrived. The entire
     * session must still be governed by the OLD version throughout: the checkout cap
     * ({@link ShiftDayPolicy#shiftEndAt}) resolves the old version's own end (rolled to Oct 1
     * 00:30), and the session is NOT flagged stale (its own boundary, Oct 1 09:30, hasn't passed).
     * Both are asked with {@code record.getWorkDate()} (Sep 30), never the checkout's own
     * timestamp — exactly what {@link AttendanceService#checkOut}/{@code flagMissingCheckoutIfStale}
     * actually pass (verified by reading those call sites, not just this unit test).
     */
    @Test
    void overnightAttendance_crossingAVersionBoundaryAtMidnight_staysGovernedByTheOldVersionThroughout() {
        LocalDate sep30 = LocalDate.of(2026, 9, 30);
        LocalDate oct1 = sep30.plusDays(1);
        Employee employee = withVersions(
                LocalTime.of(15, 30), LocalTime.of(0, 30),   // old: overnight (governs sep30's workDate)
                LocalTime.of(6, 0), LocalTime.of(15, 0),     // new: early morning, effective oct1
                oct1);

        // Checkout cap: record.getWorkDate() = sep30 (fixed at check-in) — must resolve the OLD
        // version's own end, rolled to Oct 1 00:30, NOT the new version's 15:00.
        assertEquals(LocalDateTime.of(oct1, LocalTime.of(0, 30)), policy.shiftEndAt(employee, sep30));

        // Stale check at the moment of checkout (Oct 1 00:30): shiftDayOf(now) must still resolve
        // to sep30 (not yet past its own, old-version-derived boundary of Oct 1 09:30), so
        // AttendanceService.flagMissingCheckoutIfStale's `shiftDayOf(now).isAfter(workDate)` reads
        // false — the session is correctly NOT flagged stale.
        LocalDateTime checkoutInstant = LocalDateTime.of(oct1, LocalTime.of(0, 30));
        assertEquals(sep30, policy.shiftDayOf(employee, checkoutInstant));
        assertFalse(policy.shiftDayOf(employee, checkoutInstant).isAfter(sep30));
    }

    /** A historical workDate (before the new version's effectiveFrom) still resolves the OLD version, even once a newer one exists. */
    @Test
    void maximumAttendanceBoundary_historicalWorkDate_resolvesOldVersion_afterNewerVersionExists() {
        Employee employee = withVersions(
                LocalTime.of(15, 30), LocalTime.of(0, 30),
                LocalTime.of(6, 0), LocalTime.of(15, 0),
                day.plusDays(5));
        // day is before the new version's effectiveFrom (day+5) — must still use the OLD (15:30) start.
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(9, 30)),
                policy.maximumAttendanceBoundary(employee, day));
    }

    // ── workdayStartAt/workdayEndAt: the Attendance timeline's own coordinate system ─────────

    /**
     * The exact worked example from the Attendance UI correction spec: a 10:00-19:00 shift with
     * an 18h maximum workday duration must produce workday start 04:00 / workday end 04:00 the
     * next day — i.e. the timeline's track spans 04:00->10:00->19:00->04:00(+1), never
     * 00:00->24:00.
     */
    @Test
    void workdayStartAndEnd_10to19Shift_18hMax_matchesTheSpecWorkedExample() {
        Employee employee = withShift(LocalTime.of(10, 0), LocalTime.of(19, 0));

        assertEquals(LocalDateTime.of(day, LocalTime.of(4, 0)), policy.workdayStartAt(employee, day));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(4, 0)), policy.workdayEndAt(employee, day));
    }

    /**
     * For a shift whose timing doesn't change day-to-day, one day's workdayEndAt and the next
     * day's workdayStartAt must be the exact same instant — the timeline for consecutive days
     * tiles with no gap or overlap, exactly like ShiftDayPolicy's own shiftDayOf attribution
     * (whose Rule 2 this pairing is derived from) guarantees no punch falls into neither day.
     */
    @Test
    void workdayStartAt_equalsThePreviousDaysOwnWorkdayEnd_forAStableShift() {
        Employee employee = withShift(LocalTime.of(10, 0), LocalTime.of(19, 0));
        assertEquals(policy.workdayEndAt(employee, day), policy.workdayStartAt(employee, day.plusDays(1)));
    }

    /** Overnight shifts: workday window must be derived the same way — never hard-coded to 04:00/any fixed clock time. */
    @Test
    void workdayStartAndEnd_overnightShifts_matchEveryWorkedExample() {
        Employee shift1530to0030 = withShift(LocalTime.of(15, 30), LocalTime.of(0, 30));
        assertEquals(LocalDateTime.of(day, LocalTime.of(9, 30)), policy.workdayStartAt(shift1530to0030, day));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(9, 30)), policy.workdayEndAt(shift1530to0030, day));

        Employee shift1930to0430 = withShift(LocalTime.of(19, 30), LocalTime.of(4, 30));
        assertEquals(LocalDateTime.of(day, LocalTime.of(13, 30)), policy.workdayStartAt(shift1930to0430, day));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(13, 30)), policy.workdayEndAt(shift1930to0430, day));

        Employee shift2200to0700 = withShift(LocalTime.of(22, 0), LocalTime.of(7, 0));
        assertEquals(LocalDateTime.of(day, LocalTime.of(16, 0)), policy.workdayStartAt(shift2200to0700, day));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(16, 0)), policy.workdayEndAt(shift2200to0700, day));
    }

    /**
     * workdayEndAt must be exactly the instant shiftDayOf itself rolls a timestamp onto the next
     * logical workday — one minute before it is still the original workday, the instant itself
     * (and everything after) already belongs to the new one. This is the same 04:00 boundary the
     * spec's "12:21 AM / 3:43 AM still belong to the original workday, 4:00 AM onward is a new
     * one" example describes.
     */
    @Test
    void workdayEndAt_isExactlyWhereShiftDayOfRollsOverToTheNextWorkday() {
        Employee employee = withShift(LocalTime.of(10, 0), LocalTime.of(19, 0));
        LocalDateTime boundary = policy.workdayEndAt(employee, day);
        LocalDate nextDay = day.plusDays(1);

        assertEquals(day, policy.shiftDayOf(employee, boundary.minusMinutes(1)),
                "3:59 AM still belongs to the original workday");
        assertEquals(nextDay, policy.shiftDayOf(employee, boundary),
                "4:00 AM onward already belongs to the new workday");
    }

    @Test
    void workdayStartAt_noShiftEmployee_throws() {
        assertThrows(IllegalStateException.class, () -> policy.workdayStartAt((Employee) null, day));
    }

    @Test
    void workdayEndAt_noShiftEmployee_throws() {
        assertThrows(IllegalStateException.class, () -> policy.workdayEndAt(null, day));
    }

    /**
     * There is no generic fixed-clock-time fallback anymore — a null-shift employee reaching
     * shiftDayOf/maximumAttendanceBoundary is now an anomaly (every employee is expected to have
     * an assigned shift — see Shift.DEFAULT_SHIFT_NAME's server-side default on creation and
     * ShiftSeedCorrector's startup backfill) and must fail loudly rather than silently guess a
     * day boundary via any fixed clock time (the old 07:00 shiftDayCutover rule has been removed
     * entirely, from both this class and AttendanceProperties). This must throw immediately —
     * never fall through to the "today's own start" check using resolveShiftStart's narrow global
     * fallback, which would otherwise silently reintroduce a day-attribution fallback.
     */
    @Test
    void shiftDayOf_noShiftEmployee_throwsRatherThanFallingBackToAnyFixedClockTime() {
        LocalDateTime anyTimestamp = LocalDateTime.of(day.plusDays(1), LocalTime.of(6, 59));
        assertThrows(IllegalStateException.class, () -> policy.shiftDayOf((Employee) null, anyTimestamp));
    }

    @Test
    void maximumAttendanceBoundary_noShiftEmployee_throws() {
        assertThrows(IllegalStateException.class, () -> policy.maximumAttendanceBoundary(null, day));
    }

    @Test
    void shiftEndAt_overnightShift_rollsIntoTheNextCalendarDay() {
        Employee employee = withShift(LocalTime.of(15, 30), LocalTime.of(0, 30));
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(0, 30)), policy.shiftEndAt(employee, day));
    }

    @Test
    void shiftEndAt_sameDayShift_staysOnTheSameCalendarDay() {
        Employee employee = withShift(LocalTime.of(9, 0), LocalTime.of(18, 0));
        assertEquals(LocalDateTime.of(day, LocalTime.of(18, 0)), policy.shiftEndAt(employee, day));
    }

    @Test
    void shiftEndAt_noShiftEmployee_throws() {
        assertThrows(IllegalStateException.class, () -> policy.shiftEndAt(null, day));
    }

    @Test
    void isOvernight_trueOnlyWhenEndIsNotAfterStart() {
        Shift overnight = Shift.builder().id(UUID.randomUUID()).name("Overnight").build();
        allVersions.add(ShiftVersion.builder().shift(overnight).startTime(LocalTime.of(15, 30)).endTime(LocalTime.of(0, 30)).effectiveFrom(LocalDate.MIN).build());
        Shift sameDay = Shift.builder().id(UUID.randomUUID()).name("SameDay").build();
        allVersions.add(ShiftVersion.builder().shift(sameDay).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(LocalDate.MIN).build());

        assertTrue(policy.isOvernight(overnight, day));
        assertFalse(policy.isOvernight(sameDay, day));
    }

    @Test
    void resolveShiftStart_prefersAssignedShift_elseThrows() {
        Employee withShift = withShift(LocalTime.of(20, 30), LocalTime.of(5, 30));
        assertEquals(LocalTime.of(20, 30), policy.resolveShiftStart(withShift, day));
        // No employee having a null Shift is a legitimate business state anymore — see
        // ShiftDayPolicy's own Javadoc — so there is no fallback clock time left to fall back to.
        assertThrows(IllegalStateException.class, () -> policy.resolveShiftStart(null, day));
    }

    /**
     * The corrected reset boundary is a PURE QUERY — verifies it never mutates anything it's
     * given (nothing to mutate, since it only takes an Employee/LocalDate/LocalDateTime and
     * returns a value). This is a documentation-style test: the real guarantee is structural (no
     * method on this class has a return type of void, touches a repository, or is annotated
     * @Transactional) — asserted here by simply calling every method twice and getting identical,
     * side-effect-free results.
     */
    // ── Brand-new Shift (ONEHR-336 follow-up): no version before its own creation date ───────
    // Reproduces the reported bug: a Shift created TODAY (effective only from today onward, no
    // version at all before it — see withBrandNewShift) assigned to an employee whose onboarding
    // date predates the Shift's own creation. A fresh check-in must never fail merely because
    // "yesterday" (before the Shift ever existed) has no version to resolve — see ShiftDayPolicy's
    // own Javadoc, "Brand-new Shift case".

    @Test
    void shiftDayOf_brandNewShift_earlyPunchBeforeTodaysOwnStart_stillResolvesToToday() {
        // The exact failure mode reported: Shift "test" created (and assigned) today, employee
        // checks in before today's own shift start. Rule 1 fails (timestamp < today's start), so
        // Rule 2 would previously try to resolve yesterday's boundary and throw
        // IllegalStateException("... has no version effective on or before <yesterday> ...") since
        // no version covers a date before the Shift's own creation.
        Employee employee = withBrandNewShift(LocalTime.of(9, 0), LocalTime.of(18, 0), day);
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(day, LocalTime.of(7, 0))),
                "an early punch on a brand-new Shift's own first day must resolve to today, not throw for a nonexistent yesterday");
    }

    @Test
    void shiftDayOf_brandNewShift_punchAfterTodaysOwnStart_resolvesToTodayViaRuleOne() {
        // Never even reaches Rule 2 (today's own start already covers it) — included as the
        // unaffected counterpart to the early-punch case above.
        Employee employee = withBrandNewShift(LocalTime.of(9, 0), LocalTime.of(18, 0), day);
        assertEquals(day, policy.shiftDayOf(employee, LocalDateTime.of(day, LocalTime.of(9, 0))));
    }

    @Test
    void workdayStartAt_brandNewShift_hasNoEarlierBoundary_startsAtItsOwnShiftStart() {
        // workdayStartAt(day) normally equals the PREVIOUS day's maximumAttendanceBoundary (see
        // workdayStartAt_equalsThePreviousDaysOwnWorkdayEnd_forAStableShift above) — but a
        // brand-new Shift has no version for the previous day at all, so there is no earlier
        // boundary to roll over from; the workday can only start at the Shift's own first start.
        Employee employee = withBrandNewShift(LocalTime.of(9, 0), LocalTime.of(18, 0), day);
        assertEquals(LocalDateTime.of(day, LocalTime.of(9, 0)), policy.workdayStartAt(employee, day));
    }

    @Test
    void resolveShiftStart_dateBeforeABrandNewShiftExisted_stillThrows_invariantNotWeakened() {
        // The underlying "every applicable work date must resolve a version" invariant
        // (ShiftVersionResolver#resolve) is untouched by the fix above — only shiftDayOf's/
        // workdayStartAt's own "did the Shift already exist as of yesterday" pre-check treats that
        // absence as expected; a caller asking to resolve an actually-applicable date must still
        // throw exactly as before.
        Employee employee = withBrandNewShift(LocalTime.of(9, 0), LocalTime.of(18, 0), day);
        assertThrows(IllegalStateException.class, () -> policy.resolveShiftStart(employee, day.minusDays(1)));
    }

    @Test
    void shiftDayOf_dayAfterABrandNewShiftWasCreated_ordinaryOvernightRolloverStillWorks() {
        // One day further out: Rule 2's boundary for the shift's OWN creation day (not before it)
        // does exist, so an ordinary overnight rollover into the day after next is unaffected —
        // confirms the fix is scoped to the exact "no version at all yet" case, not a blanket
        // skip of Rule 2.
        LocalDate createdOn = day;
        Employee employee = withBrandNewShift(LocalTime.of(15, 30), LocalTime.of(0, 30), createdOn);
        LocalDate dayAfter = createdOn.plusDays(1);
        assertEquals(createdOn, policy.shiftDayOf(employee, LocalDateTime.of(dayAfter, LocalTime.of(0, 20))),
                "a post-midnight punch still rolls back to the shift's own creation day, exactly like an ordinary overnight shift");
    }

    // ── Day-aware UUID-taking overloads (EmployeeShiftAssignment effective-dating) ───────────

    /**
     * The exact walkthrough from the design gate: yesterday's ASSIGNMENT (not just Version) is a
     * completely different Shift entity than today's — Shift A (22:00-06:00 overnight, 18h max)
     * yesterday, reassigned to Shift B (09:00-18:00) effective today. A 05:00 check-in today fails
     * Rule 1 under B (05:00 < 09:00), so Rule 2 must resolve YESTERDAY's own boundary under A —
     * 22:00 + 18h = 16:00 the next day (today) — 05:00 is within it, so this is still yesterday's
     * overtime tail, not a fresh start of today. Reusing B (resolved once for "today") instead
     * would compute a materially different, wrong boundary (09:00 + 18h = 03:00 today), flipping
     * the attribution — proving Rule 1 and Rule 2 must resolve their own day independently.
     */
    @Test
    void shiftDayOf_uuidOverload_assignmentBoundaryCrossing_rule2ResolvesYesterdaysOwnShift() {
        Shift shiftA = Shift.builder().id(UUID.randomUUID()).name("A-Overnight").build();
        allVersions.add(ShiftVersion.builder().shift(shiftA).startTime(LocalTime.of(22, 0)).endTime(LocalTime.of(6, 0)).effectiveFrom(LocalDate.MIN).build());
        Shift shiftB = Shift.builder().id(UUID.randomUUID()).name("B-Morning").build();
        allVersions.add(ShiftVersion.builder().shift(shiftB).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(LocalDate.MIN).build());

        UUID employeeUserId = UUID.randomUUID();
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shiftA).effectiveFrom(LocalDate.MIN).build());
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shiftB).effectiveFrom(day).build());

        LocalDateTime earlyPunchToday = LocalDateTime.of(day, LocalTime.of(5, 0));
        assertEquals(day.minusDays(1), policy.shiftDayOf(employeeUserId, earlyPunchToday),
                "still yesterday's overtime tail under Shift A — Rule 2 must not reuse Shift B (today's assignment)");
    }

    /** The mirror, unaffected-by-the-fix case: a punch AFTER today's own (new-assignment) start never even reaches Rule 2. */
    @Test
    void shiftDayOf_uuidOverload_assignmentBoundaryCrossing_onTimeArrivalUnderNewAssignmentBelongsToToday() {
        Shift shiftA = Shift.builder().id(UUID.randomUUID()).name("A-Overnight").build();
        allVersions.add(ShiftVersion.builder().shift(shiftA).startTime(LocalTime.of(22, 0)).endTime(LocalTime.of(6, 0)).effectiveFrom(LocalDate.MIN).build());
        Shift shiftB = Shift.builder().id(UUID.randomUUID()).name("B-Morning").build();
        allVersions.add(ShiftVersion.builder().shift(shiftB).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(LocalDate.MIN).build());

        UUID employeeUserId = UUID.randomUUID();
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shiftA).effectiveFrom(LocalDate.MIN).build());
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shiftB).effectiveFrom(day).build());

        assertEquals(day, policy.shiftDayOf(employeeUserId, LocalDateTime.of(day, LocalTime.of(9, 0))));
    }

    /** Mirrors the brand-new-Shift ShiftVersion fix, one layer up: a brand-new employee/assignment's early punch on its own first day must resolve to today, not throw for a nonexistent yesterday. */
    @Test
    void shiftDayOf_uuidOverload_brandNewAssignment_earlyPunchBeforeTodaysStart_stillResolvesToToday() {
        UUID employeeUserId = withAssignment(withShift(LocalTime.of(9, 0), LocalTime.of(18, 0)).getShift(), day);
        assertEquals(day, policy.shiftDayOf(employeeUserId, LocalDateTime.of(day, LocalTime.of(7, 0))));
    }

    /** workdayStartAt(UUID, ...) needs the identical per-day resolution — same walkthrough as shiftDayOf above. */
    @Test
    void workdayStartAt_uuidOverload_assignmentBoundaryCrossing_resolvesYesterdaysOwnShift() {
        Shift shiftA = Shift.builder().id(UUID.randomUUID()).name("A-Overnight").build();
        allVersions.add(ShiftVersion.builder().shift(shiftA).startTime(LocalTime.of(22, 0)).endTime(LocalTime.of(6, 0)).effectiveFrom(LocalDate.MIN).build());
        Shift shiftB = Shift.builder().id(UUID.randomUUID()).name("B-Morning").build();
        allVersions.add(ShiftVersion.builder().shift(shiftB).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(LocalDate.MIN).build());

        UUID employeeUserId = UUID.randomUUID();
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shiftA).effectiveFrom(LocalDate.MIN).build());
        allAssignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeUserId).shift(shiftB).effectiveFrom(day).build());

        // Yesterday's own boundary under Shift A: 22:00 + 18h = 16:00 the next day (today).
        assertEquals(LocalDateTime.of(day, LocalTime.of(16, 0)), policy.workdayStartAt(employeeUserId, day));
    }

    /** Mirrors workdayStartAt(Employee, ...)'s identical brand-new-shift fallback, one layer up. */
    @Test
    void workdayStartAt_uuidOverload_brandNewAssignment_hasNoEarlierBoundary_startsAtItsOwnShiftStart() {
        UUID employeeUserId = withAssignment(withShift(LocalTime.of(9, 0), LocalTime.of(18, 0)).getShift(), day);
        assertEquals(LocalDateTime.of(day, LocalTime.of(9, 0)), policy.workdayStartAt(employeeUserId, day));
    }

    @Test
    void everyMethod_isPureAndSideEffectFree_callingTwiceGivesIdenticalResults() {
        Employee employee = withShift(LocalTime.of(15, 30), LocalTime.of(0, 30));
        LocalDateTime now = LocalDateTime.of(day.plusDays(1), LocalTime.of(10, 0));

        assertEquals(policy.shiftDayOf(employee, now), policy.shiftDayOf(employee, now));
        assertEquals(policy.maximumAttendanceBoundary(employee, day), policy.maximumAttendanceBoundary(employee, day));
        assertEquals(policy.shiftEndAt(employee, day), policy.shiftEndAt(employee, day));
        assertEquals(policy.resolveShiftStart(employee, day), policy.resolveShiftStart(employee, day));
    }
}
