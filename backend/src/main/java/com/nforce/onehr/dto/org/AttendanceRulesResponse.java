package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.AttendanceRules;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class AttendanceRulesResponse {
    UUID id;
    double halfDayMaxHours;
    String defaultTimezone;
    LocalDateTime updatedAt;

    public static AttendanceRulesResponse from(AttendanceRules rules) {
        return new AttendanceRulesResponse(rules.getId(),
                rules.getHalfDayMaxHours().doubleValue(), rules.getDefaultTimezone(), rules.getUpdatedAt());
    }
}
