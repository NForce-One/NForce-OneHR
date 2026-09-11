package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

// Super Admin scope — all fields editable including manager (triggers history) and role.
@Data
public class UpdateUserRequest {
    @Pattern(regexp = "^(?=.*\\p{L})[\\p{L}\\s'-]+$",
             message = "Full name can only contain letters, spaces, hyphens, and apostrophes")
    private String fullName;

    // Optional — null/blank means "leave unchanged" (same convention as every other field here).
    // Mirrors CreateUserRequest's identical @Email + @Pattern pair; see its comment for why the
    // extra pattern is needed on top of bare @Email.
    @Email
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Enter a valid email address with a proper domain (e.g. name@company.com)")
    private String email;

    private String role;
    private UUID businessUnitId;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;
    private UUID shiftId;
    private String employmentType;
    private String workMode;
    private UUID managerId;

    // Role/manager/department/designation/employment type imply active employment — changing
    // any of them for a deactivated user is blocked unless the caller explicitly confirms (see
    // UserManagementService#updateUser). Defaults to false so a stale/older client that never
    // sends this field is always treated as unconfirmed.
    private boolean confirmInactiveEdit;

    // Deliberately NO timezone field — see UpdateEmployeeRequest's identical comment.
}
