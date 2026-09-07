package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateShiftWeeklyOffRulesRequest {

    // Bounds mirror ShiftWeeklyOffRulesService's own validation and V158's DB-level CHECK — see
    // either's comment for why (0, 24] and not some other range: no existing domain rule
    // establishes this bound, so it's fixed at the largest value for which ShiftDayPolicy's
    // single-candidate-day algorithm stays unambiguous. Validated here too so a bad value is
    // rejected with a normal 400 before it ever reaches the service/DB layer.
    @NotNull(message = "Maximum Shift Day Duration is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Maximum Shift Day Duration must be greater than 0 hours")
    @DecimalMax(value = "24.0", message = "Maximum Shift Day Duration must be at most 24 hours")
    private BigDecimal maximumShiftDayDurationHours;
}
