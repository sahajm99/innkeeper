package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sahajm99.innkeeper.model.MaintenanceRequest;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.MaintenanceRequestRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
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
 * The maintenance board.
 *
 * <p>What the board has to get right is the order of the Open lane and the one refusal the desk
 * meets in practice: closing a room that somebody has already booked. Both are asserted against
 * the seeded house rather than against a fixture, because both depend on real room nights.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaintenancePagesTest {

    /** A Denton room with no future nights and no job of its own, so it can be closed. */
    private static final String FREE_ROOM = "204";

    /** Grace Example is in room 101 until tomorrow, so it cannot be closed. */
    private static final String SOLD_ROOM = "101";

    @Autowired MockMvc mockMvc;
    @Autowired RoomRepository rooms;
    @Autowired MaintenanceRequestRepository requests;
    @Autowired ResetService reset;

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void theUrgentJobIsAtTheTopOfTheOpenLane() throws Exception {
        String html = mockMvc.perform(get("/staff/maintenance"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Leaking bathroom faucet").contains("Lobby lamp flickers");
        assertThat(html.indexOf("Leaking bathroom faucet"))
            .isLessThan(html.indexOf("Lobby lamp flickers"));
        assertThat(html).contains("Open (").contains("In progress (").contains("Done (");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aRoomWithStaysOnItCannotBeTakenOutOfService() throws Exception {
        Room sold = room(SOLD_ROOM);

        mockMvc.perform(post("/staff/maintenance")
                .param("branchId", String.valueOf(sold.getBranch().getId()))
                .param("roomId", String.valueOf(sold.getId()))
                .param("title", "Dripping tap")
                .param("description", "The bathroom tap drips all night.")
                .param("priority", "NORMAL")
                .param("outOfService", "true")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("error", containsString("has upcoming stays")));
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void closingARoomListsItAndFinishingTheJobGivesItBack() throws Exception {
        Room free = room(FREE_ROOM);

        mockMvc.perform(post("/staff/maintenance")
                .param("branchId", String.valueOf(free.getBranch().getId()))
                .param("roomId", String.valueOf(free.getId()))
                .param("title", "Window will not close")
                .param("description", "The sash drops open again as soon as it is shut.")
                .param("priority", "URGENT")
                .param("outOfService", "true")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Request raised")));

        String closed = mockMvc.perform(get("/staff/maintenance"))
            .andReturn().getResponse().getContentAsString();
        assertThat(closed).contains("Out of service")
            .contains("Denton Square, room " + FREE_ROOM);

        MaintenanceRequest raised = requests.findAll().stream()
            .filter(request -> "Window will not close".equals(request.getTitle()))
            .findFirst().orElseThrow();

        mockMvc.perform(post("/staff/maintenance/" + raised.getId() + "/done").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Job finished")));

        String reopened = mockMvc.perform(get("/staff/maintenance"))
            .andReturn().getResponse().getContentAsString();
        assertThat(reopened).doesNotContain("Denton Square, room " + FREE_ROOM + "<");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void startingAJobMovesItOutOfTheOpenLane() throws Exception {
        MaintenanceRequest waiting = requests.findAll().stream()
            .filter(request -> "Leaking bathroom faucet".equals(request.getTitle()))
            .findFirst().orElseThrow();

        mockMvc.perform(post("/staff/maintenance/" + waiting.getId() + "/start").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Job started")));

        String html = mockMvc.perform(get("/staff/maintenance"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Open (0)");
        assertThat(html).contains("In progress (2)");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void theBoardOpensWithARoomAlreadyChosenWhenABookingSentItHere() throws Exception {
        Room free = room(FREE_ROOM);

        String html = mockMvc.perform(get("/staff/maintenance")
                .param("roomId", String.valueOf(free.getId())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("value=\"" + free.getId() + "\" selected=\"selected\"");
    }

    private Room room(String number) {
        return rooms.findByBranchCodeAndRoomNumber("DEN", number)
            .flatMap(found -> rooms.findDetailed(found.getId()))
            .orElseThrow();
    }
}
