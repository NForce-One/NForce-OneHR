package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateWeeklyOffPolicyRequest {

    @NotBlank(message = "Weekly off policy name is required")
    @Size(max = 100)
    private String name;

    @NotEmpty(message = "At least one off day is required")
    private List<String> offDays;
}
