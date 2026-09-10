package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.ManagerDashboardDto;
import com.nforce.onehr.dto.UpdateEmployeeRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final LocationRepository locationRepository;
    private final ShiftRepository shiftRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final EmailService emailService;
    private final LeaveService leaveService;
    private final EmployeeCodeGenerator employeeCodeGenerator;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * HR Admin + Super Admin. Role is ALWAYS forced to EMPLOYEE regardless of caller.
     */
    @Transactional
    public EmployeeResponse createEmployee(CreateEmployeeRequest req, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        if (userRepository.existsByEmailAndDeletedAtIsNull(req.getEmail().toLowerCase().trim())) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        String tempPassword = generateTempPassword();
        Role employeeRole = roleRepository.findByCode("EMPLOYEE")
                .orElseThrow(() -> new IllegalStateException("EMPLOYEE role not found"));

        User newUser = User.builder()
                .email(req.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .mustChangePassword(true)
                .active(true)
                .roles(new HashSet<>(Set.of(employeeRole)))
                .build();
        newUser = userRepository.save(newUser);

        String code = employeeCodeGenerator.claim(req.getEmployeeCode());

        Employee emp = Employee.builder()
                .user(newUser)
                .employeeCode(code)
                .fullName(req.getFullName().trim())
                .employmentType(req.getEmploymentType() != null ? req.getEmploymentType() : "FULL_TIME")
                .workMode(req.getWorkMode() != null ? req.getWorkMode() : "ONSITE")
                .joiningDate(req.getJoiningDate())
                .createdBy(actor.getId())
                .build();

        if (req.getBusinessUnitId() != null)
            emp.setBusinessUnit(businessUnitRepository.findById(req.getBusinessUnitId()).orElse(null));
        // A brand-new employee has no legitimate pre-existing assignment to preserve, so this is
        // unconditional (unlike updateEmployee's version below, which only rejects an actual
        // change to a currently-inactive record) — mirrors UserManagementService.createUser's
        // identical Department/Designation/Location/Shift checks.
        if (req.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(req.getDepartmentId()).orElse(null);
            if (dept != null && !dept.isActive())
                throw new IllegalArgumentException("This department is inactive and cannot be assigned. Choose an active department.");
            emp.setDepartment(dept);
        }
        if (req.getDesignationId() != null) {
            Designation desig = designationRepository.findById(req.getDesignationId()).orElse(null);
            if (desig != null && !desig.isActive())
                throw new IllegalArgumentException("This designation is inactive and cannot be assigned. Choose an active designation.");
            emp.setDesignation(desig);
        }
        if (req.getLocationId() != null) {
            Location loc = locationRepository.findById(req.getLocationId())
                    .orElseThrow(() -> new IllegalArgumentException("Selected location was not found."));
            validateAssignableLocation(loc);
            emp.setLocation(loc);
        }
        // CreateEmployeeRequest has no shiftId field to pick a specific shift, so this always
        // defaults to the organization's default shift, resolved server-side — same default,
        // same stable-name lookup, same live (never cached) read as
        // UserManagementService#createUser's identical default. Every employee is a hard invariant
        // to always have an assigned shift (see ShiftDayPolicy, which throws for a null-shift
        // employee everywhere), so a missing/deactivated default shift now fails account creation
        // loudly rather than silently leaving the employee shift-less. OrgService also refuses to
        // rename/deactivate/delete the default shift itself, so this should be unreachable in
        // practice — it still must fail clearly if it ever isn't.
        Shift defaultShift = shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)
                .filter(Shift::isActive)
                .orElseThrow(() -> new IllegalStateException(
                        "The organization's default shift ('" + Shift.DEFAULT_SHIFT_NAME + "') is missing or "
                                + "inactive — an employee cannot be created without a Shift. Contact an administrator."));
        emp.setShift(defaultShift);

        emp = employeeRepository.save(emp);
        leaveService.initializeDefaultBalances(newUser.getId());

        if (req.getManagerId() != null) {
            EmployeeManagerHistory history = EmployeeManagerHistory.builder()
                    .employeeUserId(newUser.getId())
                    .managerUserId(req.getManagerId())
                    .changedBy(actor.getId())
                    .build();
            historyRepository.save(history);
        }

        auditService.log(actor.getId(), "EMPLOYEE_CREATED", newUser.getId());
        emailService.sendInviteEmail(newUser.getEmail(), req.getFullName().trim(), tempPassword);
        return toResponse(emp, findCurrentManager(newUser.getId()), newUser, tempPassword);
    }

    /**
     * Read-only preview of the Employee ID the Add Employee/User form should display. Does not
     * consume the underlying sequence — see {@link EmployeeCodeGenerator#preview()}.
     */
    @Transactional(readOnly = true)
    public String previewNextEmployeeCode() {
        return employeeCodeGenerator.preview();
    }

    /**
     * HR Admin + Super Admin. Returns all employees (role=EMPLOYEE users).
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listEmployees() {
        List<Employee> emps = employeeRepository.findAllWithDetails();
        List<Employee> staff = emps.stream()
                .filter(e -> e.getUser().getRoles().stream().anyMatch(r -> r.getCode().equals("EMPLOYEE")))
                .toList();
        Map<UUID, EmployeeResponse.ManagerRef> managersByEmployeeId =
                findCurrentManagersBulk(staff.stream().map(Employee::getUserId).toList());
        return staff.stream()
                .map(e -> toResponse(e, managersByEmployeeId.get(e.getUserId()), e.getUser(), null))
                .collect(Collectors.toList());
    }

    /**
     * HR Admin + Super Admin. Updates only dept/designation/location/employmentType/fullName.
     * Manager and role changes are deliberately not handled here — use UserManagementService.
     */
    @Transactional
    public EmployeeResponse updateEmployee(UUID userId, UpdateEmployeeRequest req, String actorEmail) {
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        // Department/designation/employment type imply active employment — for a deactivated
        // employee these are blocked behind an explicit confirmation (name, location and other
        // offboarding-correction fields stay editable unconditionally). The server is the real
        // boundary here, not just the edit form's disabled inputs.
        if (!emp.getUser().isActive() && !req.isConfirmInactiveEdit() && changesGatedEmployeeFields(emp, req)) {
            throw new IllegalArgumentException(
                    "This employee is inactive. Confirm the change to update Department, Designation, or Employment Type for an inactive employee.");
        }

        String before = auditSnapshot.toJson(employeeSnapshot(emp));

        if (req.getFullName() != null && !req.getFullName().isBlank())
            emp.setFullName(req.getFullName().trim());
        if (req.getEmploymentType() != null && !req.getEmploymentType().isBlank())
            emp.setEmploymentType(req.getEmploymentType());
        if (req.getWorkMode() != null && !req.getWorkMode().isBlank())
            emp.setWorkMode(req.getWorkMode());
        if (req.getBusinessUnitId() != null)
            emp.setBusinessUnit(businessUnitRepository.findById(req.getBusinessUnitId()).orElse(null));
        // Only a genuine CHANGE of department/designation/location is checked against the
        // active/inactive rule — an employee already sitting on a since-deactivated master row
        // (active when originally assigned) must stay saveable for unrelated edits (name,
        // employment type, a different field entirely) without being forced off it. Resubmitting
        // the SAME id already on the employee is therefore never rejected, no matter its current
        // active state; only a request that actually moves onto a *different* inactive record is
        // — mirrors UserManagementService.updateUser's identical Department/Designation/Location/
        // Shift checks.
        if (req.getDepartmentId() != null) {
            Department newDepartment = departmentRepository.findById(req.getDepartmentId()).orElse(null);
            UUID currentDepartmentId = emp.getDepartment() != null ? emp.getDepartment().getId() : null;
            UUID newDepartmentId = newDepartment != null ? newDepartment.getId() : null;
            if (!Objects.equals(currentDepartmentId, newDepartmentId)) {
                if (newDepartment != null && !newDepartment.isActive())
                    throw new IllegalArgumentException("This department is inactive and cannot be assigned. Choose an active department.");
                emp.setDepartment(newDepartment);
            }
        }
        if (req.getDesignationId() != null) {
            Designation newDesignation = designationRepository.findById(req.getDesignationId()).orElse(null);
            UUID currentDesignationId = emp.getDesignation() != null ? emp.getDesignation().getId() : null;
            UUID newDesignationId = newDesignation != null ? newDesignation.getId() : null;
            if (!Objects.equals(currentDesignationId, newDesignationId)) {
                if (newDesignation != null && !newDesignation.isActive())
                    throw new IllegalArgumentException("This designation is inactive and cannot be assigned. Choose an active designation.");
                emp.setDesignation(newDesignation);
            }
        }
        if (req.getLocationId() != null) {
            // TEMPORARY (ONEHR-336 follow-up): see UserManagementService#updateUser's identical
            // guard — Location reassignment via Employee update is disabled for now, pending a
            // proper reassignment flow that correctly effective-dates attendance-relevant history
            // instead of silently changing an employee's current config out from under in-flight/
            // historical Attendance. Only a genuine CHANGE is rejected — resubmitting the SAME
            // location already on the employee is unaffected. Employee CREATION (createEmployee
            // above) is unaffected.
            UUID currentLocationId = emp.getLocation() != null ? emp.getLocation().getId() : null;
            if (!Objects.equals(currentLocationId, req.getLocationId())) {
                throw new IllegalArgumentException(
                        "Location changes are currently unavailable when updating an employee. Contact an administrator.");
            }
        }

        emp = employeeRepository.save(emp);
        String after = auditSnapshot.toJson(employeeSnapshot(emp));
        auditService.log(actor.getId(), "EMPLOYEE_UPDATED", userId, before, after);
        return toResponse(emp, findCurrentManager(userId), emp.getUser(), null);
    }

    /** True if the request would actually change one of the fields gated behind confirmInactiveEdit. */
    private boolean changesGatedEmployeeFields(Employee emp, UpdateEmployeeRequest req) {
        UUID currentDepartmentId = emp.getDepartment() != null ? emp.getDepartment().getId() : null;
        UUID currentDesignationId = emp.getDesignation() != null ? emp.getDesignation().getId() : null;
        return (req.getDepartmentId() != null && !Objects.equals(req.getDepartmentId(), currentDepartmentId))
                || (req.getDesignationId() != null && !Objects.equals(req.getDesignationId(), currentDesignationId))
                || (req.getEmploymentType() != null && !req.getEmploymentType().isBlank()
                        && !Objects.equals(emp.getEmploymentType(), req.getEmploymentType()));
    }

    /**
     * Department/designation/location are captured by name/title, not id — the audit detail
     * popup shows these snapshots verbatim, and a raw UUID means nothing to a reader. Naming it
     * at the time of the edit (rather than resolving the id at read time) also means the audit
     * trail keeps showing what it actually was even if that department/designation/location is
     * later renamed or deleted.
     */
    private Map<String, Object> employeeSnapshot(Employee emp) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fullName", emp.getFullName());
        snapshot.put("employmentType", emp.getEmploymentType());
        snapshot.put("workMode", emp.getWorkMode());
        snapshot.put("businessUnit", emp.getBusinessUnit() != null ? emp.getBusinessUnit().getName() : null);
        snapshot.put("department", emp.getDepartment() != null ? emp.getDepartment().getName() : null);
        snapshot.put("designation", emp.getDesignation() != null ? emp.getDesignation().getTitle() : null);
        snapshot.put("location", emp.getLocation() != null ? emp.getLocation().getName() : null);
        return snapshot;
    }

    /**
     * Rejects an inactive Location, or one with no valid IANA timezone, before it's assigned to
     * an employee — Location is the ONLY source of an employee's effective attendance timezone
     * (see Employee's own class Javadoc / AttendanceRulesService#resolveEmployeeZoneId), so a
     * Location that can't resolve to a real zone must never be assignable in the first place.
     * Should be unreachable for a location created through OrgService (its create/update always
     * validates the timezone against a fixed supported set — see OrgService#SUPPORTED_TIMEZONES),
     * but this is the hard backstop this feature explicitly requires.
     */
    private void validateAssignableLocation(Location location) {
        if (!location.isActive()) {
            throw new IllegalArgumentException("This location is inactive and cannot be assigned. Choose an active location.");
        }
        String timezone = location.getTimezone();
        boolean validTimezone = timezone != null && !timezone.isBlank();
        if (validTimezone) {
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException e) {
                validTimezone = false;
            }
        }
        if (!validTimezone) {
            throw new IllegalArgumentException(
                    "This location has no valid timezone configured and cannot be assigned. Contact an administrator.");
        }
    }

    /**
     * Returns users eligible to be assigned as managers (Manager, HR Admin, Super Admin roles).
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listPotentialManagers() {
        // Deactivated (active=false, deletedAt still null — "deactivate, never delete" per
        // User's own column comment) is deliberately NOT excluded here: an employee who already
        // reports to a since-deactivated manager needs that manager to still appear (the frontend
        // renders it disabled/non-selectable via the `active` flag on this response) so the
        // existing assignment stays visible and explicable instead of silently vanishing from the
        // list. Only a genuinely deleted user (deletedAt set — UserManagementService's delete path
        // always sets both deletedAt and active=false together) is excluded outright, since that
        // account no longer exists as a real reporting-line candidate at all.
        List<User> eligible = userRepository.findAllWithRoles().stream()
                .filter(u -> u.getDeletedAt() == null && u.getRoles().stream()
                        .anyMatch(r -> Set.of("MANAGER", "HR_ADMIN", "SUPER_ADMIN").contains(r.getCode())))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        // One batch lookup for full names instead of an employeeRepository.findById per user.
        Set<UUID> ids = eligible.stream().map(User::getId).collect(Collectors.toSet());
        Map<UUID, String> namesByUserId = employeeRepository.findNamesByUserIds(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
        // ONEHR bug report: a User row with an eligible role and no Employee row (e.g. a signup
        // that never completed onboarding, or a manually-created auth-only test account) used to
        // fall back to showing up here under its raw email — appearing as a selectable Reporting
        // Manager even though it doesn't exist anywhere else in the app (not in
        // UserManagementService.listUsers, which is driven by employeeRepository.findAllWithDetails,
        // not the users table — so these accounts were literally invisible everywhere except this
        // one dropdown, with no way to find or deactivate them through normal UI). A user with no
        // real Employee profile isn't a legitimate reporting-line candidate, so exclude anyone not
        // present in namesByUserId (i.e. without an actual Employee row) instead of falling back to
        // their email.
        return eligible.stream()
                .filter(u -> namesByUserId.containsKey(u.getId()))
                .map(u -> EmployeeResponse.builder()
                        .userId(u.getId())
                        .email(u.getEmail())
                        .fullName(namesByUserId.getOrDefault(u.getId(), u.getEmail()))
                        .role(RoleUtils.primaryRoleCode(u.getRoles(), ""))
                        .active(u.isActive())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * All authenticated users — work-info only, no personal fields.
     */
    @Transactional(readOnly = true)
    public List<DirectoryEntryDto> listDirectory() {
        List<Employee> emps = employeeRepository.findAllWithDetails();
        Map<UUID, EmployeeResponse.ManagerRef> managersByEmployeeId =
                findCurrentManagersBulk(emps.stream().map(Employee::getUserId).toList());
        return emps.stream()
                .map(e -> {
                    var mgr = managersByEmployeeId.get(e.getUserId());
                    return DirectoryEntryDto.builder()
                            .userId(e.getUserId().toString())
                            .employeeCode(e.getEmployeeCode())
                            .fullName(e.getFullName())
                            .email(e.getUser().getEmail())
                            .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                            .designationName(e.getDesignation() != null ? e.getDesignation().getTitle() : null)
                            .locationName(e.getLocation() != null ? e.getLocation().getName() : null)
                            .workMode(e.getWorkMode())
                            .employmentType(e.getEmploymentType())
                            .active(e.getUser().isActive())
                            .managerName(mgr != null ? mgr.getFullName() : null)
                            .managerEmail(mgr != null ? mgr.getEmail() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns direct reports for the given manager email.
     */
    @Transactional(readOnly = true)
    public ManagerDashboardDto getManagerDashboard(String managerEmail) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        List<EmployeeManagerHistory> directReportRels = historyRepository
                .findByManagerUserIdAndEffectiveToIsNull(manager.getId());

        // Trailing 12 calendar months (including the current one), matching the dashboard
        // chart's own bucketing window. Every history row in that window counts as its own
        // join event, even if that employee has since been reassigned away from this manager —
        // "who joined the team when" should survive a later reassignment/removal.
        LocalDateTime since = LocalDate.now().withDayOfMonth(1).minusMonths(11).atStartOfDay();
        List<EmployeeManagerHistory> teamJoinerRels = historyRepository
                .findByManagerUserIdAndEffectiveFromGreaterThanEqual(manager.getId(), since);

        // Both lists' employees are fetched once, in a single batch query (with their user/
        // designation/department associations already joined), instead of one findById
        // (plus per-association lazy loads) per row across the two loops below.
        Set<UUID> employeeIds = new LinkedHashSet<>();
        directReportRels.forEach(rel -> employeeIds.add(rel.getEmployeeUserId()));
        teamJoinerRels.forEach(rel -> employeeIds.add(rel.getEmployeeUserId()));
        Map<UUID, Employee> employeesById = employeeRepository
                .findAllByIdWithUserDetails(employeeIds)
                .stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));

        List<ManagerDashboardDto.DirectReport> reports = directReportRels
                .stream()
                .map(rel -> employeesById.get(rel.getEmployeeUserId()))
                .filter(Objects::nonNull)
                .map(emp -> ManagerDashboardDto.DirectReport.builder()
                        .userId(emp.getUserId().toString())
                        .employeeCode(emp.getEmployeeCode())
                        .fullName(emp.getFullName())
                        .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                        .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                        .active(emp.getUser().isActive())
                        .build())
                .collect(Collectors.toList());

        List<ManagerDashboardDto.TeamJoiner> teamJoiners = teamJoinerRels
                .stream()
                .map(rel -> {
                    Employee emp = employeesById.get(rel.getEmployeeUserId());
                    if (emp == null) {
                        return null;
                    }
                    return ManagerDashboardDto.TeamJoiner.builder()
                            .userId(emp.getUserId().toString())
                            .employeeCode(emp.getEmployeeCode())
                            .fullName(emp.getFullName())
                            .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                            .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                            .active(emp.getUser().isActive())
                            .joinedTeamOn(rel.getEffectiveFrom().toLocalDate().toString())
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return ManagerDashboardDto.builder()
                .directReportCount(reports.size())
                .directReports(reports)
                .teamJoiners(teamJoiners)
                .build();
    }

    /**
     * HR dashboard — organization-wide equivalent of {@link #getManagerDashboard}. "Team" for HR
     * means every user in the org (any role), so this reuses the same {@link ManagerDashboardDto}
     * shape but sources it from {@link #listDirectory} data instead of a manager's direct reports,
     * and keys the joiners chart off {@code Employee.joiningDate} (org joining date) rather than
     * an {@link EmployeeManagerHistory} team-join event — HR's "team" has no such event.
     */
    @Transactional(readOnly = true)
    public ManagerDashboardDto getOrgDashboard() {
        List<Employee> all = employeeRepository.findAllWithDetails();

        List<ManagerDashboardDto.DirectReport> reports = all.stream()
                .map(emp -> ManagerDashboardDto.DirectReport.builder()
                        .userId(emp.getUserId().toString())
                        .employeeCode(emp.getEmployeeCode())
                        .fullName(emp.getFullName())
                        .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                        .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                        .active(emp.getUser().isActive())
                        .roleCode(RoleUtils.primaryRoleCode(emp.getUser().getRoles(), "EMPLOYEE"))
                        .build())
                .collect(Collectors.toList());

        // Trailing 12 calendar months (including the current one) — same window as
        // getManagerDashboard's teamJoiners, just keyed off joiningDate instead of effectiveFrom.
        LocalDate since = LocalDate.now().withDayOfMonth(1).minusMonths(11);
        List<ManagerDashboardDto.TeamJoiner> orgJoiners = all.stream()
                .filter(emp -> emp.getJoiningDate() != null && !emp.getJoiningDate().isBefore(since))
                .map(emp -> ManagerDashboardDto.TeamJoiner.builder()
                        .userId(emp.getUserId().toString())
                        .employeeCode(emp.getEmployeeCode())
                        .fullName(emp.getFullName())
                        .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                        .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                        .active(emp.getUser().isActive())
                        .joinedTeamOn(emp.getJoiningDate().toString())
                        .build())
                .collect(Collectors.toList());

        return ManagerDashboardDto.builder()
                .directReportCount(reports.size())
                .directReports(reports)
                .teamJoiners(orgJoiners)
                .build();
    }

    /**
     * Directory entries for the caller's current Project Team — everyone (including the caller)
     * who presently shares the caller's manager. Interim "peer group" definition (see
     * {@link com.nforce.onehr.repository.EmployeeManagerHistoryRepository#findCurrentPeerIds});
     * shares the exact same {@link DirectoryEntryDto} shape as {@link #listDirectory()} so the
     * frontend can reuse the same card rendering for both.
     */
    @Transactional(readOnly = true)
    public List<DirectoryEntryDto> listPeers(String employeeEmail) {
        Employee self = employeeRepository.findByUser_Email(employeeEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "No employee profile found for this account. Contact HR to complete your profile."));

        // "Project Team" = every employee (including the caller) who currently reports to the
        // same manager — empty if the caller has no manager assigned, since there's no team to
        // belong to in that case.
        if (historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(self.getUserId()).isEmpty()) {
            return List.of();
        }
        // findCurrentPeerIds already includes the caller themself (see its own doc comment) — no
        // need to add self.getUserId() again here.
        List<UUID> teamIds = historyRepository.findCurrentPeerIds(self.getUserId());

        return employeeRepository.findAllById(teamIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .map(e -> {
                    var mgr = findCurrentManager(e.getUserId());
                    return DirectoryEntryDto.builder()
                            .userId(e.getUserId().toString())
                            .employeeCode(e.getEmployeeCode())
                            .fullName(e.getFullName())
                            .email(e.getUser().getEmail())
                            .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                            .designationName(e.getDesignation() != null ? e.getDesignation().getTitle() : null)
                            .locationName(e.getLocation() != null ? e.getLocation().getName() : null)
                            .workMode(e.getWorkMode())
                            .employmentType(e.getEmploymentType())
                            .active(e.getUser().isActive())
                            .managerName(mgr != null ? mgr.getFullName() : null)
                            .managerEmail(mgr != null ? mgr.getEmail() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /** The caller's own current reporting manager — backs the "Appreciate your lead" card (ONEHR-73). */
    @Transactional(readOnly = true)
    public EmployeeResponse.ManagerRef getMyManager(String employeeEmail) {
        Employee self = employeeRepository.findByUser_Email(employeeEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
        return findCurrentManager(self.getUserId());
    }

    /**
     * Raw photo bytes for any employee — deliberately open to every authenticated user (no
     * {@code @PreAuthorize} on the controller endpoint), same visibility as the directory/org
     * chart/team views that already show everyone's name, department and designation. Returns
     * null (→ 404) when the employee has no record or hasn't uploaded a photo, so callers fall
     * back to an initials avatar.
     */
    @Transactional(readOnly = true)
    public byte[] getPhoto(UUID userId) {
        return employeeRepository.findById(userId)
                .map(Employee::getProfilePhoto)
                .filter(photo -> photo != null && photo.length > 0)
                .orElse(null);
    }

    private EmployeeResponse.ManagerRef findCurrentManager(UUID employeeId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .flatMap(h -> userRepository.findById(h.getManagerUserId()))
                .map(mgr -> {
                    String name = employeeRepository.findById(mgr.getId())
                            .map(Employee::getFullName).orElse(mgr.getEmail());
                    return EmployeeResponse.ManagerRef.builder()
                            .userId(mgr.getId().toString())
                            .fullName(name)
                            .email(mgr.getEmail())
                            .build();
                })
                .orElse(null);
    }

    /**
     * Batch equivalent of {@link #findCurrentManager} for whole-org listings (listDirectory,
     * listEmployees, {@link UserManagementService#listUsers}, and the Penalization Policy
     * Allocation table — package-private specifically so those callers reuse this instead of
     * keeping a second copy of the same lookup) — calling findCurrentManager once per employee
     * used to mean 3 separate round trips each (history lookup, manager User lookup, manager
     * Employee lookup); for ~90 employees that's ~270 sequential queries against a remote DB,
     * easily a minute or more. This does the same lookup in exactly ONE query, regardless of
     * employee count, via {@link EmployeeManagerHistoryRepository#findCurrentManagerInfoByEmployeeIds}.
     */
    Map<UUID, EmployeeResponse.ManagerRef> findCurrentManagersBulk(Collection<UUID> employeeIds) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, EmployeeResponse.ManagerRef> result = new HashMap<>();
        for (Object[] row : historyRepository.findCurrentManagerInfoByEmployeeIds(employeeIds)) {
            UUID employeeId = (UUID) row[0];
            UUID managerId = (UUID) row[1];
            String managerEmail = (String) row[2];
            String managerFullName = (String) row[3];
            result.put(employeeId, EmployeeResponse.ManagerRef.builder()
                    .userId(managerId.toString())
                    .fullName(managerFullName != null ? managerFullName : managerEmail)
                    .email(managerEmail)
                    .build());
        }
        return result;
    }

    private EmployeeResponse toResponse(Employee emp, EmployeeResponse.ManagerRef manager, User user, String tempPassword) {
        String role = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");
        return EmployeeResponse.builder()
                .userId(emp.getUserId())
                .employeeCode(emp.getEmployeeCode())
                .fullName(emp.getFullName())
                .email(user.getEmail())
                .role(role)
                .businessUnitId(emp.getBusinessUnit() != null ? emp.getBusinessUnit().getId().toString() : null)
                .businessUnitName(emp.getBusinessUnit() != null ? emp.getBusinessUnit().getName() : null)
                .departmentId(emp.getDepartment() != null ? emp.getDepartment().getId().toString() : null)
                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .designationId(emp.getDesignation() != null ? emp.getDesignation().getId().toString() : null)
                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .locationId(emp.getLocation() != null ? emp.getLocation().getId().toString() : null)
                .locationName(emp.getLocation() != null ? emp.getLocation().getName() : null)
                .employmentType(emp.getEmploymentType())
                .workMode(emp.getWorkMode())
                .joiningDate(emp.getJoiningDate())
                .active(user.isActive())
                .currentManager(manager)
                .tempPassword(tempPassword)
                .build();
    }

    private String generateTempPassword() {
        int digits = 100000 + RANDOM.nextInt(900000);
        return "OneHR@" + digits;
    }
}
