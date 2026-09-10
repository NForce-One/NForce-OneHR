package com.nforce.onehr.dto.assignments;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shared request shape for all three bulk-update endpoints (shift/weekly-off/penalisation). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkAssignmentRequest {

    @NotEmpty(message = "At least one employee id is required")
    private List<UUID> employeeUserIds;

    @NotNull(message = "A policy id is required")
    private UUID policyId;

    // Shift assignment ONLY (see EmployeeAssignmentService#bulkUpdateShift) — required and
    // validated future-only there, mirroring OrgService#updateShift's identical rule for a
    // Shift's own timing. Ignored by the weekly-off/penalisation-policy bulk endpoints, which
    // remain effective-today, open-ended, exactly as before.
    private LocalDate effectiveFrom;
}
