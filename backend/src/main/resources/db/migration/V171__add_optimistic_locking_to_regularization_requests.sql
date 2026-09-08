-- Comprehensive attendance/penalization audit (2026-09-08) found a real, reproducible race:
-- RegularizationService.approve() and .reject() both read-modify-write the same
-- regularization_requests row via a plain findById() + in-memory status check
-- (STATUS_PENDING/STATUS_PARTIALLY_APPROVED), with nothing else coordinating between them —
-- unlike attendance_records, which has had exactly this protection since V164.
--
-- Concretely: a Manager's approve() and a concurrent reject() (or two overlapping approve()
-- calls) on the same still-PENDING request can both read the row before either commits, both
-- pass their status gate, and both commit — approve() has already corrected the Attendance row,
-- audited "REGULARIZATION_APPROVED", and notified the employee, while reject()'s plain UPDATE
-- (last writer wins, no version check) leaves regularization_requests.status = 'REJECTED' with
-- its own "REGULARIZATION_REJECTED" audit row and notification — an inconsistent, self-
-- contradictory audit trail with no rollback of the already-applied Attendance mutation.
--
-- @Version turns the losing transaction's save() into an ObjectOptimisticLockingFailureException
-- (translated to a clean 409 by GlobalExceptionHandler, already relied on for this exact
-- exception type elsewhere) instead of a silent, contradictory double-commit.
ALTER TABLE regularization_requests
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
