package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateWeeklyOffPolicyRequest {

    @NotBlank(message = "Weekly off policy name is required")
    @Size(max = 100)
    private String name;

    /** java.time.DayOfWeek names, e.g. ["SATURDAY", "SUNDAY"] — full-day only for P1. */
    @NotEmpty(message = "At least one off day is required")
    private List<String> offDays;
}
