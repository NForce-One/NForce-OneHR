package com.nforce.onehr.dto.expense;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SubmitExpenseClaimRequest {
    @NotNull  private Integer categoryId;
    @NotNull  @DecimalMin("0.01") private BigDecimal amount;
    @NotNull  @PastOrPresent(message = "Expense date cannot be in the future") private LocalDate expenseDate;
    @NotBlank private String businessPurpose;
    // Base64 data URI or URL — null/absent means no receipt attached.
    // Backend validates against category's requires_receipt_above threshold.
    private String receiptUrl;
}
