package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

// Super Admin scope — role field included, all 4 Phase 1 roles allowed.
@Data
public class CreateUserRequest {
    @NotBlank
    @Pattern(regexp = "^(?=.*\\p{L})[\\p{L}\\s'-]+$", message = "Full name can only contain letters, spaces, hyphens, and apostrophes")
    private String fullName;

    // @Email alone accepts things like "a@99999999999" or "a@example.com123" — it has no
    // opinion on the TLD. The explicit pattern enforces a real, letters-only TLD with nothing
    // trailing it, per the reported business rule.
    @NotBlank
    @Email
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Enter a valid email address with a proper domain (e.g. name@company.com)")
    private String email;

    // EMPLOYEE | MANAGER | HR_ADMIN | SUPER_ADMIN
    @NotBlank
    private String role;

    private String employeeCode;
    private UUID businessUnitId;
    private UUID departmentId;
    private UUID designationId;
    private UUID shiftId;

    @NotNull
    private UUID locationId;

    private String employmentType = "FULL_TIME";
    private String workMode = "ONSITE";

    @NotNull
    private LocalDate joiningDate;

    private UUID managerId;

    // Admin-set only — this endpoint is Super-Admin-only (see class comment). IANA zone id, e.g.
    // "Asia/Kolkata"; null/blank leaves it unset, falling back to Location.timezone then the
    // org-wide default — see AttendanceService.resolveZone.
    private String timezone;
}
