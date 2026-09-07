package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateLocationRequest {
    @NotBlank(message = "Name is required")
    @Size(max = 100)
    @Pattern(
            regexp = "^[A-Za-z]+( [A-Za-z]+)*$",
            message = "Name must contain only letters (spaces allowed between words) — no numbers or special characters")
    private String name;

    @Size(max = 100)
    @Pattern(regexp = "^[^0-9]*$", message = "City cannot contain numbers")
    private String city;

    @Size(max = 100)
    @Pattern(regexp = "^[^0-9]*$", message = "State / Province cannot contain numbers")
    private String state;

    @Size(max = 100)
    @Pattern(regexp = "^[^0-9]*$", message = "Country cannot contain numbers")
    private String country;

    @Size(max = 100)
    @Pattern(regexp = "^([A-Za-z]{2})?$", message = "Region must contain exactly 2 letters (e.g. TN)")
    private String holidayRegion;

    // Required — see CreateLocationRequest's identical field comment.
    @NotBlank(message = "Timezone is required")
    @Size(max = 50)
    private String timezone;
}
