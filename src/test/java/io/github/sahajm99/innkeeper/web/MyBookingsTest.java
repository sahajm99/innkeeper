package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.seed.DemoAccounts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Getting back into a booking.
 *
 * <p>The two ways in are tested together because they answer the same question with different
 * proof: a code plus the email it was made with, or a signed-in guest account whose display name is
 * that email. Everything else is refused with one sentence that says nothing about which half was
 * wrong.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyBookingsTest {

    @Autowired MockMvc mockMvc;
    @Autowired BookingRepository bookings;
    @Autowired UserDetailsService accounts;

    private Booking seeded;

    @BeforeEach
    void findASeededBooking() {
        List<Booking> mine = bookings.findByGuestEmail(DemoAccounts.GUEST.displayName());
        assertThat(mine).isNotEmpty();
        seeded = mine.get(0);
    }

    @Test
    void theFormAsksForBothHalvesAndSaysWhyBothAreNeeded() throws Exception {
        String html = mockMvc.perform(get("/my-bookings"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("name=\"code\"").contains("name=\"email\"");
        assertThat(html).contains("Open my booking");
    }

    @Test
    void aCodeInTheQueryStringPrefillsTheField() throws Exception {
        String html = mockMvc.perform(get("/my-bookings").param("code", "INN-4KQ7ZD"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("value=\"INN-4KQ7ZD\"");
    }

    @Test
    void aCodeWithTheWrongEmailGetsTheOneAnswerThatTellsNothing() throws Exception {
        String html = mockMvc.perform(post("/my-bookings").with(csrf())
                .param("code", seeded.getConfirmationCode())
                .param("email", "somebody.else@example.com"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(plain(html)).contains("We couldn't find a booking with that code and email.");
    }

    @Test
    void aCodeThatIsNotShapedLikeOneIsAFieldError() throws Exception {
        String html = mockMvc.perform(post("/my-bookings").with(csrf())
                .param("code", "banana")
                .param("email", "ada@example.com"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("A confirmation code looks like INN-4KQ7ZD");
    }

    @Test
    void therightPairUnlocksTheBookingAndOpensIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String code = seeded.getConfirmationCode();

        mockMvc.perform(post("/my-bookings").session(session).with(csrf())
                .param("code", code.toLowerCase())
                .param("email", DemoAccounts.GUEST.displayName()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bookings/" + code));

        String html = mockMvc.perform(get("/bookings/" + code).session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(code).contains(DemoAccounts.GUEST.displayName());
    }

    @Test
    void aSignedInGuestSeesEveryStayBookedWithTheAccountEmail() throws Exception {
        UserDetails guest = accounts.loadUserByUsername(DemoAccounts.GUEST.username());

        String html = mockMvc.perform(get("/my-bookings").with(user(guest)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(DemoAccounts.GUEST.displayName());
        assertThat(bookings.findByGuestEmail(DemoAccounts.GUEST.displayName())).hasSize(2);
        for (Booking stay : bookings.findByGuestEmail(DemoAccounts.GUEST.displayName())) {
            assertThat(html).contains(stay.getConfirmationCode());
        }
    }

    @Test
    void aSignedInGuestCanOpenTheirOwnBookingWithoutTheForm() throws Exception {
        UserDetails guest = accounts.loadUserByUsername(DemoAccounts.GUEST.username());

        String html = mockMvc.perform(
                get("/bookings/" + seeded.getConfirmationCode()).with(user(guest)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(seeded.getConfirmationCode());
    }

    @Test
    void aCodeNobodyProvedIsSentBackToTheForm() throws Exception {
        mockMvc.perform(get("/bookings/" + seeded.getConfirmationCode()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-bookings?code=" + seeded.getConfirmationCode()));
    }

    @Test
    void aCodeThatNoLongerExistsIsAnsweredTheSameWay() throws Exception {
        mockMvc.perform(get("/bookings/INN-ZZZZZZ"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-bookings?code=INN-ZZZZZZ"));
    }

    /** Thymeleaf escapes an apostrophe, so a sentence carrying one is compared after decoding. */
    private static String plain(String html) {
        return html.replace("&#39;", "'");
    }
}
