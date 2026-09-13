package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.ResetService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole public journey: a summary, a form, a confirmation code, an invoice and a cancellation.
 *
 * <p>The session is the thing under test as much as the pages are. Booking unlocks the code in the
 * browser session, so the same URL opened in a different session has to send the visitor back to
 * the lookup form rather than show somebody else's stay.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingFlowTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final String EMAIL = "pages.guest@example.com";

    @Autowired MockMvc mockMvc;
    @Autowired RoomRepository rooms;
    @Autowired BookingRepository bookings;
    @Autowired ResetService reset;
    @Autowired Clock clock;

    private Room room;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void findAFreeRoom() {
        room = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();
        checkIn = BranchDates.today(clock, ZONE).plusDays(200);
        checkOut = checkIn.plusDays(2);
    }

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    // --- the form -----------------------------------------------------------------------------

    @Test
    void theBookingFormShowsTheStayItsPriceAndThePolicy() throws Exception {
        String html = mockMvc.perform(get("/book")
                .param("roomId", String.valueOf(room.getId()))
                .param("checkIn", checkIn.toString())
                .param("checkOut", checkOut.toString())
                .param("guests", "2"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("2 nights at $89.00").contains("$178.00");
        assertThat(html).contains("Confirm booking, $");
        assertThat(html).contains("Free cancellation until").contains("After that, one night");
        assertThat(html).contains("do not enter real personal data");
    }

    @Test
    void aNightFromTheRegisterBooksThatOneNight() throws Exception {
        String html = mockMvc.perform(get("/book")
                .param("roomId", String.valueOf(room.getId()))
                .param("night", checkIn.toString())
                .param("guests", "2"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("1 night at $89.00");
    }

    @Test
    void aMissingLastNameComesBackInlineWithTheSummaryIntact() throws Exception {
        String html = mockMvc.perform(bookingPost(Map.of("lastName", "")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Enter a last name");
        assertThat(html).contains("class=\"form-errors\"").contains("tabindex=\"-1\"");
        assertThat(html).contains("2 nights at $89.00");
        assertThat(bookings.findByGuestEmail(EMAIL)).isEmpty();
    }

    // --- booking ------------------------------------------------------------------------------

    @Test
    void bookingRedirectsToTheConfirmationAndTheSessionCanOpenIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String location = book(session);

        String html = mockMvc.perform(get(location).session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(location.substring("/bookings/".length()));
        assertThat(html).contains(EMAIL);
        assertThat(html).contains("Cancel booking");
        assertThat(html).contains("Keep this code and your email");
    }

    @Test
    void anotherBrowserIsSentToTheLookupForm() throws Exception {
        String location = book(new MockHttpSession());

        mockMvc.perform(get(location).session(new MockHttpSession()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-bookings?code="
                + location.substring("/bookings/".length())));
    }

    @Test
    void bookingTheSameRoomAndNightsTwiceSaysSomebodyGotThereFirst() throws Exception {
        book(new MockHttpSession());

        String html = mockMvc.perform(bookingPost())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("was just taken for those dates");
        assertThat(html).contains("See what else is free");
    }

    @Test
    void aFilledHoneypotBooksNothingAndSaysNothing() throws Exception {
        mockMvc.perform(bookingPost(Map.of("website", "https://example.com/spam")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        assertThat(bookings.findByGuestEmail(EMAIL)).isEmpty();
    }

    // --- cancelling ---------------------------------------------------------------------------

    @Test
    void theCancelStepSaysWhatItCostsBeforeAnythingHappens() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String location = book(session);

        String html = mockMvc.perform(get(location + "/cancel").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Cancelling now is free.");
        assertThat(html).contains("Keep booking").contains("Cancel booking");
    }

    @Test
    void cancellingRedirectsBackAndTheBookingReadsAsCancelled() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String location = book(session);

        mockMvc.perform(post(location + "/cancel").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(location));

        String html = mockMvc.perform(get(location).session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("chip-cancelled").contains("Cancelled");
        assertThat(html).contains("Booking cancelled");
        assertThat(html).doesNotContain("btn btn-danger");
    }

    // --- the invoice --------------------------------------------------------------------------

    @Test
    void theInvoiceListsEveryLineAndLoadsThePrintStylesheet() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String location = book(session);

        String html = mockMvc.perform(get(location + "/invoice").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/css/print.css");
        assertThat(html).contains("2 nights x $89.00").contains("Occupancy tax");
        assertThat(html).contains("Balance due");
        assertThat(html).contains("data-print");
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Books the room in this session and follows the redirect the way a browser would, so the
     * "Booked" flash is consumed here and a later page shows its own message rather than this one.
     */
    private String book(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(bookingPost().session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/bookings/INN-*"))
            .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        mockMvc.perform(get(location).session(session)).andExpect(status().isOk());
        return location;
    }

    private MockHttpServletRequestBuilder bookingPost() {
        return bookingPost(Map.of());
    }

    /**
     * The booking form as the browser would send it, with the named fields replaced. MockMvc
     * appends repeated parameters rather than replacing them, so an override has to go in before
     * the request is built or the server sees both values and binds the first.
     */
    private MockHttpServletRequestBuilder bookingPost(Map<String, String> overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("roomId", String.valueOf(room.getId()));
        fields.put("checkIn", checkIn.toString());
        fields.put("checkOut", checkOut.toString());
        fields.put("adults", "2");
        fields.put("children", "0");
        fields.put("firstName", "Ada");
        fields.put("lastName", "Example");
        fields.put("email", EMAIL);
        fields.put("phone", "");
        fields.put("specialRequests", "");
        fields.put("website", "");
        fields.putAll(overrides);

        MockHttpServletRequestBuilder request = post("/book").with(csrf());
        fields.forEach(request::param);
        return request;
    }
}
