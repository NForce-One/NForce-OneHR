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

    // Admin-set only — this endpoint is already HR_ADMIN/SUPER_ADMIN-gated (see
    // EmployeeController). Distinct from the self-service ProfileController/UpdateProfileRequest
    // path, which deliberately has no timezone field at all. Three states, since JSON can't
    // otherwise distinguish "omitted" from "explicitly null" once deserialized: null = leave
    // unchanged; "" (blank) = clear it, falling back to Location.timezone/org default; anything
    // else = set it — see EmployeeService#updateEmployee.
    private String timezone;
}
