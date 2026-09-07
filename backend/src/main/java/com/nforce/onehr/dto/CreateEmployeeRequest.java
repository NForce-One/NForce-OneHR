package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateEmployeeRequest {
    @NotBlank
    @Pattern(regexp = "^(?=.*\\p{L})[\\p{L}\\s'-]+$", message = "Full name can only contain letters, spaces, hyphens, and apostrophes")
    private String fullName;

    // See CreateUserRequest — @Email alone doesn't enforce a real, letters-only TLD.
    @NotBlank
    @Email
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Enter a valid email address with a proper domain (e.g. name@company.com)")
    private String email;

    private String employeeCode;

    private UUID businessUnitId;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;

    private String employmentType = "FULL_TIME";
    private String workMode = "ONSITE";

    @NotNull
    private LocalDate joiningDate;

    private UUID managerId;

    // Admin-set only — this endpoint is already HR_ADMIN/SUPER_ADMIN-gated (see
    // EmployeeController). IANA zone id, e.g. "Asia/Kolkata"; null/blank leaves it unset, falling
    // back to the employee's Location.timezone then the org-wide default — see
    // AttendanceService.resolveZone.
    private String timezone;
}
