package com.nforce.onehr.dto.attendance;

/**
 * Every outcome {@link com.nforce.onehr.service.AttendanceInterpretationService} may return. Do
 * not add a third value without a corresponding product decision — the whole point of this being
 * closed is that a caller can never silently treat an unresolved interpretation as if it were a
 * normal one.
 */
public enum InterpretationOutcome {
    /** Resolved against a real, known Shift — either the employee's current one (a fresh action)
     *  or the Shift snapshotted on the Attendance row being interpreted (an existing session). */
    RESOLVED,
    /**
     * The Attendance row being interpreted predates the {@code shiftId} snapshot column
     * (created before this Shift/Attendance decoupling shipped) and has no recorded Shift
     * context. Deliberately NEVER resolved by substituting the employee's current Shift — see
     * AttendanceInterpretationService's own Javadoc. Callers must degrade explicitly (e.g. treat
     * an open legacy session as unresolved for staleness/cutoff purposes, or surface that a
     * historical correction's lateness cannot be safely recomputed) rather than guess.
     */
    LEGACY_UNRESOLVED
}
