package com.nforce.onehr.controller;

import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.assignments.AssignmentLookupsResponse;
import com.nforce.onehr.dto.assignments.BulkAssignmentRequest;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.dto.assignments.ImportResultResponse;
import com.nforce.onehr.service.EmployeeAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** Manager: Bulk-Edit Team Shift, Weekly Off & Penalisation Policy Assignments (ONEHR-108). */
@RestController
@RequestMapping("/api/employee-assignments")
@RequiredArgsConstructor
public class EmployeeAssignmentController {

    private final EmployeeAssignmentService service;

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<EmployeeAssignmentRow> team(
            @RequestParam(required = false) UUID shiftId,
            @RequestParam(required = false) UUID weeklyOffPolicyId,
            @RequestParam(required = false) UUID penalisationPolicyId,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String search,
            Principal principal) {
        return service.listTeamAssignments(
                principal.getName(), shiftId, weeklyOffPolicyId, penalisationPolicyId, department, location, search);
    }

    @GetMapping("/lookups")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public AssignmentLookupsResponse lookups(Principal principal) {
        return service.getLookups(principal.getName());
    }

    @PostMapping("/bulk-update-shift")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public AssignmentBulkResultResponse bulkUpdateShift(@Valid @RequestBody BulkAssignmentRequest req, Principal principal) {
        return service.bulkUpdateShift(principal.getName(), req.getEmployeeUserIds(), req.getPolicyId(), req.getEffectiveFrom());
    }

    @PostMapping("/bulk-update-weekly-off")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public AssignmentBulkResultResponse bulkUpdateWeeklyOff(@Valid @RequestBody BulkAssignmentRequest req, Principal principal) {
        return service.bulkUpdateWeeklyOff(principal.getName(), req.getEmployeeUserIds(), req.getPolicyId());
    }

    @PostMapping("/bulk-update-penalisation-policy")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public AssignmentBulkResultResponse bulkUpdatePenalisationPolicy(@Valid @RequestBody BulkAssignmentRequest req, Principal principal) {
        return service.bulkUpdatePenalisationPolicy(principal.getName(), req.getEmployeeUserIds(), req.getPolicyId());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public ImportResultResponse importAssignments(@RequestParam MultipartFile file, Principal principal) throws IOException {
        return service.importShiftsAndWeeklyOffs(principal.getName(), file);
    }
}
