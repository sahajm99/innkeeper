package io.github.sahajm99.innkeeper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.service.RaceDemoService;
import io.github.sahajm99.innkeeper.service.ResetService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The race, run through the endpoint the home page calls.
 *
 * <p>Nine of the ten attempts lose on the unique index, and Hibernate reports every one of them
 * through {@code SqlExceptionHelper} before the service turns it into a refusal. That logger is off
 * here so the expected noise does not read like a failure; the assertions are what report a real
 * one. The winner is cancelled by the service itself, and the demo data is put back afterwards
 * because this database is shared with the tests that count the seed.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
    "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=off")
class DemoApiTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingRepository bookings;
    @Autowired ResetService reset;
    @Autowired Clock clock;

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    void tenSimultaneousBookingsLeaveExactlyOneWinnerAndItIsCancelledAgain() throws Exception {
        JsonNode race = objectMapper.readTree(mockMvc.perform(post("/api/demo/race"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attempts").value(RaceDemoService.RACERS))
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.rejected").value(RaceDemoService.RACERS - 1))
            .andReturn().getResponse().getContentAsString());

        String winner = race.get("winnerCode").asText();
        assertThat(winner).matches("INN-[A-Z2-9]{6}");
        assertThat(race.get("room").asText()).contains("Denton Square");
        assertThat(LocalDate.parse(race.get("night").asText()))
            .isAfterOrEqualTo(BranchDates.today(clock, ZONE).plusDays(RaceDemoService.DAYS_AHEAD));
        assertThat(race.get("durationMs").asLong()).isNotNegative();

        Booking booked = bookings.findByConfirmationCode(winner).orElseThrow();
        assertThat(booked.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void theRaceLeavesTheNightFreeSoItCanBeRunAgain() throws Exception {
        mockMvc.perform(post("/api/demo/race"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1));

        mockMvc.perform(post("/api/demo/race"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.rejected").value(RaceDemoService.RACERS - 1));
    }
}
