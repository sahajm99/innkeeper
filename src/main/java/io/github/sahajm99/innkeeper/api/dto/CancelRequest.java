package io.github.sahajm99.innkeeper.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Cancelling needs the email the booking was made with; the code is in the path. */
public record CancelRequest(@NotBlank @Email String email) {
}
