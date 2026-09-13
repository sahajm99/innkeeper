package io.github.sahajm99.innkeeper.api.dto;

import java.time.LocalDate;

import io.github.sahajm99.innkeeper.service.AvailabilityService;

/** One night of the availability strip: the date, and whether the room is still free that night. */
public record NightDto(LocalDate date, boolean available) {

    public static NightDto of(AvailabilityService.Night night) {
        return new NightDto(night.date(), night.available());
    }
}
