package com.nforce.onehr.dto.attendance;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateWebClockInRequest {

    // Mandatory on the first Web Clock-In of an employee's resolved work day, optional on every
    // later cycle that same day — enforced in WebClockInService#submit (it depends on that day's
    // prior rows, which no static bean-validation annotation can see), not here.
    private String reason;

    // See PunchTimezoneRequest's Javadoc — same optional browser-timezone hint, just carried
    // alongside the reason since this request already has a body.
    private String timezone;
}
