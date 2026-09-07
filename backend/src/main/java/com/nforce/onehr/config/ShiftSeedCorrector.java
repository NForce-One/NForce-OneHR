package com.nforce.onehr.config;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Backfills the organization's Default Shift ({@link Shift#DEFAULT_SHIFT_NAME}) onto any employee
 * left without a shift assignment.
 *
 * <p><b>No longer force-corrects the Default Shift's own timings</b> — see git history for the
 * original rationale (a shared-dev-DB migration-checksum issue from before Shift Management was a
 * real, admin-editable feature). If its timing is ever wrong now, fix it through the Shift
 * Management UI (Organization Structure → Shifts) — not here.
 *
 * <p>The employee backfill exists because V95's "assign everyone the default shift" UPDATE (back
 * when it was still named "Regular Shift" — see V161 for the rename to the canonical "Default
 * Shift") only ran once, against whoever existed at that moment — any employee onboarded since
 * (via a flow that doesn't explicitly pick a shift) had a null {@code shift_id}, which silently
 * fell back to {@code AttendanceProperties.shiftStart} for lateness math instead of a real shift,
 * producing wildly wrong "Xh late" figures. This assigns them the Default Shift — whatever its
 * current, admin-configured timing actually is — as a sane default, same as it always did.
 *
 * <p><b>Employee creation now also defaults to this same shift server-side</b> (see
 * {@code UserManagementService#createUser}/{@code EmployeeService#createEmployee}), so a
 * null-shift employee should no longer normally occur going forward — this backfill remains as
 * the safety net for any pre-existing row and for the narrow startup window before that default
 * takes effect. {@link com.nforce.onehr.service.ShiftDayPolicy} has no fallback for a null shift
 * (it throws) — every employee reaching attendance flows is expected to have a real shift, which
 * this backfill (plus the create-time default) is what guarantees.
 *
 * <p><b>The Default Shift is a completely ordinary Shift for edit/timing purposes</b> — the same
 * validation/versioning rules as any other apply to its start/end/break configuration. If an admin
 * edits its timing, every employee defaulted to it (by this backfill or by create-time default)
 * picks up the new timing automatically — this always reads the live row, never a cached/
 * duplicated copy.
 *
 * <p><b>But it is not an ordinary Shift with respect to its own identity</b> — because the
 * organization must always have exactly one resolvable default, {@code OrgService} refuses to
 * rename, deactivate, or delete it (regardless of employee count), so the scenarios this backfill
 * and both create-employee paths used to have to tolerate gracefully (default missing, inactive,
 * or deleted) are no longer expected to occur through any normal admin action. This backfill's own
 * {@code !shift.isActive()} check below is now a defense-in-depth safety net for a state that
 * should be unreachable, not an expected/tolerated case — and both create-employee paths now fail
 * loudly ({@code IllegalStateException}) rather than silently leaving a new employee shift-less if
 * they ever do find it missing or inactive.
 *
 * <p><b>Deliberately does NOT recompute historical {@code Attendance.lateByMinutes}/{@code
 * status} anymore.</b> This corrector previously also re-derived every existing attendance
 * record's lateness against whichever shift each employee is CURRENTLY assigned — meaning
 * reassigning an employee to a different shift would silently rewrite their entire attendance
 * history on the next restart, using a shift that may not have applied on those historical dates
 * at all. That is exactly the "historical attendance must not be recalculated using the
 * employee's current shift after the employee's shift changes" failure mode this class must not
 * reintroduce. There is no signal anywhere in the schema (no shift snapshot, no audit of prior
 * values — both deliberately not introduced, see the Shift+Weekly-Off architecture notes)
 * distinguishing a {@code lateByMinutes} that was computed correctly at check-in time from one
 * that was previously overwritten by this corrector using a since-superseded shift, so any
 * already-drifted historical values from before this fix are left exactly as they stand — there
 * is no data-driven way to selectively "un-rewrite" them. Going forward, {@code
 * Attendance.lateByMinutes} is computed once at check-in/regularization time and never silently
 * re-derived again by anything running afterward.
 */
// Must run before StaleAttendanceSweeper's startup pass: the sweep's shift-end cutoff reads
// each employee's Shift.endTime, which this corrector's backfill may still be about to set.
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
@Slf4j
public class ShiftSeedCorrector implements ApplicationRunner {

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME).ifPresent(this::backfillUnassignedEmployees);
    }

    private void backfillUnassignedEmployees(Shift shift) {
        // Same "must be active to be newly assigned" rule every other assignment path already
        // enforces (create-employee, bulk-assign, CSV import) — the Default Shift is a completely
        // ordinary shift an admin can deactivate, and this backfill assigning it to someone is
        // itself a new assignment, so it must not silently bypass that rule just because it's the
        // organization's default. If it's currently inactive, this run skips the
        // backfill entirely (leaving those employees shift-less, same as if it didn't exist) —
        // deliberate and consistent, not a silent gap.
        if (!shift.isActive()) {
            return;
        }
        List<Employee> unassigned = employeeRepository.findByShiftIsNull();
        if (unassigned.isEmpty()) {
            return;
        }
        log.warn("Assigning '{}' shift to {} employee(s) with no shift set (onboarded after V95's "
                + "one-time backfill)", Shift.DEFAULT_SHIFT_NAME, unassigned.size());
        unassigned.forEach(employee -> employee.setShift(shift));
        employeeRepository.saveAll(unassigned);
    }
}
