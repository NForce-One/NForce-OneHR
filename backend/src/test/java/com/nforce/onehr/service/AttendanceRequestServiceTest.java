package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.CreateAttendanceRequest;
import com.nforce.onehr.entity.AttendanceRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests for Partial Day's monthly-hours cap (2h/month, spendable on any day(s)
 * within the month) and WFH's 2-day prior-notice rule. Both are hard limits enforced in submit()
 * — Partial Day's cap used to be advisory-only (see git history) but is now rejected outright,
 * same as WFH's monthly cap; getPartialDayBalance still reports usage-vs-cap for the UI's
 * "View Available Balance" display. Mirrors RegularizationServiceTest's isolation approach.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceRequestServiceTest {

    @Mock private AttendanceRequestRepository requestRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceProperties attendanceProps;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private AttendanceRequestService service;

    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        Role employeeRole = Role.builder().id(1).code("EMPLOYEE").displayName("Employee").build();
        User employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(java.util.Optional.of(employeeUser));
        lenient().when(requestRepository.save(any(AttendanceRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        // toResponse() looks these up defensively — absent is fine, it degrades to nulls/"Unknown".
        lenient().when(employeeRepository.findById(any())).thenReturn(java.util.Optional.empty());
        lenient().when(userRepository.findById(any())).thenReturn(java.util.Optional.empty());
        lenient().when(attendanceProps.getZone()).thenReturn("Asia/Kolkata");
    }

    private CreateAttendanceRequest partialDayRequest(LocalDate date, double hours) {
        return CreateAttendanceRequest.builder()
                .requestType("PARTIAL_DAY")
                .requestDate(date)
                .partialDayHours(BigDecimal.valueOf(hours))
                .partialDayMode("LATE_ARRIVE")
                .reason("test")
                .build();
    }

    private AttendanceRequest existing(LocalDate date, double hours, String status) {
        return existing(date, hours, "LATE_ARRIVE", status);
    }

    private AttendanceRequest existing(LocalDate date, double hours, String mode, String status) {
        return AttendanceRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .requestType("PARTIAL_DAY")
                .requestDate(date)
                .partialDayHours(BigDecimal.valueOf(hours))
                .partialDayMode(mode)
                .status(status)
                .build();
    }

    @Test
    void allowsPartialDayRequestWithinTheAdvisoryAllowance() {
        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 3), 2), employeeEmail);

        assertEquals(BigDecimal.valueOf(2.0), response.getPartialDayHours());
    }

    @Test
    void rejectsPartialDayRequestThatExceedsTheMonthlyCap() {
        // The 2h/month cap is a hard limit (see ATTENDANCE_POLICY: "not allowed to raise a
        // request for more than 120 minutes") — a single request larger than the whole cap is
        // rejected outright, not left for the approver to judge.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 3), employeeEmail));
        assertTrue(ex.getMessage().contains("not allowed to raise a request for more than 120 minutes"));
    }

    @Test
    void rejectsZeroOrNegativePartialDayHours() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 0), employeeEmail));
        assertTrue(ex.getMessage().contains("greater than zero"));
    }

    @Test
    void wfhRequestsAreUnaffectedByThePartialDayAllowance() {
        CreateAttendanceRequest wfh = CreateAttendanceRequest.builder()
                .requestType("WFH")
                .requestDate(LocalDate.now().plusDays(10))
                .reason("test")
                .build();

        AttendanceRequestResponse response = service.submit(wfh, employeeEmail);

        assertNull(response.getPartialDayHours());
        // The same repository method backs both WFH's and Partial Day's monthly-cap lookup
        // (see wfhDaysUsedInMonth/partialDayHoursUsedInMonth) — it's called here for WFH's own
        // cap, just never with PARTIAL_DAY as the type.
        verify(requestRepository, never()).findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(any(), eq("PARTIAL_DAY"), any(), any());
    }

    // ---------------------------------------------------------------- Partial Day duplicate-slot blocking

    @Test
    void blocksPartialDayRequestWhenSameSlotIsPending() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LATE_ARRIVE", "PENDING")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail));
        assertTrue(ex.getMessage().contains("pending or approved"));
    }

    @Test
    void blocksPartialDayRequestWhenSameSlotIsApproved() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LATE_ARRIVE", "APPROVED")));

        assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail));
    }

    @Test
    void allowsPartialDayRequestWhenSameSlotWasRejected() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LATE_ARRIVE", "REJECTED")));

        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail);

        assertEquals(BigDecimal.valueOf(1.0), response.getPartialDayHours());
    }

    @Test
    void allowsPartialDayRequestWhenSameDateButDifferentMode() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LEAVING_EARLY", "PENDING")));

        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail);

        assertEquals(BigDecimal.valueOf(1.0), response.getPartialDayHours());
    }

    @Test
    void allowsPartialDayRequestWhenSameDateAndModeButDifferentDuration() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 2.0, "LATE_ARRIVE", "PENDING")));

        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail);

        assertEquals(BigDecimal.valueOf(1.0), response.getPartialDayHours());
    }

    // ---------------------------------------------------------------- WFH prior notice

    private CreateAttendanceRequest wfhRequest(LocalDate date) {
        return CreateAttendanceRequest.builder().requestType("WFH").requestDate(date).reason("test").build();
    }

    @Test
    void rejectsWfhRequestForToday() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(wfhRequest(LocalDate.now()), employeeEmail));
        assertEquals("WFH request requires 2 day(s) of prior notice.", ex.getMessage());
    }

    @Test
    void rejectsWfhRequestForTomorrow_oneDayIsNotEnoughNotice() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(wfhRequest(LocalDate.now().plusDays(1)), employeeEmail));
        assertEquals("WFH request requires 2 day(s) of prior notice.", ex.getMessage());
    }

    @Test
    void allowsWfhRequestExactlyTwoDaysOut() {
        AttendanceRequestResponse response = service.submit(wfhRequest(LocalDate.now().plusDays(2)), employeeEmail);
        assertEquals("WFH", response.getRequestType());
    }

    // ---------------------------------------------------------------- getPartialDayBalance

    @Test
    void balanceCountsPendingRequestsTowardUsage() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 1.0, "PENDING")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(BigDecimal.valueOf(1.0), balance.usedHours());
        assertEquals(BigDecimal.valueOf(1.0), balance.remainingHours());
    }

    @Test
    void balanceExcludesRejectedRequests() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 2.0, "REJECTED")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(0, balance.usedHours().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance.remainingHours().compareTo(BigDecimal.valueOf(2.0)));
    }

    @Test
    void balanceReflectsApprovedUsageEvenPastTheAllowance() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 2.5, "APPROVED")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(BigDecimal.valueOf(2.5), balance.usedHours());
        assertEquals(BigDecimal.valueOf(0).setScale(1), balance.remainingHours().setScale(1));
    }

    // ---------------------------------------------------------------- approve/reject notifications (ONEHR-140)

    private final UUID hrAdminId = UUID.randomUUID();
    private final String hrAdminEmail = "hr@test.com";

    private void stubHrAdmin() {
        Role hrRole = Role.builder().id(2).code("HR_ADMIN").displayName("HR Admin").build();
        User hrUser = User.builder().id(hrAdminId).email(hrAdminEmail).roles(Set.of(hrRole)).build();
        lenient().when(userRepository.findByEmail(hrAdminEmail)).thenReturn(java.util.Optional.of(hrUser));
    }

    @Test
    void approve_notifiesOriginalRequester() {
        stubHrAdmin();
        AttendanceRequest pending = existing(LocalDate.of(2026, 8, 3), 2.0, "PENDING");
        when(requestRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

        AttendanceRequestResponse resp = service.approve(pending.getId(), "Looks good", hrAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        verify(notificationService, times(1))
                .send(eq(employeeId), eq("ATTENDANCE_REQUEST_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_notifiesOriginalRequesterWithReason() {
        stubHrAdmin();
        AttendanceRequest pending = existing(LocalDate.of(2026, 8, 3), 2.0, "PENDING");
        when(requestRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

        AttendanceRequestResponse resp = service.reject(pending.getId(), "Team short-staffed", hrAdminEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("ATTENDANCE_REQUEST_REJECTED"), any(),
                contains("Team short-staffed"), any());
    }

    @Test
    void approve_calledTwice_sendsNotificationOnlyOnce() {
        stubHrAdmin();
        AttendanceRequest pending = existing(LocalDate.of(2026, 8, 3), 2.0, "PENDING");
        when(requestRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

        service.approve(pending.getId(), null, hrAdminEmail);
        assertThrows(IllegalArgumentException.class, () -> service.approve(pending.getId(), null, hrAdminEmail));

        verify(notificationService, times(1))
                .send(eq(employeeId), eq("ATTENDANCE_REQUEST_APPROVED"), any(), any(), any());
    }
}
