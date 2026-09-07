package com.nforce.onehr.service;

import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.ShiftVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * The one centralized place every timing consumer resolves "what was this Shift's configuration
 * on date D" — see {@link ShiftVersion}'s own Javadoc for why timing lives there and not on
 * {@link Shift} itself. Nothing outside this class (and {@code OrgService}'s own CRUD, which
 * creates/replaces version rows but never resolves them for a business decision) should read a
 * {@link ShiftVersion}'s fields directly for an Attendance-relevant computation — always go
 * through {@link #resolve}, keyed by the specific date in question (typically {@code
 * Attendance.workDate}), never by "now".
 */
@Service
@RequiredArgsConstructor
public class ShiftVersionResolver {

    private final ShiftVersionRepository shiftVersionRepository;

    /**
     * The version governing {@code workDate}: the latest version whose {@code effectiveFrom} is
     * on or before it. Every {@link Shift} is expected to have at least one version at all times
     * (created alongside the Shift itself — see {@code OrgService#createShift} — or migrated in
     * as Version 1 for any pre-existing row), so this only throws if that invariant has somehow
     * been violated.
     */
    public ShiftVersion resolve(Shift shift, LocalDate workDate) {
        return shiftVersionRepository
                .findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(shift.getId(), workDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Shift '" + shift.getName() + "' (id=" + shift.getId() + ") has no version effective on "
                                + "or before " + workDate + " — every Shift is expected to have at least an initial "
                                + "version (see OrgService#createShift and the V159 migration backfill)."));
    }

    /** Convenience for a live/current-state display (e.g. the Shifts tab list) — equivalent to {@code resolve(shift, LocalDate.now())}. */
    public ShiftVersion resolveCurrent(Shift shift) {
        return resolve(shift, LocalDate.now());
    }
}
