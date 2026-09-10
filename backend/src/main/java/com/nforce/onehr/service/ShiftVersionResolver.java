package com.nforce.onehr.service;

import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.ShiftVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

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
        return resolveIfPresent(shift, workDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Shift '" + shift.getName() + "' (id=" + shift.getId() + ") has no version effective on "
                                + "or before " + workDate + " — every Shift is expected to have at least an initial "
                                + "version (see OrgService#createShift and the V159 migration backfill)."));
    }

    /**
     * Non-throwing counterpart to {@link #resolve} — empty when {@code workDate} predates the
     * Shift's own earliest version (i.e. the Shift did not exist yet as of that date), rather than
     * treating that as the invariant violation {@link #resolve} guards against. Exists specifically
     * for callers asking "did this Shift already exist as of date D" (e.g.
     * {@link ShiftDayPolicy}'s own-yesterday overnight-tail check) rather than "what governs
     * date D" — a brand-new Shift has no version before its creation date by design (see the V159
     * migration backfill's own comment), so that absence is expected there, not anomalous.
     */
    public Optional<ShiftVersion> resolveIfPresent(Shift shift, LocalDate workDate) {
        return shiftVersionRepository
                .findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(shift.getId(), workDate);
    }

    /** Convenience for a live/current-state display (e.g. the Shifts tab list) — equivalent to {@code resolve(shift, LocalDate.now())}. */
    public ShiftVersion resolveCurrent(Shift shift) {
        return resolve(shift, LocalDate.now());
    }
}
