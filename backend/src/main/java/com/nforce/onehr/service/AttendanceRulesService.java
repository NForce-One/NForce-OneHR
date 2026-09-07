package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.AttendanceRulesResponse;
import com.nforce.onehr.dto.org.UpdateAttendanceRulesRequest;
import com.nforce.onehr.dto.org.UpdateDefaultTimezoneRequest;
import com.nforce.onehr.entity.AttendanceRules;
import com.nforce.onehr.repository.AttendanceRulesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Organization-level attendance classification rules — P1 (re-scoped Workstream B) holds exactly
 * one setting, {@code halfDayMaxHours}, on the singleton row {@link AttendanceRules} (see its own
 * Javadoc/migration for why it lives here rather than on {@code AttendanceProperties}). This is
 * deliberately the smallest possible model for the one YAML property that genuinely needed a
 * persisted, admin-editable home — see scratch/workstream-b-settings-investigation.md for why
 * every other {@code app.attendance.*} property was removed or left alone instead of joining this
 * table.
 */
@Service
@RequiredArgsConstructor
public class AttendanceRulesService {

    // (0, 24) exclusive both ends — see V162's migration comment: a half-day threshold must be a
    // positive fraction of a calendar day, strictly less than a full day. Mirrored by V162's own
    // DB CHECK constraint (defense-in-depth) and by UpdateAttendanceRulesRequest's bean validation
    // (so a bad value is rejected with a normal 400 before reaching this layer at all).
    private static final BigDecimal MIN_HOURS_EXCLUSIVE = BigDecimal.ZERO;
    private static final BigDecimal MAX_HOURS_EXCLUSIVE = BigDecimal.valueOf(24);

    private final AttendanceRulesRepository repository;

    @Transactional(readOnly = true)
    public AttendanceRulesResponse getRules() {
        return AttendanceRulesResponse.from(loadSingleton());
    }

    /** The one value AttendanceService/WebClockInService/RegularizationService need — a plain double. */
    @Transactional(readOnly = true)
    public double getHalfDayMaxHours() {
        return loadSingleton().getHalfDayMaxHours().doubleValue();
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public AttendanceRulesResponse updateHalfDayMaxHours(UpdateAttendanceRulesRequest req) {
        BigDecimal hours = req.getHalfDayMaxHours();
        if (hours == null || hours.compareTo(MIN_HOURS_EXCLUSIVE) <= 0 || hours.compareTo(MAX_HOURS_EXCLUSIVE) >= 0) {
            throw new IllegalArgumentException("Half Day Max Hours must be greater than 0 and less than 24 hours");
        }
        AttendanceRules rules = loadSingleton();
        rules.setHalfDayMaxHours(hours);
        return AttendanceRulesResponse.from(repository.save(rules));
    }

    /** The org-wide fallback zone — plain value for AttendanceService/WebClockInService's resolveZone chain. */
    @Transactional(readOnly = true)
    public ZoneId getDefaultZoneId() {
        return ZoneId.of(loadSingleton().getDefaultTimezone());
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public AttendanceRulesResponse updateDefaultTimezone(UpdateDefaultTimezoneRequest req) {
        String zoneId = req.getDefaultTimezone() != null ? req.getDefaultTimezone().trim() : null;
        try {
            ZoneId.of(zoneId);
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "'" + zoneId + "' is not a valid IANA timezone id (e.g. Asia/Kolkata, America/New_York)");
        }
        AttendanceRules rules = loadSingleton();
        rules.setDefaultTimezone(zoneId);
        return AttendanceRulesResponse.from(repository.save(rules));
    }

    private AttendanceRules loadSingleton() {
        return repository.findBySingletonTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "Attendance Rules row is missing — expected exactly one row seeded by migration V162"));
    }
}
