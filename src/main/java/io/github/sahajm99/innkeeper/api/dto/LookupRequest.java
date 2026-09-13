package io.github.sahajm99.innkeeper.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The pair a guest was given when they booked. Both are required and both are checked together:
 * the endpoint answers the same way to a wrong email and to a code that does not exist, so it
 * cannot be used to find out which codes do.
 */
public record LookupRequest(
    @NotBlank @Pattern(regexp = "INN-[A-Z2-9]{6}") String code,
    @NotBlank @Email String email) {
}
