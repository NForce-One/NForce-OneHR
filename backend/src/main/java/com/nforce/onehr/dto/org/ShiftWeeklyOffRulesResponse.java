package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.ShiftWeeklyOffRules;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class ShiftWeeklyOffRulesResponse {
    UUID id;
    double maximumShiftDayDurationHours;
    LocalDateTime updatedAt;

    public static ShiftWeeklyOffRulesResponse from(ShiftWeeklyOffRules rules) {
        return new ShiftWeeklyOffRulesResponse(rules.getId(),
                rules.getMaximumShiftDayDurationHours().doubleValue(), rules.getUpdatedAt());
    }
}
