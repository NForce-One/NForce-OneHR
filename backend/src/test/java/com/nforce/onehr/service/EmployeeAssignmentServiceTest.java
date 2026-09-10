package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.penalization.BulkAllocationRequest;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ONEHR-108 bulk-update scoping — every write re-verifies the target is still a *current*
 * direct report at call time (mirrors LeaveService's approve/reject re-verify pattern), and
 * one employee's failure never blocks another's in the same batch.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeAssignmentServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;
    @Mock private WeeklyOffPolicyRepository weeklyOffPolicyRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private AuditService auditService;
    @Mock private PenalizationPolicyAllocationService penalizationPolicyAllocationService;
    @Mock private PenalizationPolicyResolutionService penalizationPolicyResolutionService;
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks private EmployeeAssignmentService service;

    private final UUID managerId = UUID.randomUUID();
    private final UUID directReportId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";

    @BeforeEach
    void setUp() {
        Employee manager = Employee.builder().userId(managerId).fullName("Manager").build();
        // lenient: the "policy not found" test throws before either of these is reached.
        lenient().when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(directReportId));
    }

    private final LocalDate tomorrow = LocalDate.now().plusDays(1);

    @Test
    void bulkUpdateShift_succeeds_forCurrentDirectReport() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.existsById(directReportId)).thenReturn(true);

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
        // Future-effective (ONEHR-336 follow-up): a new EmployeeShiftAssignment row is written —
        // Employee.shift_id (a non-authoritative display cache) is never touched by this path.
        ArgumentCaptor<EmployeeShiftAssignment> captor = ArgumentCaptor.forClass(EmployeeShiftAssignment.class);
        verify(employeeShiftAssignmentRepository).save(captor.capture());
        assertEquals(directReportId, captor.getValue().getEmployeeUserId());
        assertEquals(shift, captor.getValue().getShift());
        assertEquals(tomorrow, captor.getValue().getEffectiveFrom());
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void bulkUpdateShift_replacesAnyExistingPendingAssignment_ratherThanStackingASecondOne() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.existsById(directReportId)).thenReturn(true);

        service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow);

        verify(employeeShiftAssignmentRepository).deleteByEmployeeUserIdAndEffectiveFromGreaterThan(directReportId, LocalDate.now());
    }

    @Test
    void bulkUpdateShift_rejectsToday() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, LocalDate.now()));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_rejectsPastDate() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, LocalDate.now().minusDays(1)));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_rejectsNullEffectiveFrom() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, null));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_fails_forEmployeeNotACurrentDirectReport() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(strangerId), shiftId, tomorrow);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
        verify(employeeRepository, never()).existsById(strangerId);
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_isPartial_whenOneOfTwoEmployeesIsNotADirectReport() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.existsById(directReportId)).thenReturn(true);

        AssignmentBulkResultResponse result =
                service.bulkUpdateShift(managerEmail, List.of(directReportId, strangerId), shiftId, tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertEquals(directReportId, result.getSucceededIds().get(0));
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
    }

    @Test
    void bulkUpdateShift_throws_whenShiftDoesNotExist() {
        UUID unknownShiftId = UUID.randomUUID();
        when(shiftRepository.findById(unknownShiftId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), unknownShiftId, tomorrow));
        verify(employeeRepository, never()).existsById(any());
    }

    @Test
    void bulkUpdateShift_throws_whenShiftIsInactive() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Retired Shift").active(false).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    // ── Section 26: Penalisation Policy bulk-assign routes through the Allocation service ────

    @Test
    void bulkUpdatePenalisationPolicy_routesThroughAllocationService_effectiveToday() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID policyId = UUID.randomUUID();
        when(penalizationPolicyAllocationService.bulkAllocate(any(), eq(managerEmail))).thenReturn(
                AssignmentBulkResultResponse.builder().succeededIds(List.of(directReportId)).failed(List.of()).build());

        AssignmentBulkResultResponse result =
                service.bulkUpdatePenalisationPolicy(managerEmail, List.of(directReportId), policyId);

        assertEquals(List.of(directReportId), result.getSucceededIds());
        assertTrue(result.getFailed().isEmpty());
        ArgumentCaptor<BulkAllocationRequest> captor = ArgumentCaptor.forClass(BulkAllocationRequest.class);
        verify(penalizationPolicyAllocationService).bulkAllocate(captor.capture(), eq(managerEmail));
        assertEquals(List.of(directReportId), captor.getValue().getEmployeeUserIds());
        assertEquals(policyId, captor.getValue().getPenalisationPolicyId());
        assertEquals(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")), captor.getValue().getEffectiveFrom());
        assertNull(captor.getValue().getEffectiveTo(), "open-ended — this screen has no end-date picker");
        // No duplicate audit entry here — PenalizationPolicyAllocationService's own bulkAllocate
        // already logs one, with more per-employee detail than this call site could add.
        verifyNoInteractions(auditService);
    }

    @Test
    void bulkUpdatePenalisationPolicy_employeeNotACurrentDirectReport_reportedAsFailure_neverSentToAllocationService() {
        UUID policyId = UUID.randomUUID();

        AssignmentBulkResultResponse result =
                service.bulkUpdatePenalisationPolicy(managerEmail, List.of(strangerId), policyId);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
        verifyNoInteractions(penalizationPolicyAllocationService);
    }

    @Test
    void bulkUpdatePenalisationPolicy_mergesManagerScopeFailuresWithAllocationServiceFailures() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID policyId = UUID.randomUUID();
        when(penalizationPolicyAllocationService.bulkAllocate(any(), eq(managerEmail))).thenReturn(
                AssignmentBulkResultResponse.builder().succeededIds(List.of()).failed(List.of(
                        AssignmentBulkResultResponse.FailureDto.builder().employeeUserId(directReportId)
                                .reason("This employee already has an allocation to \"Other Policy\" covering ...").build()
                )).build());

        AssignmentBulkResultResponse result =
                service.bulkUpdatePenalisationPolicy(managerEmail, List.of(directReportId, strangerId), policyId);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(2, result.getFailed().size());
        assertTrue(result.getFailed().stream().anyMatch(f -> f.getEmployeeUserId().equals(strangerId)
                && "Not a current direct report".equals(f.getReason())));
        assertTrue(result.getFailed().stream().anyMatch(f -> f.getEmployeeUserId().equals(directReportId)
                && f.getReason().contains("already has an allocation")));
    }

    // ── GAP-014: Team Assignments' policy column/filter must read the authoritative resolution,
    // not the legacy Employee.penalisationPolicy FK nothing writes to any more. ────────────────

    @Test
    void listTeamAssignments_displaysResolvedPolicy_notLegacyEmployeeFk() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID resolvedPolicyId = UUID.randomUUID();
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One")
                .user(User.builder().id(directReportId).build()).build();
        when(employeeRepository.findAllById(List.of(directReportId))).thenReturn(List.of(employee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any()))
                .thenReturn(Map.of(directReportId, resolvedPolicyId));
        when(penalisationPolicyRepository.findById(resolvedPolicyId))
                .thenReturn(Optional.of(PenalisationPolicy.builder().id(resolvedPolicyId).name("Strict Policy").build()));

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, null, null, null, null);

        assertEquals(1, rows.size());
        assertEquals(resolvedPolicyId, rows.get(0).getPenalisationPolicyId());
        assertEquals("Strict Policy", rows.get(0).getPenalisationPolicyName());
    }

    @Test
    void listTeamAssignments_filtersByResolvedPolicy_notLegacyEmployeeFk() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID matchingPolicyId = UUID.randomUUID();
        UUID otherPolicyId = UUID.randomUUID();
        UUID secondReportId = UUID.randomUUID();
        Employee reportA = Employee.builder().userId(directReportId).fullName("Report A")
                .user(User.builder().id(directReportId).build()).build();
        Employee reportB = Employee.builder().userId(secondReportId).fullName("Report B")
                .user(User.builder().id(secondReportId).build()).build();
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId))
                .thenReturn(List.of(directReportId, secondReportId));
        when(employeeRepository.findAllById(List.of(directReportId, secondReportId))).thenReturn(List.of(reportA, reportB));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any()))
                .thenReturn(Map.of(directReportId, matchingPolicyId, secondReportId, otherPolicyId));
        lenient().when(penalisationPolicyRepository.findById(matchingPolicyId))
                .thenReturn(Optional.of(PenalisationPolicy.builder().id(matchingPolicyId).name("Match").build()));

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, matchingPolicyId, null, null, null);

        assertEquals(1, rows.size());
        assertEquals(directReportId, rows.get(0).getEmployeeUserId());
    }
}
