package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.WeeklyOffPolicy;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Value
public class WeeklyOffPolicyResponse {
    UUID id;
    String name;
    List<String> offDays;
    long employeeCount;
    LocalDateTime createdAt;

    public static WeeklyOffPolicyResponse from(WeeklyOffPolicy p, long employeeCount) {
        List<String> days = (p.getOffDays() == null || p.getOffDays().isBlank())
                ? List.of()
                : Arrays.stream(p.getOffDays().split(",")).map(String::trim).toList();
        return new WeeklyOffPolicyResponse(p.getId(), p.getName(), days, employeeCount, p.getCreatedAt());
    }
}
