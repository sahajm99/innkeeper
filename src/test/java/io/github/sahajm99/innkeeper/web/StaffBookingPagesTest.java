package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.ParkingKind;
import io.github.sahajm99.innkeeper.model.ParkingSpace;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.InvoiceRepository;
import io.github.sahajm99.innkeeper.repository.ParkingSpaceRepository;
import io.github.sahajm99.innkeeper.service.InvoiceService;
import io.github.sahajm99.innkeeper.service.ResetService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The booking desk.
 *
 * <p>Every test here drives a stay through a transition and then reads the page back, because the
 * thing worth protecting is not that the service works - the service tests say that - but that the
 * page offers exactly the actions the status allows, and that a refused one comes back as a
 * sentence on the same page rather than as an error.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffBookingPagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired BookingRepository bookings;
    @Autowired ParkingSpaceRepository parkingSpaces;
    @Autowired InvoiceRepository invoices;
    @Autowired InvoiceService invoiceService;
    @Autowired ResetService reset;

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void searchingForAGuestListsTheirStay() throws Exception {
        String html = mockMvc.perform(get("/staff/bookings").param("q", "Otto"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Otto Stub").contains(codeOf("otto.stub@example.com"));
        assertThat(html).contains("o***@example.com").doesNotContain("otto.stub@example.com");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aSearchThatFindsNothingSaysSo() throws Exception {
        String html = mockMvc.perform(get("/staff/bookings").param("q", "Nobody At All"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No bookings match.");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void anArrivalIsOfferedCheckInWithNoParkingChosen() throws Exception {
        String html = mockMvc.perform(get("/staff/bookings/" + codeOf("otto.stub@example.com")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Check in").contains("No parking");
        assertThat(html).doesNotContain("Record payment");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void checkingInWithASpaceShowsTheChipAndTheSpace() throws Exception {
        String code = codeOf("otto.stub@example.com");
        ParkingSpace space = freeSpaceAt("otto.stub@example.com");

        mockMvc.perform(post("/staff/bookings/" + code + "/check-in")
                .param("parkingSpaceId", String.valueOf(space.getId())).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/staff/bookings/" + code));

        String html = mockMvc.perform(get("/staff/bookings/" + code))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Checked in").contains("Parking space " + space.getSpaceNumber());
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void checkingOutOffersAPaymentPrefilledWithTheBalanceAndSettlesIt() throws Exception {
        String code = codeOf("kai.specimen@example.com");

        mockMvc.perform(post("/staff/bookings/" + code + "/check-out").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Checked out")));

        String afterCheckOut = mockMvc.perform(get("/staff/bookings/" + code))
            .andReturn().getResponse().getContentAsString();
        assertThat(afterCheckOut).contains("Record payment");

        String balance = balanceOf("kai.specimen@example.com");
        assertThat(afterCheckOut).contains("value=\"" + balance + "\"");

        mockMvc.perform(post("/staff/bookings/" + code + "/payments")
                .param("amount", balance).param("method", "CARD").param("reference", "4417")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Payment recorded")));

        String settled = mockMvc.perform(get("/staff/bookings/" + code))
            .andReturn().getResponse().getContentAsString();
        assertThat(settled).contains(">Paid<");
        assertThat(settled).doesNotContain("Record payment");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aFineOnAStayThatIsOverIsRefusedWithASentence() throws Exception {
        String code = codeOf("vic.testcase@example.com");

        mockMvc.perform(post("/staff/bookings/" + code + "/fines")
                .param("reason", "Broken lamp").param("amount", "20.00").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/staff/bookings/" + code))
            .andExpect(flash().attribute("error",
                containsString("only be added while the stay is booked")));
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void checkingInAGuestWhoHasAlreadyLeftIsAFlashRatherThanAnError() throws Exception {
        String code = codeOf("vic.testcase@example.com");

        mockMvc.perform(post("/staff/bookings/" + code + "/check-in").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void anUnknownCodeIsTheDesignedNotFoundPage() throws Exception {
        String html = mockMvc.perform(get("/staff/bookings/INN-NOPE99"))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Request id");
    }

    private String codeOf(String email) {
        return booking(email).getConfirmationCode();
    }

    /** The join-fetching query, so the room and the branch are readable outside a session. */
    private Booking booking(String email) {
        return bookings.findByGuestEmail(email).stream().findFirst().orElseThrow();
    }

    /** A guest space nobody is parked in at the branch the stay is at. */
    private ParkingSpace freeSpaceAt(String email) {
        Long branchId = booking(email).getRoom().getBranch().getId();
        return parkingSpaces
            .findByBranchIdAndBookingIsNullAndKindOrderBySpaceNumber(branchId, ParkingKind.GUEST)
            .stream().findFirst().orElseThrow();
    }

    /** What the stay still owes, written the way the number input renders it. */
    private String balanceOf(String email) {
        return invoices.findByBookingId(booking(email).getId())
            .map(invoiceService::balance)
            .map(BigDecimal::toPlainString)
            .orElseThrow();
    }
}
