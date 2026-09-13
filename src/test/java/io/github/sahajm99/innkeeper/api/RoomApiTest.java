package io.github.sahajm99.innkeeper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Searching for a room, reading one, and asking which of its nights are free. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomApiTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RoomRepository rooms;
    @Autowired BranchRepository branches;
    @Autowired Clock clock;

    @Test
    void aRoomHeldForThoseNightsIsNotOffered() throws Exception {
        Branch denton = branches.findByCode("DEN").orElseThrow();
        Room taken = rooms.findByBranchCodeAndRoomNumber("DEN", "105").orElseThrow();
        LocalDate checkIn = today().plusDays(40);

        JsonNode offered = readJson(get("/api/rooms")
            .param("branchId", String.valueOf(denton.getId()))
            .param("checkIn", checkIn.toString())
            .param("checkOut", checkIn.plusDays(2).toString()));

        assertThat(offered).isNotEmpty();
        assertThat(offered).allSatisfy(room ->
            assertThat(room.get("branchCode").asText()).isEqualTo("DEN"));
        assertThat(offered).noneSatisfy(room ->
            assertThat(room.get("id").asLong()).isEqualTo(taken.getId()));
    }

    @Test
    void aPartyOfFourIsOnlyOfferedSuites() throws Exception {
        JsonNode offered = readJson(get("/api/rooms").param("guests", "4"));

        assertThat(offered).isNotEmpty();
        assertThat(offered).allSatisfy(room -> {
            assertThat(room.get("type").get("code").asText()).isEqualTo("SUITE");
            assertThat(room.get("type").get("maxOccupancy").asInt()).isGreaterThanOrEqualTo(4);
        });
    }

    @Test
    void oneRoomCarriesItsBranchAndItsType() throws Exception {
        Room room = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();

        mockMvc.perform(get("/api/rooms/{id}", room.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roomNumber").value("101"))
            .andExpect(jsonPath("$.branchCode").value("DEN"))
            .andExpect(jsonPath("$.branchName").value("Denton Square"))
            .andExpect(jsonPath("$.floor").value(1))
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.type.code").value("STANDARD"))
            .andExpect(jsonPath("$.type.maxOccupancy").value(2));
    }

    @Test
    void aWindowLongerThanTheHorizonIsClampedToNinetyNights() throws Exception {
        Room room = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();

        mockMvc.perform(get("/api/rooms/{id}/availability", room.getId()).param("days", "200"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roomId").value(room.getId()))
            .andExpect(jsonPath("$.days").value(90))
            .andExpect(jsonPath("$.nights.length()").value(90))
            .andExpect(jsonPath("$.nights[0].date").value(today().toString()));
    }

    @Test
    void aWindowOfNothingIsARefusal() throws Exception {
        Room room = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();

        mockMvc.perform(get("/api/rooms/{id}/availability", room.getId()).param("days", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errors.days").isNotEmpty());
    }

    @Test
    void aRoomThatIsNotThereIsANotFoundProblem() throws Exception {
        mockMvc.perform(get("/api/rooms/{id}", 9_999_999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not found"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void theAvailabilityStripMarksTheNightsASeededStayHolds() throws Exception {
        Room booked = rooms.findByBranchCodeAndRoomNumber("DEN", "105").orElseThrow();
        LocalDate held = today().plusDays(40);

        JsonNode strip = objectMapper.readTree(
            mockMvc.perform(get("/api/rooms/{id}/availability", booked.getId())
                    .param("from", held.toString())
                    .param("days", "3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(strip.get("nights").get(0).get("available").asBoolean()).isFalse();
        assertThat(strip.get("nights").get(2).get("available").asBoolean()).isTrue();
    }

    private LocalDate today() {
        return BranchDates.today(clock, ZONE);
    }

    private JsonNode readJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }
}
