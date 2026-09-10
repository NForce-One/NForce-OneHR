package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Location;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class LocationResponse {
    UUID id;
    String name;
    String city;
    String state;
    String country;
    String holidayRegion;
    String timezone;
    // Null when no timezone change is queued. Present only when a future change is pending — see
    // Location.pendingTimezone/pendingTimezoneEffectiveFrom's own Javadoc.
    String pendingTimezone;
    LocalDate pendingTimezoneEffectiveFrom;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static LocationResponse from(Location l, long employeeCount) {
        return new LocationResponse(l.getId(), l.getName(), l.getCity(), l.getState(), l.getCountry(),
                l.getHolidayRegion(), l.getTimezone(), l.getPendingTimezone(), l.getPendingTimezoneEffectiveFrom(),
                l.isActive(), employeeCount, l.getCreatedAt(), l.getUpdatedAt());
    }
}
