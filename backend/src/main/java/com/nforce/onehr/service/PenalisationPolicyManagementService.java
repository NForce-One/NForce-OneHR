package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.ClonePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.CreatePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.PenalisationPolicySummaryDto;
import com.nforce.onehr.dto.penalization.RenamePenalisationPolicyRequest;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyLateHoursTier;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Organization Masters → Penalization Policy → Policy List (Section 5): CRUD for the
 * {@link PenalisationPolicy} label/assignment entity itself (name, description, status),
 * distinct from {@link PenalizationPolicyService} which owns each policy's rule-configuration
 * version chain. Named "Management" rather than reusing either existing name to avoid the
 * British/American-spelling ambiguity already flagged between {@code PenalisationPolicy} (V95,
 * assignment label) and {@code PenalizationPolicy*} (rule config).
 */
@Service
@RequiredArgsConstructor
public class PenalisationPolicyManagementService {

    private final PenalisationPolicyRepository penalisationPolicyRepository;
    private final PenalizationPolicyVersionRepository versionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;
    private final PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    private final PenalizationPolicyAllocationRepository allocationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties attendanceProperties;
    private final PenalizationPolicyResolutionService resolutionService;
    private final AttendancePenaltyRepository attendancePenaltyRepository;

    private LocalDate today() {
        return LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();
    }

    /**
     * Section 7/2: read-only — this is a deploy-time config value
     * ({@code app.attendance.penalization-fallback-strategy}), not a DB-backed runtime setting
     * (unlike {@code halfDayMaxHours}/{@code defaultTimezone} on {@code AttendanceRules}, or
     * {@code lateGraceMinutes} on {@code ShiftVersion} — all three since migrated off
     * {@link AttendanceProperties}). Exposed so the Allocation screen can explain why an
     * unassigned employee resolves to {@code DEFAULT} vs {@code ALLOCATION_REQUIRED}, without the
     * frontend re-deriving or hardcoding that logic itself.
     */
    public String getFallbackStrategy() {
        return attendanceProperties.getPenalizationFallbackStrategy().name();
    }

    /**
     * Every policy's "Employee Count" here comes from
     * {@link PenalizationPolicyResolutionService#resolveCurrentEmployeeCountsByPolicy} — the same
     * authoritative, allocation-aware resolution the Penalization Policy Allocation screen and the
     * attendance engine use — computed ONCE for the whole list (two bulk queries total) rather
     * than once per policy, so this scales with employee count, not policy count × employee count.
     * Likewise, every policy's "current version" (currentVersion/effectiveFrom columns) is fetched
     * in ONE bulk query up front — {@link #toSummary(PenalisationPolicy, long)} does its own
     * single-policy lookup for the single-object callers (create/rename/toggleActive/delete),
     * but calling that per policy here was a genuine N+1 (one findByPolicyIdAndEffectiveToIsNull
     * round trip per row) that dominated this list's load time.
     */
    @Transactional(readOnly = true)
    public List<PenalisationPolicySummaryDto> list() {
        List<PenalisationPolicy> policies = penalisationPolicyRepository.findAll();
        List<UUID> policyIds = policies.stream().map(PenalisationPolicy::getId).toList();
        Map<UUID, PenalizationPolicyVersion> currentVersionByPolicyId = policyIds.isEmpty() ? Map.of()
                : versionRepository.findByPolicyIdInAndEffectiveToIsNull(policyIds).stream()
                        .collect(Collectors.toMap(PenalizationPolicyVersion::getPolicyId, v -> v));
        // The org default policy id (oldest by createdAt — same rule as
        // PenalizationPolicyService#resolveDefaultPolicyId) is derived from the `policies` list
        // already in hand, instead of letting resolveCurrentEmployeeCountsByPolicy issue its own
        // separate lookup query for it.
        UUID defaultPolicyId = policies.stream()
                .min(Comparator.comparing(PenalisationPolicy::getCreatedAt))
                .map(PenalisationPolicy::getId).orElse(null);
        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today(), defaultPolicyId);
        return policies.stream()
                .map(p -> toSummary(p, counts.getOrDefault(p.getId(), 0L), currentVersionByPolicyId.get(p.getId())))
                .sorted(Comparator.comparing(PenalisationPolicySummaryDto::getCreatedAt))
                .toList();
    }

    @Transactional
    public PenalisationPolicySummaryDto create(CreatePenalisationPolicyRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);
        if (penalisationPolicyRepository.findByName(request.getName()).isPresent()) {
            throw new IllegalStateException("A policy named '" + request.getName() + "' already exists");
        }
        PenalisationPolicy policy = penalisationPolicyRepository.save(PenalisationPolicy.builder()
                .name(request.getName()).description(request.getDescription())
                .createdBy(actor.getId()).status("ACTIVE").build());
        auditService.log(actor.getId(), "PENALISATION_POLICY_CREATED", policy.getId());
        return toSummary(policy, 0L);
    }

    @Transactional
    public PenalisationPolicySummaryDto rename(UUID id, RenamePenalisationPolicyRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        penalisationPolicyRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalStateException("A policy named '" + request.getName() + "' already exists");
                });
        String before = auditSnapshot.toJson(Map.of("name", policy.getName()));
        policy.setName(request.getName());
        if (request.getDescription() != null) {
            policy.setDescription(request.getDescription());
        }
        penalisationPolicyRepository.save(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_RENAMED", policy.getId(),
                before, auditSnapshot.toJson(Map.of("name", policy.getName())));
        return toSummary(policy, resolutionService.resolveCurrentEmployeeCount(policy.getId(), today()));
    }

    /**
     * Copies the source policy's currently-effective rule configuration (all four sections, all
     * tier tables) into a brand-new, independently-versioned policy — immediately effective (not
     * "1st of next month": there is no prior version on the new policy to supersede, so
     * {@link PenalizationPolicyService}'s supersede-scheduling rule doesn't apply here).
     */
    @Transactional
    public PenalisationPolicySummaryDto clone(UUID sourceId, ClonePenalisationPolicyRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);
        findPolicy(sourceId); // 404s early if the source doesn't exist
        if (penalisationPolicyRepository.findByName(request.getName()).isPresent()) {
            throw new IllegalStateException("A policy named '" + request.getName() + "' already exists");
        }

        PenalisationPolicy clone = penalisationPolicyRepository.save(PenalisationPolicy.builder()
                .name(request.getName()).description(request.getDescription())
                .createdBy(actor.getId()).status("ACTIVE").build());

        versionRepository.findByPolicyIdAndEffectiveToIsNull(sourceId).ifPresent(source -> {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone()));
            PenalizationPolicyVersion copy = copyVersionFields(source, clone.getId(), now, actor.getId());
            copy = versionRepository.save(copy);
            for (PenalizationPolicyWorkHoursTier t : tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(source.getId())) {
                tierRepository.save(PenalizationPolicyWorkHoursTier.builder()
                        .policyVersionId(copy.getId()).thresholdPercent(t.getThresholdPercent())
                        .deductionDays(t.getDeductionDays()).sortOrder(t.getSortOrder()).build());
            }
            for (PenalizationPolicyLateHoursTier t : lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(source.getId())) {
                lateHoursTierRepository.save(PenalizationPolicyLateHoursTier.builder()
                        .policyVersionId(copy.getId()).thresholdHours(t.getThresholdHours())
                        .deductionDays(t.getDeductionDays()).sortOrder(t.getSortOrder()).build());
            }
        });

        auditService.log(actor.getId(), "PENALISATION_POLICY_CLONED", clone.getId(),
                auditSnapshot.toJson(Map.of("clonedFromPolicyId", sourceId.toString())), null);
        return toSummary(clone, 0L);
    }

    /**
     * Deactivating a policy removes it from the active allocation/assignment dropdowns (see
     * {@code PenalisationPolicySummaryDto#getStatus} and the frontend's active-policy filter) —
     * it does not touch any existing allocation, legacy FK assignment, or historical attendance
     * result. Reactivating simply flips it back. Employees currently resolving to a deactivated
     * policy keep resolving to it (deactivation is "don't let anyone else pick this" — retiring an
     * in-use policy without reassigning first is an explicit admin decision, not something this
     * toggle does on their behalf).
     */
    @Transactional
    public PenalisationPolicySummaryDto toggleActive(UUID id, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        // Section 7: deactivating the org default would silently leave every unassigned employee
        // with no policy at all the moment resolveActiveDefaultPolicyId next runs — require an
        // explicit new default first, the same "never leave the org without one" spirit as
        // delete()'s "cannot delete the only remaining policy" guard below.
        if ("ACTIVE".equals(policy.getStatus()) && policy.isOrgDefault()) {
            throw new IllegalStateException(
                    "This policy is the organization default. Set a different active policy as the default before deactivating this one.");
        }
        String before = auditSnapshot.toJson(Map.of("status", policy.getStatus()));
        policy.setStatus("ACTIVE".equals(policy.getStatus()) ? "INACTIVE" : "ACTIVE");
        penalisationPolicyRepository.save(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_STATUS_CHANGED", policy.getId(),
                before, auditSnapshot.toJson(Map.of("status", policy.getStatus())));
        return toSummary(policy, resolutionService.resolveCurrentEmployeeCount(id, today()));
    }

    /**
     * Section 7: makes the org-wide fallback an explicit admin action instead of an undocumented
     * "oldest ACTIVE policy" derivation. Only an ACTIVE policy may be set — "the default itself
     * must be ACTIVE" is a write-time guarantee here, not just a runtime filter in
     * {@link PenalizationPolicyService#resolveActiveDefaultPolicyId}.
     *
     * <p>Clears the previous default via an immediate bulk {@code UPDATE}
     * ({@link PenalisationPolicyRepository#clearOrgDefault}) rather than loading that row and
     * calling {@code save()} on it — Hibernate defers an entity save's actual UPDATE statement to
     * flush time, ordered by when each entity was LOADED into the persistence context, not by
     * statement call order. Since {@code policy} (the new default) is loaded before any previous
     * default row would be, a deferred flush could write this row's "true" before the old row's
     * "false", tripping {@code idx_penalisation_policies_one_org_default} even though the code
     * calls them in the right sequence. The bulk clear runs synchronously the moment it's called,
     * so the database has zero rows flagged default before {@code policy}'s own save is ever
     * flushed — the two writes can never coexist.
     */
    @Transactional
    public PenalisationPolicySummaryDto setOrgDefault(UUID id, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        if (!"ACTIVE".equals(policy.getStatus())) {
            throw new IllegalStateException("Only an active policy can be set as the organization default");
        }
        if (policy.isOrgDefault()) {
            return toSummary(policy, resolutionService.resolveCurrentEmployeeCount(id, today()));
        }
        penalisationPolicyRepository.clearOrgDefault();
        policy.setOrgDefault(true);
        penalisationPolicyRepository.save(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_SET_AS_DEFAULT", policy.getId());
        return toSummary(policy, resolutionService.resolveCurrentEmployeeCount(id, today()));
    }

    /**
     * Blocked while any employee is still assigned to this policy, blocked for the org's last
     * remaining policy — every employee must always have a policy to fall back to — blocked
     * while any historical {@link com.nforce.onehr.entity.AttendancePenalty} still references this
     * policy, and blocked whenever the policy has genuinely ever been live (Gap-036: more than one
     * version, or any version already effective). That last check exists because the first three
     * guards only catch a policy reached via an explicit allocation or one that actually produced a
     * penalty row — a policy that governed every employee for months purely through the org-default
     * fallback could have real rule history with zero rows in either table. {@code allocationCount}
     * above only protects against removing an *assignment*; it says nothing about penalties already
     * issued, and neither says anything about whether the policy's own version history is real.
     */
    @Transactional
    public void delete(UUID id, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        // Section 7: guards against the narrow edge case employeeCount alone wouldn't catch — every
        // employee happens to have an explicit allocation/legacy FK, so the default's own resolved
        // count reads zero even though it's still the org's configured fallback for anyone new.
        if (policy.isOrgDefault()) {
            throw new IllegalStateException(
                    "This policy is the organization default and cannot be deleted. Set a different policy as the default first.");
        }
        long employeeCount = resolutionService.resolveCurrentEmployeeCount(id, today());
        if (employeeCount > 0) {
            throw new IllegalStateException(employeeCount + " employee(s) are currently assigned to this policy. Reassign them first.");
        }
        long allocationCount = allocationRepository.countByPenalisationPolicyId(id);
        if (allocationCount > 0) {
            throw new IllegalStateException(allocationCount
                    + " Penalization Policy Allocation record(s) reference this policy. Remove or reassign them first.");
        }
        if (attendancePenaltyRepository.existsByPolicyId(id)) {
            throw new IllegalStateException(
                    "One or more attendance penalty records reference this policy's history and cannot be orphaned. "
                            + "This policy cannot be deleted.");
        }
        List<PenalizationPolicyVersion> versions = versionRepository.findByPolicyIdOrderByVersionDesc(id);
        // Gap-036: the three checks above only catch a policy that was reached via an explicit
        // allocation or that actually produced a persisted AttendancePenalty — an org that let
        // every employee resolve to this policy purely through the org-default fallback (see
        // PenalizationPolicyResolutionService#resolveDefaultPolicyIdOrNull) could have real,
        // months-old rule history here with zero rows in either of those tables. "More than one
        // version, or any version already effective" is the actual signal that this policy was
        // genuinely live, independent of whether anything else in the system happens to still
        // reference it — a policy this old must be deactivated, never hard-deleted.
        boolean everLive = versions.size() > 1
                || versions.stream().anyMatch(v -> !v.getEffectiveFrom().toLocalDate().isAfter(today()));
        if (everLive) {
            throw new IllegalStateException(
                    "This policy has real version history (it has been effective, or has more than one version) "
                            + "and cannot be permanently deleted. Deactivate it instead.");
        }
        if (penalisationPolicyRepository.count() <= 1) {
            throw new IllegalStateException("Cannot delete the organization's only remaining Penalization Policy.");
        }
        for (PenalizationPolicyVersion version : versions) {
            tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId()).forEach(tierRepository::delete);
            lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId()).forEach(lateHoursTierRepository::delete);
            versionRepository.delete(version);
        }
        penalisationPolicyRepository.delete(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_DELETED", id,
                auditSnapshot.toJson(Map.of("name", policy.getName())), null);
    }

    private PenalizationPolicyVersion copyVersionFields(PenalizationPolicyVersion source, UUID newPolicyId,
                                                         LocalDateTime effectiveFrom, UUID actorId) {
        return PenalizationPolicyVersion.builder()
                .policyId(newPolicyId).version(1).effectiveFrom(effectiveFrom)
                .noAttendanceEnabled(source.isNoAttendanceEnabled()).naDeductionDays(source.getNaDeductionDays())
                .naNoShowEnabled(source.isNaNoShowEnabled()).naNoShowThresholdHours(source.getNaNoShowThresholdHours())
                .naAdjoiningHolidayEnabled(source.isNaAdjoiningHolidayEnabled())
                .naAdjoiningHolidayCondition(source.getNaAdjoiningHolidayCondition())
                .naAdjoiningHolidayCalendarDayThreshold(source.getNaAdjoiningHolidayCalendarDayThreshold())
                .naAdjoiningHolidayIgnoreHalfDayLeave(source.isNaAdjoiningHolidayIgnoreHalfDayLeave())
                .naAdjoiningWeekoffEnabled(source.isNaAdjoiningWeekoffEnabled())
                .naAdjoiningWeekoffCondition(source.getNaAdjoiningWeekoffCondition())
                .naAdjoiningWeekoffCalendarDayThreshold(source.getNaAdjoiningWeekoffCalendarDayThreshold())
                .naAdjoiningWeekoffIgnoreHalfDayLeave(source.isNaAdjoiningWeekoffIgnoreHalfDayLeave())
                .lateArrivalEnabled(source.isLateArrivalEnabled()).laBasis(source.getLaBasis())
                .laGracePeriodMinutes(source.getLaGracePeriodMinutes()).laExemptCount(source.getLaExemptCount())
                .laExemptPeriod(source.getLaExemptPeriod()).laDeductionDays(source.getLaDeductionDays())
                .laDeductionPerShifts(source.getLaDeductionPerShifts())
                .laIgnoreWhenEffectiveHoursMetEnabled(source.isLaIgnoreWhenEffectiveHoursMetEnabled())
                .laAllowedHours(source.getLaAllowedHours()).laCombinedRuleBehavior(source.getLaCombinedRuleBehavior())
                .laPenaliseWhenCausedByMissingLogEnabled(source.isLaPenaliseWhenCausedByMissingLogEnabled())
                .workHoursShortageEnabled(source.isWorkHoursShortageEnabled())
                .whsDeductionBasis(source.getWhsDeductionBasis()).whsDeductionPeriod(source.getWhsDeductionPeriod())
                .whsApplyPenaltyForShortageEnabled(source.isWhsApplyPenaltyForShortageEnabled())
                .whsApplyPenaltyForLateArrivalEnabled(source.isWhsApplyPenaltyForLateArrivalEnabled())
                .whsExcludeHoursOutsideShiftEnabled(source.isWhsExcludeHoursOutsideShiftEnabled())
                .whsPenalizeShortageCausedByMissingLogsEnabled(source.isWhsPenalizeShortageCausedByMissingLogsEnabled())
                .missingLogsEnabled(source.isMissingLogsEnabled()).mlExemptDays(source.getMlExemptDays())
                .mlExemptPeriod(source.getMlExemptPeriod()).mlDeductionMode(source.getMlDeductionMode())
                .mlDeductionDays(source.getMlDeductionDays()).mlDeductionPerShifts(source.getMlDeductionPerShifts())
                .mlIgnoreRuleEnabled(source.isMlIgnoreRuleEnabled())
                .mlIgnoreRuleThresholdPercent(source.getMlIgnoreRuleThresholdPercent())
                .deductionMethod(source.getDeductionMethod()).leavePriorityOrder(source.getLeavePriorityOrder())
                .bufferPeriodDays(source.getBufferPeriodDays())
                .noticePeriodForcesLopEnabled(source.isNoticePeriodForcesLopEnabled())
                .createdBy(actorId)
                .build();
    }

    // Single-policy lookup for the single-object callers (create/rename/toggleActive/delete) —
    // one query is correct here since each only handles one policy per call.
    private PenalisationPolicySummaryDto toSummary(PenalisationPolicy policy, long employeeCount) {
        return toSummary(policy, employeeCount, versionRepository.findByPolicyIdAndEffectiveToIsNull(policy.getId()).orElse(null));
    }

    // Bulk-list variant: the current version is passed in, already resolved in one batched query
    // by the caller (see #list) — this overload itself never queries.
    private PenalisationPolicySummaryDto toSummary(PenalisationPolicy policy, long employeeCount, PenalizationPolicyVersion current) {
        return PenalisationPolicySummaryDto.builder()
                .id(policy.getId()).name(policy.getName()).description(policy.getDescription())
                .status(policy.getStatus()).employeeCount(employeeCount).orgDefault(policy.isOrgDefault())
                .currentVersion(current != null ? current.getVersion() : null)
                .effectiveFrom(current != null ? current.getEffectiveFrom() : null)
                .createdBy(policy.getCreatedBy()).createdAt(policy.getCreatedAt())
                .build();
    }

    private PenalisationPolicy findPolicy(UUID id) {
        return penalisationPolicyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Penalisation Policy not found"));
    }

    private User resolveActor(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
