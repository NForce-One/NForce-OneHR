package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebClockInResponse {

    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeEmail;
    private String departmentName;
    private LocalDate workDate;
    private LocalDateTime requestedCheckIn;
    private String reason;
    private LocalDateTime checkedOutAt;
    private LocalDateTime createdAt;
}
