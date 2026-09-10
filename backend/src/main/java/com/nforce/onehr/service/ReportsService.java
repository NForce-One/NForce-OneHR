package com.nforce.onehr.service;

import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manager: Attendance Request Reports Library (ONEHR-109) — only the 4 report cards backed by
 * real data today. The other 6 cards in the prototype (Overtime, Partial Day, Mobile Location
 * Punches, Web Clock-ins w/ Forgot ID, Shift & Weekly Off Requests, Working Remotely Requests)
 * have no backing entity anywhere in the codebase and are intentionally NOT implemented here —
 * the frontend renders them as disabled "Coming soon" cards instead of silently returning
 * empty data (ONEHR-109 AC #5).
 */
@Service
@RequiredArgsConstructor
public class ReportsService {

    public enum ReportType { REGULARIZATION, WEB_CLOCK_IN }

    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;

    @Transactional(readOnly = true)
    public List<AttendanceRequestReportRow> getAttendanceRequestReport(
            String managerEmail, ReportType type, LocalDate from, LocalDate to) {
        Employee manager = resolveManager(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));

        List<AttendanceRequestReportRow> rows = type == ReportType.REGULARIZATION
                ? regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(reportIds, from, to).stream()
                        .map(r -> AttendanceRequestReportRow.builder()
                                .employeeUserId(r.getEmployeeUserId())
                                .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                                .fullName(nameOf(byId, r.getEmployeeUserId()))
                                .date(r.getAttendanceDate())
                                .checkIn(r.getRequestedCheckIn())
                                .checkOut(r.getRequestedCheckOut())
                                .reason(r.getReason())
                                .status(r.getStatus())
                                .build())
                        .toList()
                : webClockInRequestRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to).stream()
                        .map(r -> AttendanceRequestReportRow.builder()
                                .employeeUserId(r.getEmployeeUserId())
                                .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                                .fullName(nameOf(byId, r.getEmployeeUserId()))
                                .date(r.getWorkDate())
                                .checkIn(r.getRequestedCheckIn())
                                .checkOut(r.getCheckedOutAt())
                                .reason(r.getReason())
                                // Web Clock-In has no review status at all (see
                                // WebClockInService's own class Javadoc) — this column instead
                                // reflects the session's own completion state.
                                .status(r.getCheckedOutAt() != null ? "Checked Out" : "Checked In")
                                .build())
                        .toList();

        return rows.stream()
                .sorted(Comparator.comparing(AttendanceRequestReportRow::getDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public String toCsv(List<AttendanceRequestReportRow> rows) {
        StringBuilder sb = new StringBuilder("Employee Code,Employee Name,Date,Check In,Check Out,Reason,Status\n");
        for (AttendanceRequestReportRow r : rows) {
            sb.append(csvEscape(r.getEmployeeCode())).append(',')
              .append(csvEscape(r.getFullName())).append(',')
              .append(r.getDate() != null ? r.getDate() : "").append(',')
              .append(r.getCheckIn() != null ? r.getCheckIn() : "").append(',')
              .append(r.getCheckOut() != null ? r.getCheckOut() : "").append(',')
              .append(csvEscape(r.getReason())).append(',')
              .append(csvEscape(r.getStatus())).append('\n');
        }
        return sb.toString();
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) ? "\"" + escaped + "\"" : escaped;
    }

    private String codeOf(Map<UUID, Employee> byId, UUID employeeUserId) {
        Employee e = byId.get(employeeUserId);
        return e != null ? e.getEmployeeCode() : null;
    }

    private String nameOf(Map<UUID, Employee> byId, UUID employeeUserId) {
        Employee e = byId.get(employeeUserId);
        return e != null ? e.getFullName() : null;
    }

    private Employee resolveManager(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
