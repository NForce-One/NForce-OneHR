package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateLocationRequest {

    @NotBlank(message = "Location name is required")
    @Size(max = 100, message = "Location name must be 100 characters or fewer")
    @Pattern(
            regexp = "^[A-Za-z]+( [A-Za-z]+)*$",
            message = "Location name must contain only letters (spaces allowed between words) — no numbers or special characters")
    private String name;

    @Size(max = 100) @Pattern(regexp = "^[^0-9]*$", message = "City cannot contain numbers") private String city;
    @Size(max = 100) @Pattern(regexp = "^[^0-9]*$", message = "State / Province cannot contain numbers") private String state;
    @Size(max = 100) @Pattern(regexp = "^[^0-9]*$", message = "Country cannot contain numbers") private String country;
    @Size(max = 100)
    @Pattern(regexp = "^([A-Za-z]{2})?$", message = "Region must contain exactly 2 letters (e.g. TN)")
    private String holidayRegion;

    // Required — every Location must have exactly one valid timezone. Must be one of
    // OrgService's fixed SUPPORTED_TIMEZONES, not just any IANA zone id — so Name/City/State/
    // Country stay freely editable (any number of locations can be added) while the one
    // attendance-relevant value here can never be an arbitrary or inconsistent one. See
    // OrgService#validatedTimezone.
    @NotBlank(message = "Timezone is required")
    @Size(max = 50)
    private String timezone;
}
