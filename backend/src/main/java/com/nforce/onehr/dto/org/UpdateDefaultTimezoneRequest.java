package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateDefaultTimezoneRequest {

    // Further validated in AttendanceRulesService against ZoneId.of(...) — a real IANA zone id
    // check, which a regex/@Pattern can't express — so a bad value is rejected with a clear
    // message rather than silently accepted and failing later at attendance-computation time.
    @NotBlank(message = "Default timezone is required")
    private String defaultTimezone;
}
