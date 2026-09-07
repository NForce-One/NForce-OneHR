package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateEmployeeRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.EmployeeCodeConflictException;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies {@link EmployeeService#createEmployee} goes through the centralized
 * {@link EmployeeCodeGenerator} (ONEHR Employee ID rework) instead of any local MAX+1 logic,
 * and that the resulting employee_code is exactly whatever the generator claimed — including
 * propagating a claim conflict as a real failure rather than falling back to a different code.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceCreateTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DesignationRepository designationRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private EmailService emailService;
    @Mock private LeaveService leaveService;
    @Mock private EmployeeCodeGenerator employeeCodeGenerator;

    @InjectMocks private EmployeeService employeeService;

    private final String actorEmail = "hradmin@test.com";
    private CreateEmployeeRequest req;

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(UUID.randomUUID()).email(actorEmail).build();
        Role employeeRole = Role.builder().id(1).code("EMPLOYEE").build();

        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(userRepository.existsByEmailAndDeletedAtIsNull(any())).thenReturn(false);
        lenient().when(roleRepository.findByCode("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        // Default-shift lookup (see EmployeeService#createEmployee) — every employee created
        // must always end up with a real assigned Shift (product invariant, ONEHR-108), so most
        // tests here that don't care about shift assignment get a normal active Regular Shift by
        // default; the two tests that specifically exercise the missing/inactive-default-shift
        // scenario override this below.
        lenient().when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME))
                .thenReturn(Optional.of(Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build()));

        req = new CreateEmployeeRequest();
        req.setFullName("Jane Smith");
        req.setEmail("jane@nforceone.com");
        req.setJoiningDate(LocalDate.now());
    }

    @Test
    void createEmployee_usesCodeClaimedByCentralizedGenerator() {
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

        var response = employeeService.createEmployee(req, actorEmail);

        assertEquals("NF-2026-0057", response.getEmployeeCode());
        verify(employeeCodeGenerator).claim(req.getEmployeeCode());
    }

    /**
     * CreateEmployeeRequest has no shiftId field at all — this path always defaults to the
     * organization's default shift, resolved server-side by its stable seeded name (never a
     * hardcoded id), so an employee created through this endpoint can never end up with no shift.
     */
    @Test
    void createEmployee_defaultsToRegularShift_whenNoShiftCanBeSpecified() {
        Shift regularShift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)).thenReturn(Optional.of(regularShift));
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        employeeService.createEmployee(req, actorEmail);

        verify(employeeRepository).save(captor.capture());
        assertEquals(regularShift.getId(), captor.getValue().getShift().getId());
    }

    /**
     * An inactive Regular Shift is never silently assigned — same "must be active" rule every
     * other shift-assignment path already enforces. Every employee having a real assigned Shift is
     * a hard invariant now, not a tolerated absence — so this fails loudly rather than saving a
     * shift-less employee.
     */
    @Test
    void createEmployee_failsLoudly_whenTheOnlyRegularShiftIsInactive() {
        Shift inactiveRegularShift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(false).build();
        when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)).thenReturn(Optional.of(inactiveRegularShift));

        assertThrows(IllegalStateException.class, () -> employeeService.createEmployee(req, actorEmail));

        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createEmployee_passesSubmittedPreviewCodeThroughToGenerator() {
        req.setEmployeeCode("NF-2026-0056");
        when(employeeCodeGenerator.claim("NF-2026-0056")).thenReturn("NF-2026-0056");

        var response = employeeService.createEmployee(req, actorEmail);

        assertEquals("NF-2026-0056", response.getEmployeeCode());
        verify(employeeCodeGenerator).claim("NF-2026-0056");
    }

    @Test
    void createEmployee_generatorConflict_failsWithoutPersistingEmployee() {
        req.setEmployeeCode("NF-2026-0056");
        when(employeeCodeGenerator.claim("NF-2026-0056"))
                .thenThrow(new EmployeeCodeConflictException("NF-2026-0056"));

        assertThrows(EmployeeCodeConflictException.class,
                () -> employeeService.createEmployee(req, actorEmail));

        verify(employeeRepository, never()).save(any());
    }

    @Test
    void previewNextEmployeeCode_delegatesToGenerator() {
        when(employeeCodeGenerator.preview()).thenReturn("NF-2026-0057");

        assertEquals("NF-2026-0057", employeeService.previewNextEmployeeCode());
    }

    // ── Finalized Location/Timezone model: Location is the ONLY timezone input ──
    // CreateEmployeeRequest has no timezone field at all (see its own class comment) — these
    // lock in that an invalid/inactive/timezone-less Location is rejected outright rather than
    // silently accepted, since Location is now the sole source of the employee's effective
    // attendance timezone.

    @Test
    void createEmployee_rejectsALocationIdThatDoesNotExist() {
        UUID bogusLocationId = UUID.randomUUID();
        req.setLocationId(bogusLocationId);
        when(locationRepository.findById(bogusLocationId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createEmployee_rejectsAnInactiveLocation() {
        UUID locationId = UUID.randomUUID();
        Location inactive = Location.builder().id(locationId).name("Hyderabad").timezone("Asia/Kolkata").active(false).build();
        req.setLocationId(locationId);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(inactive));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    /**
     * Should be unreachable through the normal Org Setup flow (OrgService#createLocation always
     * validates the timezone against a fixed supported set — see SUPPORTED_TIMEZONES), but this
     * is the hard backstop the feature explicitly requires: a Location with no valid timezone
     * must never be assignable.
     */
    @Test
    void createEmployee_rejectsALocationWithNoTimezoneConfigured() {
        UUID locationId = UUID.randomUUID();
        Location noTimezone = Location.builder().id(locationId).name("Legacy Office").timezone(null).active(true).build();
        req.setLocationId(locationId);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(noTimezone));

        assertThrows(IllegalArgumentException.class, () -> employeeService.createEmployee(req, actorEmail));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void createEmployee_acceptsAnActiveLocationWithAValidTimezone() {
        UUID locationId = UUID.randomUUID();
        Location valid = Location.builder().id(locationId).name("Hyderabad").timezone("Asia/Kolkata").active(true).build();
        req.setLocationId(locationId);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(valid));
        when(employeeCodeGenerator.claim(req.getEmployeeCode())).thenReturn("NF-2026-0057");

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        employeeService.createEmployee(req, actorEmail);

        verify(employeeRepository).save(captor.capture());
        assertEquals(locationId, captor.getValue().getLocation().getId());
    }
}
