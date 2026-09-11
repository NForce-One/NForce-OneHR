package com.nforce.onehr.dto.expense;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateExpenseCategoryRequest {
    @NotBlank(message = "Category name is required")
    @Size(max = 60, message = "Category name must be 60 characters or fewer")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])[^0-9]+$",
            message = "Category name must contain letters and cannot contain numbers or be made up of special characters only")
    private String name;
    @NotNull  private BigDecimal requiresReceiptAbove;
    @DecimalMin(value = "0", message = "Daily limit cannot be negative")
    private BigDecimal dailyLimit;
    @DecimalMin(value = "0", message = "Second approval threshold cannot be negative")
    private BigDecimal secondApprovalAbove;
}
