package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.ShiftWeeklyOffRulesResponse;
import com.nforce.onehr.dto.org.UpdateShiftWeeklyOffRulesRequest;
import com.nforce.onehr.entity.ShiftWeeklyOffRules;
import com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Organization-level "Shifts & Weekly Off Rules" — P1 holds exactly one setting,
 * {@code maximumShiftDayDurationHours}, on the singleton row {@link ShiftWeeklyOffRules} (see its
 * own Javadoc/migration for why it lives here rather than on {@link com.nforce.onehr.entity.Shift}
 * or {@link com.nforce.onehr.config.AttendanceProperties}). The full "Shifts & Weekly Off Rules"
 * request-workflow module (shift-change/weekly-off request rate limits, etc.) is explicitly P2 —
 * this service and its one field are the entire P1 surface.
 */
@Service
@RequiredArgsConstructor
public class ShiftWeeklyOffRulesService {

    // (0, 24] — see V158's migration comment for why this specific bound: no existing domain rule
    // establishes it; it's the largest value for which ShiftDayPolicy's single-candidate-day
    // shift-day algorithm stays unambiguous (a value >24h would let more than one calendar day's
    // shift-start simultaneously fall within the elapsed window). Mirrored by V158's own DB CHECK
    // constraint (defense-in-depth) and by UpdateShiftWeeklyOffRulesRequest's bean validation
    // (so a bad value is rejected with a normal 400 before reaching this layer at all).
    private static final BigDecimal MIN_HOURS_EXCLUSIVE = BigDecimal.ZERO;
    private static final BigDecimal MAX_HOURS_INCLUSIVE = BigDecimal.valueOf(24);

    private final ShiftWeeklyOffRulesRepository repository;

    @Transactional(readOnly = true)
    public ShiftWeeklyOffRulesResponse getRules() {
        return ShiftWeeklyOffRulesResponse.from(loadSingleton());
    }

    /** The one value {@link ShiftDayPolicy} needs — a plain double, for its own internal arithmetic. */
    @Transactional(readOnly = true)
    public double getMaximumShiftDayDurationHours() {
        return loadSingleton().getMaximumShiftDayDurationHours().doubleValue();
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ShiftWeeklyOffRulesResponse updateMaximumShiftDayDurationHours(UpdateShiftWeeklyOffRulesRequest req) {
        BigDecimal hours = req.getMaximumShiftDayDurationHours();
        if (hours == null || hours.compareTo(MIN_HOURS_EXCLUSIVE) <= 0 || hours.compareTo(MAX_HOURS_INCLUSIVE) > 0) {
            throw new IllegalArgumentException("Maximum Shift Day Duration must be greater than 0 and at most 24 hours");
        }
        ShiftWeeklyOffRules rules = loadSingleton();
        rules.setMaximumShiftDayDurationHours(hours);
        return ShiftWeeklyOffRulesResponse.from(repository.save(rules));
    }

    private ShiftWeeklyOffRules loadSingleton() {
        return repository.findBySingletonTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "Shifts & Weekly Off Rules row is missing — expected exactly one row seeded by migration V158"));
    }
}
