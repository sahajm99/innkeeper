package io.github.sahajm99.innkeeper.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The calendar strip for one room. {@code days} is the window actually answered, which is not
 * always the window asked for: the API clamps a request for more than ninety nights.
 */
public record RoomAvailabilityDto(Long roomId, LocalDate from, int days, List<NightDto> nights) {
}
