package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateAttendanceRulesRequest {

    // Bounds mirror AttendanceRulesService's own validation and V162's DB-level CHECK — a
    // half-day threshold must be a positive fraction of a calendar day, strictly less than a
    // full 24h day. Validated here too so a bad value is rejected with a normal 400 before it
    // ever reaches the service/DB layer.
    @NotNull(message = "Half Day Max Hours is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Half Day Max Hours must be greater than 0 hours")
    @DecimalMax(value = "24.0", inclusive = false, message = "Half Day Max Hours must be less than 24 hours")
    private BigDecimal halfDayMaxHours;
}
