package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String name;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 100)
    private String country;

    @Column(name = "holiday_region", length = 100)
    private String holidayRegion;

    /**
     * IANA timezone id (e.g. "Asia/Kolkata", "America/New_York") for employees assigned to this
     * location — the sole source of an employee's effective attendance timezone (see
     * AttendanceRulesService#resolveEmployeeZoneId; Employee itself carries no timezone field of
     * its own — see Employee's own class Javadoc). Name/City/State/Country are freely editable
     * (any number of Locations can be created), but this field must always be exactly one of a
     * fixed, small set of supported zones — see {@link com.nforce.onehr.service.OrgService
     * #SUPPORTED_TIMEZONES}, which OrgService#createLocation/updateLocation validate against
     * (never accepting an arbitrary client-supplied zone) — and DB-enforced NOT NULL +
     * CHECK-constrained to that same set as of V169/V170, so an unsupported or missing Location
     * timezone is no longer representable.
     */
    @Column(length = 50, nullable = false)
    private String timezone;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
