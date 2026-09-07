package com.nforce.onehr.controller;

import com.nforce.onehr.dto.HierarchyNodeDto;
import com.nforce.onehr.dto.org.*;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.OrgService;
import com.nforce.onehr.service.ShiftWeeklyOffRulesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final ShiftWeeklyOffRulesService shiftWeeklyOffRulesService;
    private final AttendanceRulesService attendanceRulesService;

    // ── Hierarchy ────────────────────────────────────────────────────────────

    @GetMapping("/hierarchy")
    public List<HierarchyNodeDto> hierarchy() {
        return orgService.getHierarchy();
    }

    // ── Business Units ───────────────────────────────────────────────────────

    @GetMapping("/business-units")
    public List<BusinessUnitResponse> listBusinessUnits() {
        return orgService.listBusinessUnits();
    }

    @PostMapping("/business-units")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessUnitResponse createBusinessUnit(@Valid @RequestBody CreateBusinessUnitRequest req) {
        return orgService.createBusinessUnit(req);
    }

    @PutMapping("/business-units/{id}")
    public BusinessUnitResponse updateBusinessUnit(@PathVariable UUID id, @Valid @RequestBody UpdateBusinessUnitRequest req) {
        return orgService.updateBusinessUnit(id, req);
    }

    @PatchMapping("/business-units/{id}/toggle-active")
    public BusinessUnitResponse toggleBusinessUnitActive(@PathVariable UUID id) {
        return orgService.toggleBusinessUnitActive(id);
    }

    @DeleteMapping("/business-units/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBusinessUnit(@PathVariable UUID id) {
        orgService.deleteBusinessUnit(id);
    }

    // ── Departments ───────────────────────────────────────────────────────────

    @GetMapping("/departments")
    public List<DepartmentResponse> listDepartments() {
        return orgService.listDepartments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse createDepartment(@Valid @RequestBody CreateDepartmentRequest req) {
        return orgService.createDepartment(req);
    }

    @PutMapping("/departments/{id}")
    public DepartmentResponse updateDepartment(@PathVariable UUID id, @Valid @RequestBody UpdateDepartmentRequest req) {
        return orgService.updateDepartment(id, req);
    }

    @PatchMapping("/departments/{id}/toggle-active")
    public DepartmentResponse toggleDepartmentActive(@PathVariable UUID id) {
        return orgService.toggleDepartmentActive(id);
    }

    @DeleteMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDepartment(@PathVariable UUID id) {
        orgService.deleteDepartment(id);
    }

    // ── Designations ──────────────────────────────────────────────────────────

    @GetMapping("/designations")
    public List<DesignationResponse> listDesignations() {
        return orgService.listDesignations();
    }

    @PostMapping("/designations")
    @ResponseStatus(HttpStatus.CREATED)
    public DesignationResponse createDesignation(@Valid @RequestBody CreateDesignationRequest req) {
        return orgService.createDesignation(req);
    }

    @PutMapping("/designations/{id}")
    public DesignationResponse updateDesignation(@PathVariable UUID id, @Valid @RequestBody UpdateDesignationRequest req) {
        return orgService.updateDesignation(id, req);
    }

    @PatchMapping("/designations/{id}/toggle-active")
    public DesignationResponse toggleDesignationActive(@PathVariable UUID id) {
        return orgService.toggleDesignationActive(id);
    }

    @DeleteMapping("/designations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDesignation(@PathVariable UUID id) {
        orgService.deleteDesignation(id);
    }

    // ── Locations ─────────────────────────────────────────────────────────────

    @GetMapping("/locations")
    public List<LocationResponse> listLocations() {
        return orgService.listLocations();
    }

    // The fixed set of IANA zones a Location's Timezone may be — see OrgService
    // .SUPPORTED_TIMEZONES — open to any authenticated caller, same as listLocations, since it's
    // just the menu of options for the Timezone dropdown, not a mutation.
    @GetMapping("/locations/timezones")
    public List<String> listSupportedLocationTimezones() {
        return OrgService.SUPPORTED_TIMEZONES;
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse createLocation(@Valid @RequestBody CreateLocationRequest req) {
        return orgService.createLocation(req);
    }

    @PutMapping("/locations/{id}")
    public LocationResponse updateLocation(@PathVariable UUID id, @Valid @RequestBody UpdateLocationRequest req) {
        return orgService.updateLocation(id, req);
    }

    @PatchMapping("/locations/{id}/toggle-active")
    public LocationResponse toggleLocationActive(@PathVariable UUID id) {
        return orgService.toggleLocationActive(id);
    }

    @DeleteMapping("/locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocation(@PathVariable UUID id) {
        orgService.deleteLocation(id);
    }

    // ── Shifts ────────────────────────────────────────────────────────────────
    // Create/edit/delete are Super Admin only — enforced in OrgService (@PreAuthorize), not
    // just here; listing itself has no role restriction (every employee's own attendance flow
    // reads shift config indirectly via AttendanceService, and My Team / HR views need to list
    // shifts for assignment regardless of role).

    @GetMapping("/shifts")
    public List<ShiftResponse> listShifts() {
        return orgService.listShifts();
    }

    @GetMapping("/shifts/{id}/employees")
    public List<ShiftEmployeeResponse> listShiftEmployees(@PathVariable UUID id) {
        return orgService.listShiftEmployees(id);
    }

    /** Version history drill-down — same no-role-restriction rationale as listShifts above. */
    @GetMapping("/shifts/{id}/versions")
    public List<ShiftVersionResponse> listShiftVersions(@PathVariable UUID id) {
        return orgService.listShiftVersions(id);
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftResponse createShift(@Valid @RequestBody CreateShiftRequest req) {
        return orgService.createShift(req);
    }

    @PutMapping("/shifts/{id}")
    public ShiftResponse updateShift(@PathVariable UUID id, @Valid @RequestBody UpdateShiftRequest req) {
        return orgService.updateShift(id, req);
    }

    @PatchMapping("/shifts/{id}/toggle-active")
    public ShiftResponse toggleShiftActive(@PathVariable UUID id) {
        return orgService.toggleShiftActive(id);
    }

    @DeleteMapping("/shifts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteShift(@PathVariable UUID id) {
        orgService.deleteShift(id);
    }

    // ── Weekly Off Policies ───────────────────────────────────────────────────
    // Same Super-Admin-only create/edit/delete pattern as Shifts (enforced in OrgService);
    // listing is open — the Add/Edit User and bulk-assignment flows need it regardless of role.

    @GetMapping("/weekly-off-policies")
    public List<WeeklyOffPolicyResponse> listWeeklyOffPolicies() {
        return orgService.listWeeklyOffPolicies();
    }

    @PostMapping("/weekly-off-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public WeeklyOffPolicyResponse createWeeklyOffPolicy(@Valid @RequestBody CreateWeeklyOffPolicyRequest req) {
        return orgService.createWeeklyOffPolicy(req);
    }

    @PutMapping("/weekly-off-policies/{id}")
    public WeeklyOffPolicyResponse updateWeeklyOffPolicy(@PathVariable UUID id, @Valid @RequestBody UpdateWeeklyOffPolicyRequest req) {
        return orgService.updateWeeklyOffPolicy(id, req);
    }

    @DeleteMapping("/weekly-off-policies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWeeklyOffPolicy(@PathVariable UUID id) {
        orgService.deleteWeeklyOffPolicy(id);
    }

    // ── Shifts & Weekly Off Rules ─────────────────────────────────────────────
    // Org-level singleton (see ShiftWeeklyOffRules) — P1 holds exactly one setting, Maximum Shift
    // Day Duration. Read is open (the Shift form's duration warning needs it regardless of role);
    // update is Super-Admin-only, enforced in ShiftWeeklyOffRulesService.

    @GetMapping("/shift-weekly-off-rules")
    public ShiftWeeklyOffRulesResponse getShiftWeeklyOffRules() {
        return shiftWeeklyOffRulesService.getRules();
    }

    @PutMapping("/shift-weekly-off-rules")
    public ShiftWeeklyOffRulesResponse updateShiftWeeklyOffRules(@Valid @RequestBody UpdateShiftWeeklyOffRulesRequest req) {
        return shiftWeeklyOffRulesService.updateMaximumShiftDayDurationHours(req);
    }

    // ── Attendance Rules ──────────────────────────────────────────────────────
    // Org-level singleton (see AttendanceRules) — currently holds exactly one setting, Half Day
    // Max Hours (the absolute-hours HALF_DAY classification threshold, migrated off
    // app.attendance.half-day-max-hours — see AttendanceRulesService's own Javadoc). Read is open
    // (attendance status derivation needs it for every employee regardless of role); update is
    // Super-Admin-only, enforced in AttendanceRulesService.

    @GetMapping("/attendance-rules")
    public AttendanceRulesResponse getAttendanceRules() {
        return attendanceRulesService.getRules();
    }

    @PutMapping("/attendance-rules")
    public AttendanceRulesResponse updateAttendanceRules(@Valid @RequestBody UpdateAttendanceRulesRequest req) {
        return attendanceRulesService.updateHalfDayMaxHours(req);
    }

    // Org-wide fallback timezone (Phase 2 timezone pass) — consulted only when neither an
    // employee's own Employee.timezone nor their Location.timezone is set. Separate endpoint
    // from the one above so existing halfDayMaxHours callers are unaffected. Update is
    // Super-Admin-only, enforced in AttendanceRulesService.updateDefaultTimezone.
    @PutMapping("/attendance-rules/default-timezone")
    public AttendanceRulesResponse updateDefaultTimezone(@Valid @RequestBody UpdateDefaultTimezoneRequest req) {
        return attendanceRulesService.updateDefaultTimezone(req);
    }
}
