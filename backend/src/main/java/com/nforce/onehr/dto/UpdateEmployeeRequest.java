package com.nforce.onehr.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

// HR Admin scope — only dept/designation/location/employment_type.
// Manager and role changes are Super-Admin-only via /api/users.
@Data
public class UpdateEmployeeRequest {
    private UUID businessUnitId;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;
    private String employmentType;
    private String workMode;
    @Pattern(regexp = "^(?=.*\\p{L})[\\p{L}\\s'-]+$",
             message = "Full name can only contain letters, spaces, hyphens, and apostrophes")
    private String fullName;

    // Department/designation/employment type imply active employment — changing them for a
    // deactivated employee is blocked unless the caller explicitly confirms (see
    // EmployeeService#updateEmployee). Defaults to false so a stale/older client that never
    // sends this field is always treated as unconfirmed.
    private boolean confirmInactiveEdit;

    // Deliberately NO timezone field — see CreateEmployeeRequest's own comment. locationId above
    // is the only timezone-relevant input this request accepts.
}
