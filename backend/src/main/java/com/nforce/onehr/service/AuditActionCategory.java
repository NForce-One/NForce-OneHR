package com.nforce.onehr.service;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Splits the free-text {@code audit_log.action} values into the two slices the read API
 * exposes per role: HR Admin only ever sees {@link #HR_OPERATIONAL}; Super Admin sees both.
 * The split is by originating service (confirmed with product): everything logged by
 * AuthService/UserManagementService is access-control, everything else is HR-operational.
 *
 * <p>This is also the single source of truth for which actions are trackable in this feature
 * at all — the audit pages were narrowed from a system-wide trail to a personal activity
 * history, so pure authentication/session events (AuthService's login and password
 * self-service flows), self-service attendance punches (attendance check-in/out, web
 * clock-in requests and checkout), and self-submitted requests (leave/expense/asset
 * submissions, regularization requests/edits) are deliberately omitted from both sets
 * below — they never appear anywhere in the feature (table, cards, stats, or export),
 * since {@link AuditActionGroup#knownActions()} and every query in {@code AuditQueryService}
 * derive from {@link #allActions()}.
 */
public enum AuditActionCategory {

    ACCESS_CONTROL(Set.of(
            // UserManagementService — real admin actions performed ON another account (resets,
            // activation, removal). USER_CREATED belongs here too — creating a new user account
            // (with a role, not just an EMPLOYEE-only profile) is Super-Admin-only, unlike
            // USER_UPDATED, which is classified as HR_OPERATIONAL (Employee Management) instead
            // — see below. AuthService's login and password self-service events are intentionally
            // absent — see class Javadoc.
            "USER_CREATED", "PASSWORD_RESET",
            "USER_ACTIVATED", "USER_DEACTIVATED", "USER_SOFT_DELETED"
    )),

    HR_OPERATIONAL(Set.of(
            // AssetService — ASSET_REQUEST_SUBMITTED (self-submitted) intentionally omitted
            "ASSET_REQUEST_APPROVED", "ASSET_REQUEST_REJECTED",
            "ASSET_CREATED", "ASSET_ASSIGNED", "ASSET_REASSIGNED", "ASSET_RETURNED",
            "ASSET_RETIRED", "ASSET_REQUEST_FULFILLED",
            // EmployeeService — HR Admin's employee create/update path
            "EMPLOYEE_CREATED", "EMPLOYEE_UPDATED",
            // UserManagementService — Super Admin's parallel *update* path for the same kind of
            // edit (same fields, via a different screen/endpoint) as EMPLOYEE_UPDATED above, so
            // it's classified as Employee Management rather than access-control despite
            // originating from the same service as USER_CREATED (ACCESS_CONTROL, above).
            "USER_UPDATED",
            // ExpenseService — EXPENSE_SUBMITTED (self-submitted) intentionally omitted
            "EXPENSE_MANAGER_APPROVED", "EXPENSE_MANAGER_REJECTED",
            "EXPENSE_FINAL_APPROVED", "EXPENSE_FINAL_REJECTED", "EXPENSE_MARKED_PAID",
            // LeaveService — LEAVE_REQUEST_SUBMITTED (self-submitted) intentionally omitted
            "LEAVE_REQUEST_APPROVED", "LEAVE_REQUEST_REJECTED",
            // RegularizationService — REGULARIZATION_REQUESTED/UPDATED (self-submitted/self-edit)
            // intentionally omitted
            "REGULARIZATION_APPROVED", "REGULARIZATION_REJECTED",
            // WebClockInService — WEB_CLOCK_IN_CHECKED_IN/WEB_CLOCK_OUT/WEB_CLOCK_IN_CANCELLED
            // (self-service punches, same treatment as AttendanceService's own check-in/
            // check-out) intentionally omitted — there is no approval decision to track at all.
            // PenalizationPolicyService — Organization Masters configuration changes, not a
            // self-service action, so both HR Admin and Super Admin see them (unlike
            // ACCESS_CONTROL, which is Super-Admin-only).
            "PENALIZATION_POLICY_CREATED", "PENALIZATION_POLICY_UPDATED"
    ));

    private final Set<String> actions;

    AuditActionCategory(Set<String> actions) {
        this.actions = actions;
    }

    public Set<String> actions() {
        return actions;
    }

    /**
     * Classifies an action string. Unrecognized/future actions default to HR_OPERATIONAL —
     * visible to more roles — rather than silently vanishing from HR Admin's view.
     */
    public static AuditActionCategory of(String action) {
        return ACCESS_CONTROL.actions.contains(action) ? ACCESS_CONTROL : HR_OPERATIONAL;
    }

    public static Set<String> allActions() {
        return Stream.concat(ACCESS_CONTROL.actions.stream(), HR_OPERATIONAL.actions.stream())
                .collect(Collectors.toSet());
    }
}
