package io.github.sahajm99.innkeeper.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.sahajm99.innkeeper.config.InnkeeperProperties;
import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.StayPeriod;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.repository.RoomTypeRepository;
import io.github.sahajm99.innkeeper.service.AvailabilityService;
import io.github.sahajm99.innkeeper.web.form.SearchForm;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The three pages a visitor sees before deciding: the front desk, the rooms that are free, and one
 * room with its register.
 *
 * <p>All three share one form object, because the dates and the party size are the thread through
 * the whole journey: they arrive as a query string, they are checked once here, and every link the
 * page draws carries them onward so that nobody has to type them twice.</p>
 */
@Controller
public class PublicPagesController {

    /** How far ahead the strip on a room page looks; the same window the API defaults to. */
    public static final int STRIP_DAYS = AvailabilityService.DEFAULT_DAYS;

    /** Weeks start on Sunday, which is how a Texas wall calendar is printed. */
    private static final DayOfWeek WEEK_START = DayOfWeek.SUNDAY;

    private static final int WEEK_LENGTH = 7;

    /** One hotel on the front page: what it is called, where it is and what it starts at. */
    public record BranchCard(Long id, String code, String name, String city, String tagline,
        BigDecimal fromRate) {
    }

    /** One row of the rooms ledger. {@code stayTotal} is null until the visitor picks dates. */
    public record RoomRow(Long id, String roomNumber, String typeName, String bedSetup, int sleeps,
        BigDecimal rate, BigDecimal stayTotal) {
    }

    /** A branch and what it has free; an empty list is the "sold out for these dates" line. */
    public record BranchRooms(Long branchId, String branchName, String city, List<RoomRow> rooms) {

        public boolean soldOut() {
            return rooms.isEmpty();
        }
    }

    /** One cell of the register: a date, whether it is free, and whether it is today. */
    public record StripNight(LocalDate date, boolean available, boolean today, boolean monthStart,
        String monthLabel, String dayLabel) {
    }

    private final BranchRepository branches;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final AvailabilityService availability;
    private final Clock clock;
    private final Dates dates;
    private final ZoneId appZone;

    public PublicPagesController(BranchRepository branches, RoomRepository rooms,
            RoomTypeRepository roomTypes, AvailabilityService availability, Clock clock,
            Dates dates, InnkeeperProperties properties) {
        this.branches = branches;
        this.rooms = rooms;
        this.roomTypes = roomTypes;
        this.availability = availability;
        this.clock = clock;
        this.dates = dates;
        this.appZone = properties.timezone() == null
            ? ZoneId.systemDefault()
            : ZoneId.of(properties.timezone());
    }

    // --- the front desk -----------------------------------------------------------------------

    @GetMapping("/")
    public String landing(@Valid @ModelAttribute("search") SearchForm search, BindingResult binding,
            Model model) {
        checkDates(search, binding);
        model.addAttribute("branchCards", branchCards());
        model.addAttribute("branchList", branches.findAllByOrderByName());
        model.addAttribute("roomTypes", roomTypes.findAllByOrderBySortOrder());
        addSearchContext(model, today(), search);
        return "index";
    }

    // --- what is free -------------------------------------------------------------------------

    @GetMapping("/rooms")
    public String roomsPage(@Valid @ModelAttribute("search") SearchForm search,
            BindingResult binding, Model model) {
        checkDates(search, binding);
        addSearchContext(model, today(), search);
        model.addAttribute("branchList", branches.findAllByOrderByName());
        model.addAttribute("roomTypes", roomTypes.findAllByOrderBySortOrder());

        if (binding.hasErrors()) {
            model.addAttribute("results", List.of());
            return "rooms";
        }

        List<Room> free = availability.findAvailable(new AvailabilityService.Query(
            search.getBranchId(), search.getType(), search.getMaxRate(), search.getGuests(),
            search.getCheckIn(), search.getCheckOut()));
        long nights = search.hasDates()
            ? ChronoUnit.DAYS.between(search.getCheckIn(), search.getCheckOut())
            : 0;

        List<BranchRooms> results = new ArrayList<>();
        for (Branch branch : branches.findAllByOrderByName()) {
            if (search.getBranchId() != null && !search.getBranchId().equals(branch.getId())) {
                continue;
            }
            List<RoomRow> rows = free.stream()
                .filter(room -> room.getBranch().getId().equals(branch.getId()))
                .map(room -> row(room, nights))
                .toList();
            results.add(new BranchRooms(branch.getId(), branch.getName(), branch.getCity(), rows));
        }

        model.addAttribute("results", results);
        model.addAttribute("nights", nights);
        model.addAttribute("anyRooms", !free.isEmpty());
        return "rooms";
    }

    // --- one room, and its register -----------------------------------------------------------

    @GetMapping("/rooms/{id}")
    public String roomPage(@PathVariable("id") Long id,
            @Valid @ModelAttribute("search") SearchForm search, BindingResult binding, Model model) {
        Room room = rooms.findDetailed(id)
            .orElseThrow(() -> new NotFoundException("No room with id " + id));
        LocalDate today = BranchDates.today(clock, room.getBranch().zone());
        checkDates(search, binding);

        List<AvailabilityService.Night> nights = availability.strip(room, today, STRIP_DAYS);
        model.addAttribute("room", room);
        model.addAttribute("branch", room.getBranch());
        model.addAttribute("type", room.getRoomType());
        model.addAttribute("weeks", weeks(nights, today));
        model.addAttribute("stripFrom", today);
        model.addAttribute("stripTo", today.plusDays(STRIP_DAYS - 1L));
        addSearchContext(model, today, search);

        boolean hasDates = !binding.hasErrors() && search.hasDates();
        long stayNights = hasDates
            ? ChronoUnit.DAYS.between(search.getCheckIn(), search.getCheckOut())
            : 0;
        boolean free = hasDates && isFree(nights, search.getCheckIn(), search.getCheckOut());
        BigDecimal total = free
            ? room.getNightlyRate().multiply(BigDecimal.valueOf(stayNights))
            : null;
        LocalDate opening = hasDates && !free
            ? availability.nextOpening(room, today, (int) stayNights, STRIP_DAYS).orElse(null)
            : null;
        model.addAttribute("requestedNights", stayNights);
        model.addAttribute("requestedFree", free);
        model.addAttribute("requestedTotal", total);
        model.addAttribute("nextOpening", opening);
        model.addAttribute("blockedMessage",
            hasDates && !free ? blocked(search, stayNights, opening) : null);
        model.addAttribute("stripStatus", free
            ? dates.nights(stayNights) + ", " + dates.money(total) + " before tax."
            : "Pick your first night.");
        return "room";
    }

    /**
     * The sentence a blocked request gets: what is not free, and when the same length of stay next
     * opens up - because "no" without "then when" leaves the visitor nowhere to go.
     */
    private String blocked(SearchForm search, long stayNights, LocalDate opening) {
        String refusal = "Not available " + dates.dayMonth(search.getCheckIn()) + " to "
            + dates.dayMonth(search.getCheckOut()) + ".";
        return opening == null
            ? refusal + " Nothing that long opens up in the next " + STRIP_DAYS + " days."
            : refusal + " Next " + stayNights + "-night opening: " + dates.dayMonth(opening) + ".";
    }

    // --- shared -------------------------------------------------------------------------------

    /**
     * Adds the model attributes every search form needs: today, so the date inputs refuse the past,
     * and the far end of the booking window.
     */
    private void addSearchContext(Model model, LocalDate today, SearchForm search) {
        model.addAttribute("today", today);
        model.addAttribute("latestDate", today.plusDays(StayPeriod.MAX_DAYS_AHEAD));
        model.addAttribute("carry", carry(search));
    }

    /**
     * The dates and the party size as a query string, so every link a page draws can hand them on
     * and nobody has to type them twice. Dates that were not given are left out rather than sent
     * empty.
     */
    private String carry(SearchForm search) {
        StringBuilder query = new StringBuilder("guests=").append(search.getGuests());
        if (search.getCheckIn() != null) {
            query.append("&checkIn=").append(search.getCheckIn());
        }
        if (search.getCheckOut() != null) {
            query.append("&checkOut=").append(search.getCheckOut());
        }
        return query.toString();
    }

    /**
     * Applies the stay rules to whatever dates were typed, so the search form refuses a backwards
     * range for the same reason and with the same sentence the booking form does.
     */
    private void checkDates(SearchForm search, BindingResult binding) {
        if (binding.hasFieldErrors("checkIn") || binding.hasFieldErrors("checkOut")
                || !search.hasDates()) {
            return;
        }
        try {
            StayPeriod.validated(search.getCheckIn(), search.getCheckOut(), today());
        } catch (BookingRuleException broken) {
            binding.rejectValue(broken.field(), "stay.invalid", broken.getMessage());
        }
    }

    private LocalDate today() {
        return BranchDates.today(clock, appZone);
    }

    private List<BranchCard> branchCards() {
        return branches.findAllByOrderByName().stream()
            .map(branch -> new BranchCard(branch.getId(), branch.getCode(), branch.getName(),
                branch.getCity(), branch.getTagline(), rooms.minimumRate(branch.getId())))
            .toList();
    }

    private RoomRow row(Room room, long nights) {
        RoomType type = room.getRoomType();
        return new RoomRow(room.getId(), room.getRoomNumber(), type.getName(), type.getBedSetup(),
            type.getMaxOccupancy(), room.getNightlyRate(),
            nights > 0 ? room.getNightlyRate().multiply(BigDecimal.valueOf(nights)) : null);
    }

    /** True when every night of the stay is still free. */
    private boolean isFree(List<AvailabilityService.Night> strip, LocalDate from, LocalDate to) {
        List<LocalDate> wanted = from.datesUntil(to).toList();
        return wanted.stream().allMatch(night -> strip.stream()
            .anyMatch(cell -> cell.date().equals(night) && cell.available()));
    }

    /**
     * The register, laid out as a wall calendar: rows of seven starting on a Sunday, with empty
     * cells before the first night and after the last so the columns stay under their weekday.
     */
    private List<List<StripNight>> weeks(List<AvailabilityService.Night> nights, LocalDate today) {
        if (nights.isEmpty()) {
            return List.of();
        }
        LocalDate first = nights.get(0).date();
        int lead = (int) ChronoUnit.DAYS.between(
            first.with(TemporalAdjusters.previousOrSame(WEEK_START)), first);

        List<StripNight> cells = new ArrayList<>();
        for (int pad = 0; pad < lead; pad++) {
            cells.add(null);
        }
        for (AvailabilityService.Night night : nights) {
            cells.add(cell(night, today));
        }
        while (cells.size() % WEEK_LENGTH != 0) {
            cells.add(null);
        }

        List<List<StripNight>> weeks = new ArrayList<>();
        for (int start = 0; start < cells.size(); start += WEEK_LENGTH) {
            weeks.add(new ArrayList<>(cells.subList(start, start + WEEK_LENGTH)));
        }
        return weeks;
    }

    private StripNight cell(AvailabilityService.Night night, LocalDate today) {
        LocalDate date = night.date();
        return new StripNight(date, night.available(), date.equals(today),
            date.getDayOfMonth() == 1 || date.equals(today),
            date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
            String.valueOf(date.getDayOfMonth()));
    }
}
