package io.github.sahajm99.innkeeper.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.api.dto.NightDto;
import io.github.sahajm99.innkeeper.api.dto.RoomAvailabilityDto;
import io.github.sahajm99.innkeeper.api.dto.RoomDto;
import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rooms, and when they are free.
 *
 * <p>There is no {@code /api/availability}: availability is a property of a room, so it hangs off
 * the room it belongs to. A search with dates answers with the rooms that can take the stay, which
 * is the same question the rooms page asks and the same service that answers it.</p>
 */
@RestController
@RequestMapping("/api/rooms")
@Tag(name = "Rooms", description = "Rooms and their availability")
public class RoomApiController {

    private final RoomRepository rooms;
    private final AvailabilityService availability;
    private final Clock clock;

    public RoomApiController(RoomRepository rooms, AvailabilityService availability, Clock clock) {
        this.rooms = rooms;
        this.availability = availability;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Rooms that can take the stay, or tonight when no dates are given")
    public List<RoomDto> rooms(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "maxRate", required = false) BigDecimal maxRate,
            @RequestParam(name = "checkIn", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam(name = "checkOut", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(name = "guests", defaultValue = "1") int guests) {
        AvailabilityService.Query query =
            new AvailabilityService.Query(branchId, type, maxRate, guests, checkIn, checkOut);
        return availability.findAvailable(query).stream().map(RoomDto::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "One room")
    public RoomDto room(@PathVariable("id") Long id) {
        return RoomDto.of(require(id));
    }

    /**
     * The next {@code days} nights of one room, from {@code from} or from today at that branch.
     *
     * <p>A window longer than the horizon is clamped rather than refused, because a client asking
     * for a year wants as much as there is; a window of nothing is a mistake worth reporting.</p>
     */
    @GetMapping("/{id}/availability")
    @Operation(summary = "Per-night availability, up to ninety nights")
    public RoomAvailabilityDto availability(
            @PathVariable("id") Long id,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "days", defaultValue = "60") int days) {
        if (days < AvailabilityService.MIN_DAYS) {
            throw new BookingRuleException("days",
                "Ask for at least " + AvailabilityService.MIN_DAYS + " night");
        }
        Room room = require(id);
        LocalDate start = from == null
            ? BranchDates.today(clock, room.getBranch().zone())
            : from;
        List<NightDto> nights = availability.strip(room, start, days).stream()
            .map(NightDto::of)
            .toList();
        return new RoomAvailabilityDto(room.getId(), start, nights.size(), nights);
    }

    private Room require(Long id) {
        return rooms.findDetailed(id)
            .orElseThrow(() -> new NotFoundException("No room with id " + id));
    }
}
