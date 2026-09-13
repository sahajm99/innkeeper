package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.BookingService;
import io.github.sahajm99.innkeeper.service.CreateBookingCommand;
import io.github.sahajm99.innkeeper.service.ResetService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The three pages a visitor reads before booking.
 *
 * <p>Every assertion here is about what a person sees - a sentence, a price, a hatched night - and
 * not about the model behind it, because these pages are the product and their copy is part of the
 * design.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicPagesTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    @Autowired MockMvc mockMvc;
    @Autowired RoomRepository rooms;
    @Autowired BookingService bookings;
    @Autowired ResetService reset;
    @Autowired Clock clock;

    private Room denton101;
    private LocalDate today;

    @BeforeEach
    void findARoom() {
        denton101 = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();
        today = BranchDates.today(clock, ZONE);
    }

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    // --- the front desk -----------------------------------------------------------------------

    @Test
    void theLandingPageOpensWithTheSearchAndTheThreeHotels() throws Exception {
        String html = page("/");

        assertThat(html).contains("Denton Square").contains("Fort Worth Stockyards")
            .contains("Austin Lakeline");
        assertThat(html).contains("Search rooms").contains("name=\"checkIn\"")
            .contains("min=\"" + today + "\"");
        assertThat(html).contains("Resets nightly at 03:00 Central");
        assertThat(html).contains("id=\"race-button\"").contains("Run the race");
    }

    @Test
    void eachHotelCardCarriesTheRateOfItsCheapestRoom() throws Exception {
        assertThat(page("/")).contains("$89.00");
    }

    @Test
    void aDateThatIsNotOneIsAFieldError() throws Exception {
        mockMvc.perform(get("/").param("checkIn", "bad"))
            .andExpect(status().isOk());

        assertThat(pageWithParams("/", "checkIn", "bad")).contains("Enter a date");
    }

    // --- what is free -------------------------------------------------------------------------

    @Test
    void theRoomsPageListsRoomsWithTheTotalForTheStay() throws Exception {
        String html = search(today.plusDays(5), today.plusDays(7), 2);

        assertThat(html).contains("2 nights").contains("$178.00").contains("View room");
    }

    @Test
    void aRoomAlreadyHeldForThoseDatesIsNotListed() throws Exception {
        LocalDate from = today.plusDays(5);
        LocalDate to = today.plusDays(7);
        assertThat(search(from, to, 2)).contains("/rooms/" + denton101.getId() + "?");

        bookings.create(new CreateBookingCommand(denton101.getId(), from, to, 2, 0, "Ada",
            "Example", "ada.example@example.com", null, null, "test"));

        assertThat(search(from, to, 2)).doesNotContain("/rooms/" + denton101.getId() + "?");
    }

    @Test
    void askingForMoreGuestsThanAnyRoomSleepsSaysSo() throws Exception {
        assertThat(pageWithParams("/rooms", "guests", "9"))
            .contains("The largest room here sleeps 4 guests");
    }

    @Test
    void nothingFreeSaysWhichDatesWereTriedAndWhatToDo() throws Exception {
        LocalDate from = today.plusDays(5);
        LocalDate to = today.plusDays(8);
        String html = mockMvc.perform(get("/rooms")
                .param("checkIn", from.toString())
                .param("checkOut", to.toString())
                .param("guests", "2")
                .param("maxRate", "1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Nothing free at any branch for")
            .contains("Try shorter dates or another branch.");
    }

    @Test
    void aBranchWithNothingFreeIsShownAsSoldOut() throws Exception {
        LocalDate from = today.plusDays(5);
        LocalDate to = today.plusDays(6);
        for (Room room : rooms.findByBranchIdOrderByRoomNumber(denton101.getBranch().getId())) {
            try {
                bookings.create(new CreateBookingCommand(room.getId(), from, to, 1, 0, "Ada",
                    "Example", "ada.example@example.com", null, null, "test"));
            } catch (RuntimeException alreadyBusy) {
                // an out-of-service room or one already held; either way it is not on sale
            }
        }

        assertThat(search(from, to, 1)).contains("Sold out for these dates.");
    }

    // --- one room -----------------------------------------------------------------------------

    @Test
    void theRoomPageDrawsSixtyNights() throws Exception {
        String html = page("/rooms/" + denton101.getId());

        assertThat(countOf(html, "class=\"night")).isEqualTo(60);
        assertThat(html).contains("role=\"grid\"").contains("Availability for the next 60 days");
    }

    @Test
    void aNightSomebodyElseHoldsIsMarkedBooked() throws Exception {
        String html = page("/rooms/" + denton101.getId());

        assertThat(html).contains("data-available=\"false\"");
        assertThat(html).contains("data-date=\"" + today + "\"");
        assertThat(html).contains(", booked\"");
    }

    @Test
    void theDatesAskedForArePreSelectedWithTheirTotal() throws Exception {
        LocalDate from = today.plusDays(5);
        LocalDate to = today.plusDays(7);
        String html = roomPage(from, to);

        assertThat(html).contains("value=\"" + from + "\"").contains("value=\"" + to + "\"");
        assertThat(html).contains("2 nights, $178.00 before tax.");
    }

    @Test
    void blockedDatesNameTheNextOpening() throws Exception {
        LocalDate from = today.plusDays(5);
        LocalDate to = today.plusDays(7);
        bookings.create(new CreateBookingCommand(denton101.getId(), from, to, 2, 0, "Ada",
            "Example", "ada.example@example.com", null, null, "test"));

        String html = roomPage(from, to);

        assertThat(html).contains("Not available").contains("2-night opening:");
    }

    @Test
    void aRoomThatDoesNotExistIsTheNotFoundPage() throws Exception {
        mockMvc.perform(get("/rooms/98765"))
            .andExpect(status().isNotFound());

        assertThat(mockMvc.perform(get("/rooms/98765")).andReturn().getResponse()
            .getContentAsString()).contains("No such page");
    }

    // --- the files the pages need ---------------------------------------------------------------

    @Test
    void theStripAndRaceScriptsAreServed() throws Exception {
        mockMvc.perform(get("/js/strip.js")).andExpect(status().isOk());
        mockMvc.perform(get("/js/race.js")).andExpect(status().isOk());
        mockMvc.perform(get("/js/print.js")).andExpect(status().isOk());
        mockMvc.perform(get("/js/focus-errors.js")).andExpect(status().isOk());
    }

    // --- helpers ------------------------------------------------------------------------------

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String pageWithParams(String path, String name, String value) throws Exception {
        return mockMvc.perform(get(path).param(name, value)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String search(LocalDate from, LocalDate to, int guests) throws Exception {
        return mockMvc.perform(get("/rooms")
                .param("checkIn", from.toString())
                .param("checkOut", to.toString())
                .param("guests", String.valueOf(guests)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String roomPage(LocalDate from, LocalDate to) throws Exception {
        return mockMvc.perform(get("/rooms/" + denton101.getId())
                .param("checkIn", from.toString())
                .param("checkOut", to.toString())
                .param("guests", "2"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private static int countOf(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
