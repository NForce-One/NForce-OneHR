package com.nforce.onehr.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Shift rules used to derive attendance status. Values are configurable so the PO can tune
 * them without a code change — the thresholds in the defaults below are provisional and
 * pending final confirmation.
 */
@Component
@ConfigurationProperties(prefix = "app.attendance")
@Getter @Setter
public class AttendanceProperties {

    /**
     * Business timezone — deploy-time default, still read directly by every non-Attendance-punch
     * consumer that needs a single org-wide "what business day is it right now" clock (Leave,
     * Penalization Policy, EmployeeAssignmentService's effective-from dates, ExceptionService's
     * own detection-day cutoff, AttendanceRequestService). Must NOT be left to the JVM default —
     * Railway runs UTC.
     *
     * <p>As of the Phase 2 timezone pass, this is NO LONGER the org-wide default for the
     * Attendance/Web-Clock-In/Regularization flow specifically — that flow now reads {@link
     * com.nforce.onehr.service.AttendanceRulesService#getDefaultZoneId()} instead, an
     * Admin-configurable singleton seeded from this same value (see V167's migration comment),
     * which in turn only applies once an employee has neither their own {@code
     * Employee.timezone} nor a {@code Location.timezone} set. Deliberately left un-migrated here
     * for its other, non-attendance consumers — migrating those is a separate, unrelated
     * refactor this pass did not undertake.
     */
    private String zone = "Asia/Kolkata";

    /**
     * Section 7: org-wide policy for an employee with no allocation and no legacy FK. Defaults to
     * {@code DEFAULT_POLICY} so no existing org's behavior changes unless explicitly opted into
     * {@code REQUIRE_ALLOCATION} (e.g. {@code app.attendance.penalization-fallback-strategy: REQUIRE_ALLOCATION}).
     */
    private PenalizationFallbackStrategy penalizationFallbackStrategy = PenalizationFallbackStrategy.DEFAULT_POLICY;
}
