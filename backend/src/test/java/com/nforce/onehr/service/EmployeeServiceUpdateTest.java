package com.nforce.onehr.service;

import com.nforce.onehr.dto.UpdateEmployeeRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEMPORARY (ONEHR-336 follow-up): {@link EmployeeService#updateEmployee} must reject any actual
 * change to an employee's Location — see that method's own guard comment for why (pending a
 * proper reassignment flow that correctly effective-dates attendance-relevant history). Employee
 * CREATION ({@link EmployeeService#createEmployee}, covered by {@link EmployeeServiceCreateTest})
 * is unaffected — Location remains freely settable there. Note: {@link UpdateEmployeeRequest} has
 * no shiftId field at all (Shift is HR-Admin-scope-only via {@code UserManagementService}, see
 * {@link UserManagementServiceTest} for that restriction's coverage).
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceUpdateTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private BusinessUnitRepository businessUnitRepository;
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

    private final UUID targetUserId = UUID.randomUUID();
    private final UUID currentLocationId = UUID.randomUUID();
    private final UUID newLocationId = UUID.randomUUID();
    private final String actorEmail = "hradmin@test.com";

    private Employee targetEmployee;
    private Location currentLocation;

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(UUID.randomUUID()).email(actorEmail).build();
        User targetUser = User.builder().id(targetUserId).email("target@test.com").active(true).build();
        currentLocation = Location.builder().id(currentLocationId).name("Hyderabad").timezone("Asia/Kolkata").active(true).build();
        targetEmployee = Employee.builder().userId(targetUserId).user(targetUser)
                .fullName("Target User").employmentType("FULL_TIME").workMode("ONSITE")
                .location(currentLocation).joiningDate(LocalDate.now()).build();

        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(employeeRepository.findById(targetUserId)).thenReturn(Optional.of(targetEmployee));
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    @Test
    void updateEmployee_locationChange_isRejected() {
        UpdateEmployeeRequest req = new UpdateEmployeeRequest();
        req.setLocationId(newLocationId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> employeeService.updateEmployee(targetUserId, req, actorEmail));

        assertTrue(ex.getMessage().contains("Location"));
        assertEquals(currentLocation, targetEmployee.getLocation());
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void updateEmployee_unchangedLocation_isStillAllowed_doesNotThrow() {
        // Resubmitting the SAME location already on the employee (e.g. an edit form that always
        // sends the current value) must keep working — only a genuine change is rejected.
        UpdateEmployeeRequest req = new UpdateEmployeeRequest();
        req.setLocationId(currentLocationId);

        var response = employeeService.updateEmployee(targetUserId, req, actorEmail);

        assertEquals(currentLocation, targetEmployee.getLocation());
        assertEquals(currentLocationId.toString(), response.getLocationId());
    }

    @Test
    void updateEmployee_unrelatedFieldChange_stillWorks_whenLocationIsNotTouched() {
        UpdateEmployeeRequest req = new UpdateEmployeeRequest();
        req.setFullName("Renamed Employee");

        var response = employeeService.updateEmployee(targetUserId, req, actorEmail);

        assertEquals("Renamed Employee", response.getFullName());
        verify(employeeRepository).save(any());
    }
}
