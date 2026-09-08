package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
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
        // ShiftVersionRepository's own query semantics.
        ShiftVersionResolver resolver = new ShiftVersionResolver(null) {
            @Override
            public ShiftVersion resolve(Shift shift, LocalDate workDate) {
                return allVersions.stream()
                        .filter(v -> v.getShift().getId().equals(shift.getId()))
                        .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                        .max(java.util.Comparator.comparing(ShiftVersion::getEffectiveFrom))
                        .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
            }
        };
        policy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), resolver);
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
        assertThrows(IllegalStateException.class, () -> policy.workdayStartAt(null, day));
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
        assertThrows(IllegalStateException.class, () -> policy.shiftDayOf(null, anyTimestamp));
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
