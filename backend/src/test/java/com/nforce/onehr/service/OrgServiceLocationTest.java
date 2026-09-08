package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.CreateLocationRequest;
import com.nforce.onehr.dto.org.LocationResponse;
import com.nforce.onehr.dto.org.UpdateLocationRequest;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.BusinessUnitRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.AssetRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Location name/city/state/country/holiday-region stay freely editable (any number of Locations
 * can be created) — the same capitalization normalization and duplicate-name protection as
 * before. Timezone, however, is the one field that's NOT freely editable: it must be one of
 * {@link OrgService#SUPPORTED_TIMEZONES}, never an arbitrary or malformed IANA zone id, so a
 * Location + Timezone combination that doesn't correspond to one of the business's actual
 * supported zones (e.g. the pre-existing "Texas" → America/New_York data bug fixed in V169) is no
 * longer representable.
 */
@ExtendWith(MockitoExtension.class)
class OrgServiceLocationTest {

    @Mock private BusinessUnitRepository businessUnitRepo;
    @Mock private DepartmentRepository departmentRepo;
    @Mock private DesignationRepository designationRepo;
    @Mock private LocationRepository locationRepo;
    @Mock private ShiftRepository shiftRepo;
    @Mock private com.nforce.onehr.repository.ShiftVersionRepository shiftVersionRepository;
    @Mock private com.nforce.onehr.service.ShiftVersionResolver shiftVersionResolver;
    @Mock private com.nforce.onehr.service.ShiftWeeklyOffRulesService shiftWeeklyOffRulesService;
    @Mock private WeeklyOffPolicyRepository weeklyOffPolicyRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private HolidayRepository holidayRepo;
    @Mock private AssetRepository assetRepo;
    @Mock private com.nforce.onehr.repository.AttendanceRepository attendanceRepo;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(businessUnitRepo, departmentRepo, designationRepo, locationRepo, shiftRepo, shiftVersionRepository, shiftVersionResolver, shiftWeeklyOffRulesService, weeklyOffPolicyRepo, employeeRepo, historyRepo, holidayRepo, assetRepo, attendanceRepo);
        lenient().when(locationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepo.countByLocationId(any())).thenReturn(0L);
    }

    private CreateLocationRequest createReq(String name, String timezone) {
        CreateLocationRequest req = new CreateLocationRequest();
        req.setName(name);
        req.setTimezone(timezone);
        return req;
    }

    @ParameterizedTest
    @CsvSource({
            "hyderabad, Hyderabad",
            "HYDERABAD, Hyderabad",
            "hYdErAbAd, Hyderabad",
            "'new york', 'New York'",
            "'NEW YORK', 'New York'",
            "'nEW yORK', 'New York'",
            "Hyderabad, Hyderabad",
            "'hyderabad city office', 'Hyderabad City Office'"
    })
    void createLocation_normalizesNameCasing(String input, String expected) {
        LocationResponse response = service.createLocation(createReq(input, "Asia/Kolkata"));

        assertEquals(expected, response.getName());
        ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(locationRepo).save(captor.capture());
        assertEquals(expected, captor.getValue().getName());
    }

    @Test
    void createLocation_acceptsAnySupportedTimezone() {
        for (String tz : OrgService.SUPPORTED_TIMEZONES) {
            reset(locationRepo);
            lenient().when(locationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            LocationResponse response = service.createLocation(createReq("Austin", tz));

            assertEquals(tz, response.getTimezone());
        }
    }

    @Test
    void createLocation_rejectsATimezoneNotInTheSupportedSet() {
        // A real, validly-parseable IANA zone (Europe/London) that just isn't one of the four
        // this business currently supports — must still be rejected, not merely "invalid syntax".
        CreateLocationRequest req = createReq("Austin", "Europe/London");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createLocation(req));
        assertTrue(ex.getMessage().contains("Europe/London"));
        assertTrue(ex.getMessage().contains("Asia/Kolkata"), "should list the supported options");
        verify(locationRepo, never()).save(any());
    }

    @Test
    void createLocation_rejectsAMalformedTimezone() {
        CreateLocationRequest req = createReq("Austin", "Not/A_Real_Zone");

        assertThrows(IllegalArgumentException.class, () -> service.createLocation(req));
        verify(locationRepo, never()).save(any());
    }

    @Test
    void updateLocation_normalizesNameCasing() {
        UUID id = UUID.randomUUID();
        Location existing = Location.builder().id(id).name("Hyderabad").timezone("Asia/Kolkata").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("nEW yORK");
        req.setTimezone("America/New_York");

        LocationResponse response = service.updateLocation(id, req);

        assertEquals("New York", response.getName());
        assertEquals("New York", existing.getName());
    }

    @Test
    void updateLocation_rejectsATimezoneNotInTheSupportedSet() {
        UUID id = UUID.randomUUID();
        // Simulates correcting a wrongly-configured Location (e.g. the real "Texas" ->
        // America/New_York data bug fixed by V169) — but only onto a SUPPORTED zone.
        Location existing = Location.builder().id(id).name("Texas").timezone("America/New_York").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("Texas");
        req.setTimezone("Australia/Sydney");

        assertThrows(IllegalArgumentException.class, () -> service.updateLocation(id, req));
        assertEquals("America/New_York", existing.getTimezone(), "must not be mutated on a rejected update");
        verify(locationRepo, never()).save(any());
    }

    @Test
    void createLocation_duplicateCheckIsCaseInsensitiveAgainstNormalizedName() {
        when(locationRepo.existsByNameIgnoreCase("New York")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createLocation(createReq("nEW yORK", "America/New_York")));
        verify(locationRepo, never()).save(any());
    }

    @Test
    void updateLocation_duplicateCheckIsCaseInsensitiveAgainstNormalizedName() {
        UUID id = UUID.randomUUID();
        Location existing = Location.builder().id(id).name("Hyderabad").timezone("Asia/Kolkata").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));
        when(locationRepo.existsByNameIgnoreCase("New York")).thenReturn(true);

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("nEW yORK");
        req.setTimezone("America/New_York");

        assertThrows(IllegalArgumentException.class, () -> service.updateLocation(id, req));
        verify(locationRepo, never()).save(any());
    }

    @Test
    void updateLocation_allowsSavingSameNameRegardlessOfCasing() {
        UUID id = UUID.randomUUID();
        Location existing = Location.builder().id(id).name("Hyderabad").timezone("Asia/Kolkata").build();
        when(locationRepo.findById(id)).thenReturn(Optional.of(existing));

        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setName("HYDERABAD");
        req.setTimezone("Asia/Kolkata");

        LocationResponse response = service.updateLocation(id, req);

        assertEquals("Hyderabad", response.getName());
        verify(locationRepo, never()).existsByNameIgnoreCase(anyString());
    }

    /**
     * listLocations() must batch employee counts via one GROUP BY query (countGroupedByLocationId)
     * rather than one countByLocationId call per row — this test locks in both the batching
     * itself (never() on the per-row count) and that each location still ends up with the
     * correct count from the grouped result, including a location with zero employees getting 0
     * rather than a missing/null entry.
     */
    @Test
    void listLocations_usesBatchedGroupedCount_notOnePerRowCountQuery() {
        UUID hyderabadId = UUID.randomUUID();
        UUID bangaloreId = UUID.randomUUID();
        Location hyderabad = Location.builder().id(hyderabadId).name("Hyderabad").build();
        Location bangalore = Location.builder().id(bangaloreId).name("Bangalore").build();
        when(locationRepo.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(java.util.List.of(hyderabad, bangalore));
        when(employeeRepo.countGroupedByLocationId())
                .thenReturn(java.util.List.<Object[]>of(new Object[]{hyderabadId, 5L}));

        java.util.List<LocationResponse> result = service.listLocations();

        assertEquals(5L, result.stream().filter(r -> r.getId().equals(hyderabadId)).findFirst().orElseThrow().getEmployeeCount());
        // Bangalore has no entry in the grouped result (zero employees) — must default to 0, not null/missing.
        assertEquals(0L, result.stream().filter(r -> r.getId().equals(bangaloreId)).findFirst().orElseThrow().getEmployeeCount());
        verify(employeeRepo, never()).countByLocationId(any());
    }
}
