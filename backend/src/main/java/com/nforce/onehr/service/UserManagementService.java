package com.nforce.onehr.service;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.ForceLogoutBroadcaster;
import com.nforce.onehr.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private static final Set<String> PHASE1_ROLES = Set.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");
    private static final SecureRandom RANDOM = new SecureRandom();

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
    private final NotificationService notificationService;
    private final LeaveService leaveService;
    private final ForceLogoutBroadcaster forceLogoutBroadcaster;
    private final EmployeeCodeGenerator employeeCodeGenerator;
    // Only for the package-private findCurrentManagersBulk bulk manager lookup used by
    // listUsers() below — reuses EmployeeService's existing bulk implementation instead of a
    // second copy of the same N+1-prone-if-done-per-row logic.
    private final EmployeeService employeeService;

    /** Super Admin: create a user with any Phase 1 role. */
    @Transactional
    public EmployeeResponse createUser(CreateUserRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        String roleCode = req.getRole().toUpperCase();
        if (!PHASE1_ROLES.contains(roleCode))
            throw new IllegalArgumentException("Role '" + roleCode + "' is not a valid Phase 1 role");
        if (userRepository.existsByEmailAndDeletedAtIsNull(req.getEmail().toLowerCase().trim()))
            throw new IllegalArgumentException("A user with this email already exists");

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));

        String tempPassword = generateTempPassword();
        User newUser = User.builder()
                .email(req.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .mustChangePassword(true)
                .active(true)
                .roles(rolesFor(role))
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
        if (req.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(req.getDepartmentId()).orElse(null);
            // A brand-new employee can never have a legitimate pre-existing assignment to
            // preserve, so this is unconditional — same reasoning as the Shift check below.
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
        if (req.getShiftId() != null) {
            // A bogus/stale shift id must never silently leave the employee shift-less — every
            // employee having a real assigned Shift is a hard invariant now, not a tolerated
            // absence (see ShiftDayPolicy, which throws for a null-shift employee everywhere).
            Shift shift = shiftRepository.findById(req.getShiftId())
                    .orElseThrow(() -> new IllegalArgumentException("Shift not found"));
            // A brand-new employee can never have a legitimate pre-existing assignment to
            // preserve, so this is unconditional (unlike updateUser's version below, which only
            // rejects an actual change to a currently-inactive shift).
            if (!shift.isActive())
                throw new IllegalArgumentException("This shift is inactive and cannot be assigned. Choose an active shift.");
            emp.setShift(shift);
        } else {
            // No shift explicitly chosen — default to the organization's default shift, resolved
            // server-side so an API-created employee can never accidentally end up with none (the
            // frontend also preselects this same shift, but this is the actual guarantee). Looked
            // up by its stable, seeded name — never a hardcoded id. Every employee is a hard
            // invariant to always have an assigned shift (see ShiftDayPolicy, which has no
            // fallback for a null one) — so unlike before, a missing/deactivated default shift now
            // fails account creation loudly rather than leaving the employee shift-less and
            // relying on ShiftSeedCorrector's next startup sweep to quietly fix it later.
            // OrgService also refuses to rename/deactivate/delete the default shift itself, so
            // this should be unreachable in practice — it still must fail clearly if it ever isn't.
            Shift defaultShift = defaultShift()
                    .filter(Shift::isActive)
                    .orElseThrow(() -> new IllegalStateException(
                            "The organization's default shift ('" + Shift.DEFAULT_SHIFT_NAME + "') is missing or "
                                    + "inactive — an employee cannot be created without a Shift. Contact an administrator."));
            emp.setShift(defaultShift);
        }

        emp = employeeRepository.save(emp);
        leaveService.initializeDefaultBalances(newUser.getId());

        if (req.getManagerId() != null) {
            validateNoCycle(newUser.getId(), req.getManagerId());
            historyRepository.save(EmployeeManagerHistory.builder()
                    .employeeUserId(newUser.getId())
                    .managerUserId(req.getManagerId())
                    .changedBy(actor.getId())
                    .build());
        }

        auditService.log(actor.getId(), "USER_CREATED", newUser.getId());
        emailService.sendInviteEmail(newUser.getEmail(), req.getFullName().trim(), tempPassword);
        notificationService.send(newUser.getId(), "ACCOUNT",
                "Welcome to OneHR",
                "Your account has been created. Please log in and change your temporary password.",
                "/profile");
        return toResponse(emp, findCurrentManager(newUser.getId()), newUser, tempPassword);
    }

    /**
     * Every Phase 1 role is staff first — everyone gets the base EMPLOYEE role alongside
     * whatever admin role they're assigned, so self-service features (attendance punch, leave,
     * etc.) work for them too, Super Admin included. (V111/V115 previously stripped this from
     * Super Admin and back; V116 restores it again.)
     */
    private Set<Role> rolesFor(Role assignedRole) {
        Set<Role> roles = new HashSet<>();
        roles.add(assignedRole);
        if (!"EMPLOYEE".equals(assignedRole.getCode())) {
            roleRepository.findByCode("EMPLOYEE").ifPresent(roles::add);
        }
        return roles;
    }

    /**
     * The organization's default shift ({@link Shift#DEFAULT_SHIFT_NAME}, "Default Shift",
     * 15:30-00:30 as seeded — but this always reads the shift's CURRENT admin-configured timing,
     * never a cached/duplicated copy: if an admin edits its timing through the Shift Management
     * UI, every employee defaulted to it from that point on picks up the new timing automatically,
     * since this is a live lookup of the same row, not a snapshot), looked up by its stable name
     * — never a hardcoded id, since ids differ per environment/seed run. Used to default a
     * newly-created employee's shift when the admin doesn't explicitly pick one — see
     * {@link #createUser}.
     */
    private Optional<Shift> defaultShift() {
        return shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME);
    }

    /**
     * Super Admin: list all users across all roles.
     *
     * Resolves every employee's current manager in one bulk lookup (see
     * {@link EmployeeService#findCurrentManagersBulk}) instead of calling
     * {@link #findCurrentManager} once per employee — that per-row version does up to 3 extra
     * queries each, which for the full org list turns into hundreds of sequential round trips.
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listUsers() {
        List<Employee> emps = employeeRepository.findAllWithDetails();
        Map<UUID, EmployeeResponse.ManagerRef> managersByEmployeeId =
                employeeService.findCurrentManagersBulk(emps.stream().map(Employee::getUserId).toList());
        return emps.stream()
                .map(e -> toResponse(e, managersByEmployeeId.get(e.getUserId()), e.getUser(), null))
                .collect(Collectors.toList());
    }

    /**
     * Super Admin: update all fields.
     * Manager change closes the current history row and inserts a new one — never overwrites.
     */
    @Transactional
    public EmployeeResponse updateUser(UUID userId, UpdateUserRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        User target = emp.getUser();
        String before = auditSnapshot.toJson(userSnapshot(emp, target));
        String currentRole = RoleUtils.primaryRoleCode(target.getRoles(), null);
        UUID currentManagerId = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(userId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
        boolean forceLogoutRequired = false;
        boolean roleChanged = false;
        boolean managerChanged = false;
        boolean emailChanged = false;
        String oldEmail = null;

        // Role/manager/department/designation/employment type imply active employment — for a
        // deactivated user these are blocked behind an explicit confirmation (name, location,
        // shift and other offboarding-correction fields stay editable unconditionally). The
        // server is the real boundary here, not just the edit form's disabled inputs.
        if (!target.isActive() && !req.isConfirmInactiveEdit()
                && changesGatedUserFields(emp, currentRole, currentManagerId, req)) {
            throw new IllegalArgumentException(
                    "This user is inactive. Confirm the change to update Role, Manager, Department, Designation, or Employment Type for an inactive user.");
        }

        if (req.getFullName() != null && !req.getFullName().isBlank()) {
            String fullName = req.getFullName().trim();
            if (!Objects.equals(emp.getFullName(), fullName)) {
                emp.setFullName(fullName);
                forceLogoutRequired = true;
            }
        }
        // Email — same uniqueness rule as createUser (excluding this user's own current row,
        // since re-saving the same value the employee already has must never trip "already
        // exists"). Normalized to lowercase+trim before storing/comparing, matching createUser.
        if (req.getEmail() != null && !req.getEmail().isBlank()) {
            String newEmail = req.getEmail().toLowerCase().trim();
            if (!Objects.equals(target.getEmail(), newEmail)) {
                if (userRepository.existsByEmailAndDeletedAtIsNull(newEmail))
                    throw new IllegalArgumentException("A user with this email already exists");
                oldEmail = target.getEmail();
                target.setEmail(newEmail);
                forceLogoutRequired = true;
                emailChanged = true;
            }
        }
        if (req.getEmploymentType() != null && !req.getEmploymentType().isBlank()
                && !Objects.equals(emp.getEmploymentType(), req.getEmploymentType())) {
            emp.setEmploymentType(req.getEmploymentType());
            forceLogoutRequired = true;
        }
        if (req.getWorkMode() != null && !req.getWorkMode().isBlank()
                && !Objects.equals(emp.getWorkMode(), req.getWorkMode())) {
            emp.setWorkMode(req.getWorkMode());
            forceLogoutRequired = true;
        }
        if (req.getBusinessUnitId() != null) {
            BusinessUnit newBusinessUnit = businessUnitRepository.findById(req.getBusinessUnitId()).orElse(null);
            UUID currentBusinessUnitId = emp.getBusinessUnit() != null ? emp.getBusinessUnit().getId() : null;
            UUID newBusinessUnitId = newBusinessUnit != null ? newBusinessUnit.getId() : null;
            if (!Objects.equals(currentBusinessUnitId, newBusinessUnitId)) {
                emp.setBusinessUnit(newBusinessUnit);
                forceLogoutRequired = true;
            }
        }
        if (req.getDepartmentId() != null) {
            Department newDepartment = departmentRepository.findById(req.getDepartmentId()).orElse(null);
            UUID currentDepartmentId = emp.getDepartment() != null ? emp.getDepartment().getId() : null;
            UUID newDepartmentId = newDepartment != null ? newDepartment.getId() : null;
            if (!Objects.equals(currentDepartmentId, newDepartmentId)) {
                // Only guarded on an actual change — re-saving an employee whose existing
                // assignment already points at a since-deactivated department (departmentId
                // unchanged) must keep working untouched, not get blocked by this check.
                if (newDepartment != null && !newDepartment.isActive())
                    throw new IllegalArgumentException("This department is inactive and cannot be assigned. Choose an active department.");
                emp.setDepartment(newDepartment);
                forceLogoutRequired = true;
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
                forceLogoutRequired = true;
            }
        }
        if (req.getLocationId() != null) {
            // TEMPORARY (ONEHR-336 follow-up): Location reassignment via Employee update is
            // disabled for now — pending a proper reassignment flow that correctly effective-dates
            // attendance-relevant history (see ShiftVersion's own versioning design, which Location
            // has no equivalent of yet) instead of silently changing an employee's current
            // config out from under in-flight/historical Attendance. Only a genuine CHANGE is
            // rejected — resubmitting the SAME location already on the employee (e.g. an edit form
            // that always sends the current value) is unaffected, exactly like the change-detection
            // every other field here uses. Employee CREATION (createUser above) is unaffected.
            UUID currentLocationId = emp.getLocation() != null ? emp.getLocation().getId() : null;
            if (!Objects.equals(currentLocationId, req.getLocationId())) {
                throw new IllegalArgumentException(
                        "Location changes are currently unavailable when updating an employee. Contact an administrator.");
            }
        }
        if (req.getShiftId() != null) {
            // TEMPORARY (ONEHR-336 follow-up): see the identical Location guard above — same
            // reasoning, applied to Shift reassignment. Employee CREATION (createUser above) is
            // unaffected.
            UUID currentShiftId = emp.getShift() != null ? emp.getShift().getId() : null;
            if (!Objects.equals(currentShiftId, req.getShiftId())) {
                throw new IllegalArgumentException(
                        "Shift changes are currently unavailable when updating an employee. Contact an administrator.");
            }
        }

        // Role change
        if (req.getRole() != null && !req.getRole().isBlank()) {
            String roleCode = req.getRole().toUpperCase();
            if (!PHASE1_ROLES.contains(roleCode))
                throw new IllegalArgumentException("Invalid role: " + roleCode);
            if (!Objects.equals(currentRole, roleCode)) {
                Role newRole = roleRepository.findByCode(roleCode)
                        .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));
                target.getRoles().clear();
                target.getRoles().addAll(rolesFor(newRole));
                forceLogoutRequired = true;
                roleChanged = true;
            }
        }

        // Manager change — effective-dating: close current, insert new
        if (req.getManagerId() != null) {
            if (!Objects.equals(currentManagerId, req.getManagerId())) {
                validateNoCycle(userId, req.getManagerId());
                historyRepository.closeCurrentEntry(userId, LocalDateTime.now());
                historyRepository.save(EmployeeManagerHistory.builder()
                        .employeeUserId(userId)
                        .managerUserId(req.getManagerId())
                        .changedBy(actor.getId())
                        .build());
                forceLogoutRequired = true;
                managerChanged = true;
            }
        }

        if (forceLogoutRequired) {
            // Invalidates every JWT already issued to this user (see JwtAuthenticationFilter) —
            // their very next API call fails auth under the old token even if their open tab
            // misses the SSE push.
            target.setTokenVersion(target.getTokenVersion() + 1);
            target.setTokenVersionReason("PROFILE_UPDATED");
            userRepository.save(target);
        }

        emp = employeeRepository.save(emp);

        if (roleChanged) {
            notificationService.send(target.getId(), "ACCOUNT",
                    "Role Updated",
                    "Your role has been updated to " + RoleUtils.primaryRoleCode(target.getRoles(), "").replace("_", " ") + ".",
                    "/profile");
        }
        if (managerChanged) {
            notificationService.send(target.getId(), "ACCOUNT",
                    "Manager Updated",
                    "Your manager has been updated.",
                    "/profile");
        }
        if (emailChanged) {
            // Sent to the NEW address — the whole point is confirming the user can actually
            // receive mail there and giving them the correct address to sign in with next time.
            // In-app notification is a soft-effort companion, not the primary channel: the token
            // bump above (forceLogoutRequired) already ends their session, so they won't see it
            // until they sign back in with the new email anyway.
            emailService.sendEmailUpdatedEmail(target.getEmail(), emp.getFullName(), oldEmail);
            notificationService.send(target.getId(), "ACCOUNT",
                    "Email Updated",
                    "Your account email has been updated to " + target.getEmail() + ".",
                    "/profile");
        }

        String after = auditSnapshot.toJson(userSnapshot(emp, target));
        auditService.log(actor.getId(), "USER_UPDATED", userId, before, after);

        if (forceLogoutRequired) {
            forceLogoutAfterCommit(target.getId());
        }

        return toResponse(emp, findCurrentManager(userId), target, null);
    }

    /**
     * Super Admin only (enforced at the controller). Joining date drives probation,
     * leave accrual and seniority elsewhere in the system, so it's deliberately not
     * part of the general updateUser fields — every change goes through here with a
     * mandatory audit trail of the old date, new date, and the reason.
     */
    @Transactional
    public EmployeeResponse updateJoiningDate(UUID userId, UpdateJoiningDateRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        User target = emp.getUser();

        String before = auditSnapshot.toJson(Map.of("joiningDate", emp.getJoiningDate().toString()));
        emp.setJoiningDate(req.getNewJoiningDate());
        emp = employeeRepository.save(emp);

        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("joiningDate", emp.getJoiningDate().toString());
        if (req.getNote() != null && !req.getNote().isBlank()) afterSnapshot.put("note", req.getNote().trim());
        String after = auditSnapshot.toJson(afterSnapshot);

        auditService.log(actor.getId(), "JOINING_DATE_UPDATED", userId, before, after);
        return toResponse(emp, findCurrentManager(userId), target, null);
    }

    /** True if the request would actually change one of the fields gated behind confirmInactiveEdit. */
    private boolean changesGatedUserFields(Employee emp, String currentRole, UUID currentManagerId, UpdateUserRequest req) {
        UUID currentDepartmentId = emp.getDepartment() != null ? emp.getDepartment().getId() : null;
        UUID currentDesignationId = emp.getDesignation() != null ? emp.getDesignation().getId() : null;
        return (req.getDepartmentId() != null && !Objects.equals(req.getDepartmentId(), currentDepartmentId))
                || (req.getDesignationId() != null && !Objects.equals(req.getDesignationId(), currentDesignationId))
                || (req.getEmploymentType() != null && !req.getEmploymentType().isBlank()
                        && !Objects.equals(emp.getEmploymentType(), req.getEmploymentType()))
                || (req.getRole() != null && !req.getRole().isBlank()
                        && !Objects.equals(currentRole, req.getRole().toUpperCase()))
                || (req.getManagerId() != null && !Objects.equals(currentManagerId, req.getManagerId()));
    }

    /**
     * Role and manager are the two fields most worth diffing here — everything else mirrors
     * EmployeeService. Department/designation/location/manager are captured by name, not id —
     * the audit detail popup shows these snapshots verbatim, and a raw UUID means nothing to a
     * reader. Naming it at the time of the edit (rather than resolving the id at read time) also
     * means the audit trail keeps showing what it actually was even if that department/
     * designation/location/manager is later renamed, reassigned, or deleted.
     */
    private Map<String, Object> userSnapshot(Employee emp, User user) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fullName", emp.getFullName());
        snapshot.put("email", user.getEmail());
        snapshot.put("employmentType", emp.getEmploymentType());
        snapshot.put("workMode", emp.getWorkMode());
        snapshot.put("businessUnit", emp.getBusinessUnit() != null ? emp.getBusinessUnit().getName() : null);
        snapshot.put("department", emp.getDepartment() != null ? emp.getDepartment().getName() : null);
        snapshot.put("designation", emp.getDesignation() != null ? emp.getDesignation().getTitle() : null);
        snapshot.put("location", emp.getLocation() != null ? emp.getLocation().getName() : null);
        snapshot.put("shift", emp.getShift() != null ? emp.getShift().getName() : null);
        snapshot.put("role", RoleUtils.primaryRoleCode(user.getRoles(), null));
        UUID managerId = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(emp.getUserId())
                .map(EmployeeManagerHistory::getManagerUserId).orElse(null);
        snapshot.put("manager", resolveEmployeeName(managerId));
        return snapshot;
    }

    /**
     * Rejects an inactive Location, or one with no valid IANA timezone, before it's assigned.
     * Mirrors EmployeeService's identical helper — see its own Javadoc for the full rationale.
     */
    private void validateAssignableLocation(Location location) {
        if (!location.isActive()) {
            throw new IllegalArgumentException("This location is inactive and cannot be assigned. Choose an active location.");
        }
        String timezone = location.getTimezone();
        boolean validTimezone = timezone != null && !timezone.isBlank();
        if (validTimezone) {
            try {
                java.time.ZoneId.of(timezone);
            } catch (java.time.DateTimeException e) {
                validTimezone = false;
            }
        }
        if (!validTimezone) {
            throw new IllegalArgumentException(
                    "This location has no valid timezone configured and cannot be assigned. Contact an administrator.");
        }
    }

    /** Best-effort display name for a user id — employee's full name, falling back to email, null if no id. */
    private String resolveEmployeeName(UUID userId) {
        if (userId == null) return null;
        return employeeRepository.findById(userId)
                .map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse(null));
    }

    /** Super Admin: generate new temp password, set must_change_password = true. */
    @Transactional
    public ResetPasswordResponse resetPassword(UUID userId, String actorEmail) {
        User actor = requireActor(actorEmail);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Snapshot deliberately excludes the password hash — never log credential material,
        // only the safe, hash-free "must change password" flag flip.
        String before = auditSnapshot.toJson(Map.of("mustChangePassword", target.isMustChangePassword()));
        String tempPassword = generateTempPassword();
        target.setPasswordHash(passwordEncoder.encode(tempPassword));
        target.setMustChangePassword(true);
        // Invalidates any JWT issued under the old password (see JwtAuthenticationFilter).
        target.setTokenVersion(target.getTokenVersion() + 1);
        target.setTokenVersionReason("PASSWORD_CHANGED");
        userRepository.save(target);
        String after = auditSnapshot.toJson(Map.of("mustChangePassword", true));

        auditService.log(actor.getId(), "PASSWORD_RESET", userId, before, after);
        // No linkPath: the Password Reset notification intentionally has no "Open related
        // page" action in the Notifications tab (ONEHR-351) — email delivery is unaffected.
        notificationService.send(target.getId(), "SECURITY",
                "Password Reset by Administrator",
                "An administrator has reset your password. Please log in with your temporary password and change it immediately.",
                null);
        return ResetPasswordResponse.builder()
                .tempPassword(tempPassword)
                .message("Password reset. User must change password on next login.")
                .build();
    }

    /**
     * Super Admin: activate or deactivate. Deactivated user's existing JWT stops working
     * immediately (JWT filter checks isEnabled) — two guards below exist specifically because
     * that immediacy makes a mistaken deactivation unrecoverable in-app:
     *  - self-deactivation would end the actor's own session mid-request, with no other Super
     *    Admin necessarily available to undo it;
     *  - deactivating the last active Super Admin would leave nobody able to reactivate anyone,
     *    including themselves — recoverable only via direct DB access.
     * Both are re-checked here (not just hidden in the UI) since the API is the actual
     * security boundary.
     */
    @Transactional
    public EmployeeResponse setActiveStatus(UUID userId, boolean active, String actorEmail) {
        User actor = requireActor(actorEmail);
        Employee emp = employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        User target = emp.getUser();

        if (!active) assertNotSelfOrLastActiveSuperAdmin(actor, target, "deactivate");

        String before = auditSnapshot.toJson(Map.of("active", target.isActive()));
        target.setActive(active);
        userRepository.save(target);
        String after = auditSnapshot.toJson(Map.of("active", active));
        auditService.log(actor.getId(), active ? "USER_ACTIVATED" : "USER_DEACTIVATED", userId, before, after);
        return toResponse(emp, findCurrentManager(userId), target, null);
    }

    /**
     * Super Admin: soft-delete — sets deleted_at. Deleted user's JWT stops working immediately.
     * Carries the exact same self/last-Super-Admin lockout risk as {@link #setActiveStatus} (it
     * also forces active=false), so it re-checks the same guard rather than leaving delete as a
     * bypass of the deactivation restriction above.
     */
    @Transactional
    public void softDeleteUser(UUID userId, String actorEmail) {
        User actor = requireActor(actorEmail);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (target.getDeletedAt() != null)
            throw new IllegalArgumentException("User is already deleted");
        assertNotSelfOrLastActiveSuperAdmin(actor, target, "delete");
        String before = auditSnapshot.toJson(Map.of("deletedAt", "null", "active", target.isActive()));
        target.setDeletedAt(Instant.now());
        target.setActive(false);
        userRepository.save(target);
        // Close out the employee's open manager-history row so they immediately stop being
        // anyone's "current" direct report/peer — without this, every screen that resolves a
        // manager's team via findCurrentDirectReportIds/findCurrentPeerIds keeps surfacing the
        // deleted user forever, since a soft delete otherwise never touches this table.
        historyRepository.closeCurrentEntry(userId, LocalDateTime.now());
        String after = auditSnapshot.toJson(Map.of("deletedAt", target.getDeletedAt().toString(), "active", false));
        auditService.log(actor.getId(), "USER_SOFT_DELETED", userId, before, after);
    }

    /**
     * Shared guard for setActiveStatus(active=false) and softDeleteUser — both end up disabling
     * `target`'s login, so both must block:
     *  - acting on your own account (would end the actor's own session mid-request), and
     *  - the last remaining active Super Admin (would leave nobody able to reactivate anyone).
     * `verb` is only used to phrase the error ("deactivate"/"delete").
     */
    private void assertNotSelfOrLastActiveSuperAdmin(User actor, User target, String verb) {
        if (target.getId().equals(actor.getId()))
            throw new IllegalArgumentException("You cannot " + verb + " your own account. Ask another Super Admin to do this.");
        boolean targetIsSuperAdmin = target.getRoles().stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()));
        if (targetIsSuperAdmin && target.isActive()) {
            boolean anotherActiveSuperAdminExists = userRepository.findActiveSuperAdmins().stream()
                    .anyMatch(u -> !u.getId().equals(target.getId()));
            if (!anotherActiveSuperAdminExists)
                throw new IllegalArgumentException("Cannot " + verb + " the last active Super Admin. Assign Super Admin to another user first.");
        }
    }

    /**
     * Rejects any manager assignment that would create a circular reporting chain.
     * Walks the proposed manager's ancestor chain; if it reaches employeeId at any point
     * the assignment would form a cycle and is rejected with a clear error.
     *
     * Walks one link at a time via findByEmployeeUserIdAndEffectiveToIsNull instead of loading
     * every currently-open manager-history row org-wide into memory (the previous approach) —
     * this call runs synchronously inside createUser/updateUser whenever a manager is assigned,
     * so its cost used to scale with total headcount on every single hire. It now scales with
     * the reporting chain's depth instead, which is what actually bounds a real org hierarchy.
     */
    private void validateNoCycle(UUID employeeId, UUID proposedManagerId) {
        if (proposedManagerId == null) return;
        if (proposedManagerId.equals(employeeId))
            throw new IllegalArgumentException("Cannot assign a user as their own manager.");

        UUID cur = proposedManagerId;
        Set<UUID> visited = new HashSet<>();
        while (cur != null) {
            if (!visited.add(cur)) break; // cycle already in data — stop traversal
            if (cur.equals(employeeId))
                throw new IllegalArgumentException(
                        "Cannot assign this manager: it would create a circular reporting chain. " +
                        "The proposed manager is already a direct or indirect report of this user.");
            cur = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(cur)
                    .map(EmployeeManagerHistory::getManagerUserId)
                    .orElse(null);
        }
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

    private EmployeeResponse toResponse(Employee emp, EmployeeResponse.ManagerRef manager, User user, String tempPassword) {
        String role = RoleUtils.primaryRoleCode(user.getRoles(), "");
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
                .shiftId(emp.getShift() != null ? emp.getShift().getId().toString() : null)
                .shiftName(emp.getShift() != null ? emp.getShift().getName() : null)
                .employmentType(emp.getEmploymentType())
                .workMode(emp.getWorkMode())
                .joiningDate(emp.getJoiningDate())
                .active(user.isActive())
                .currentManager(manager)
                .tempPassword(tempPassword)
                .build();
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String generateTempPassword() {
        int digits = 100000 + RANDOM.nextInt(900000);
        return "OneHR@" + digits;
    }

    private void forceLogoutAfterCommit(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forceLogoutBroadcaster.forceLogout(userId);
                }
            });
            return;
        }
        forceLogoutBroadcaster.forceLogout(userId);
    }
}
