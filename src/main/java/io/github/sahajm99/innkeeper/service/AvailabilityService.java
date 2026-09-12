package io.github.sahajm99.innkeeper.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.sahajm99.innkeeper.config.InnkeeperProperties;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.RoomNightRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is free, and when.
 *
 * <p>Three questions, one source of truth: the {@code room_night} rows. Searching answers "which
 * rooms can take this stay", the strip answers "which nights of this room are gone" for the
 * calendar on the room page, and the next opening answers "if not these dates, then when" so a
 * blocked search has somewhere to send the visitor.</p>
 */
@Service
public class AvailabilityService {

    /** The strip the room page draws is 60 nights; the API lets a caller ask for 1 to 90. */
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 90;
    public static final int DEFAULT_DAYS = 60;

    /** A search, with every filter but the party size optional. */
    public record Query(Long branchId, String typeCode, BigDecimal maxRate, int guests,
        LocalDate checkIn, LocalDate checkOut) {
    }

    /** One night of the strip: the date, and whether the room is still free that night. */
    public record Night(LocalDate date, boolean available) {
    }

    private final RoomRepository rooms;
    private final RoomNightRepository roomNights;
    private final BranchRepository branches;
    private final Clock clock;
    private final ZoneId appZone;

    public AvailabilityService(RoomRepository rooms, RoomNightRepository roomNights,
            BranchRepository branches, Clock clock, InnkeeperProperties properties) {
        this.rooms = rooms;
        this.roomNights = roomNights;
        this.branches = branches;
        this.clock = clock;
        this.appZone = properties.timezone() == null
            ? ZoneId.systemDefault()
            : ZoneId.of(properties.timezone());
    }

    /**
     * The rooms that can take the stay: in service, big enough for the party, matching the filters
     * and holding none of the nights asked for.
     *
     * <p>Missing dates are filled in rather than refused, because the rooms page is reachable
     * without them: no check-in means today, and no check-out means one night.</p>
     */
    @Transactional(readOnly = true)
    public List<Room> findAvailable(Query query) {
        LocalDate today = BranchDates.today(clock, zoneOf(query.branchId()));
        LocalDate checkIn = query.checkIn() == null ? today : query.checkIn();
        LocalDate checkOut = query.checkOut() == null || !query.checkOut().isAfter(checkIn)
            ? checkIn.plusDays(1)
            : query.checkOut();
        return rooms.findAvailable(query.branchId(), blankToNull(query.typeCode()), query.maxRate(),
            Math.max(1, query.guests()), checkIn, checkOut, today);
    }

    /**
     * One entry per night from {@code from}, for a window clamped to 1..90 nights. A room out of
     * service is unavailable on every one of them, whatever the room nights say, because it cannot
     * be offered at all.
     */
    @Transactional(readOnly = true)
    public List<Night> strip(Room room, LocalDate from, int days) {
        LocalDate end = from.plusDays(clamped(days));
        boolean inService = room.getStatus() == RoomStatus.AVAILABLE;
        Set<LocalDate> held = inService ? heldBetween(room, from, end) : Set.of();
        return from.datesUntil(end)
            .map(night -> new Night(night, inService && !held.contains(night)))
            .toList();
    }

    /**
     * The first date on or after {@code from} where the room is free for {@code nights} nights in
     * a row, with the whole run inside the horizon. Empty when the room is out of service, when
     * fewer than one night was asked for, or when no such run exists in the window.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> nextOpening(Room room, LocalDate from, int nights, int horizonDays) {
        if (nights < 1 || horizonDays < nights || room.getStatus() != RoomStatus.AVAILABLE) {
            return Optional.empty();
        }
        LocalDate horizon = from.plusDays(horizonDays);
        Set<LocalDate> held = heldBetween(room, from, horizon);
        for (LocalDate start = from; !start.plusDays(nights).isAfter(horizon);
                start = start.plusDays(1)) {
            if (start.datesUntil(start.plusDays(nights)).noneMatch(held::contains)) {
                return Optional.of(start);
            }
        }
        return Optional.empty();
    }

    private Set<LocalDate> heldBetween(Room room, LocalDate from, LocalDate to) {
        return new HashSet<>(roomNights.bookedNights(room.getId(), from, to));
    }

    private int clamped(int days) {
        return Math.min(MAX_DAYS, Math.max(MIN_DAYS, days));
    }

    /**
     * "Today" belongs to a branch, not to the server. A search of one branch uses that branch's
     * timezone; a search of all of them uses the first branch, falling back to the configured
     * application timezone when there are no branches at all.
     */
    private ZoneId zoneOf(Long branchId) {
        Optional<Branch> branch = branchId == null
            ? branches.findAllByOrderByName().stream().findFirst()
            : branches.findById(branchId);
        return branch.map(Branch::zone).orElse(appZone);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
