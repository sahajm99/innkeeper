package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;
import io.github.sahajm99.innkeeper.repository.ComplaintRepository;
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
 * The complaints queue.
 *
 * <p>Resolving is the only action here that can be got wrong, and it is got wrong by closing a
 * complaint without saying what was done about it. The queue is also the page most likely to leak
 * an address, since every row has one, so the mask is asserted here too.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffComplaintPagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired ComplaintRepository complaints;
    @Autowired ResetService reset;

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void theQueueListsWhatIsOpenWithTheAddressMasked() throws Exception {
        String html = mockMvc.perform(get("/staff/complaints"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Grace Example");
        assertThat(html).contains("g***@example.com").doesNotContain("grace.example@example.com");
        assertThat(html).contains("Start").contains("Resolve");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void closingAComplaintWithNoNoteIsRefused() throws Exception {
        Complaint open = open();

        mockMvc.perform(post("/staff/complaints/" + open.getId() + "/resolve").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("error", containsString("needs a note")));
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aResolvedComplaintLeavesTheQueue() throws Exception {
        Complaint open = open();
        String ticket = open.getTicketNumber();

        mockMvc.perform(post("/staff/complaints/" + open.getId() + "/resolve")
                .param("note", "Replaced the heater valve and moved the guest for one night.")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString(ticket)));

        String html = mockMvc.perform(get("/staff/complaints"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain(ticket);
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void startingAComplaintChangesItsChip() throws Exception {
        Complaint open = open();

        mockMvc.perform(post("/staff/complaints/" + open.getId() + "/start").with(csrf()))
            .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/staff/complaints"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("In progress");
    }

    private Complaint open() {
        return complaints.findByStatusInOrderByCreatedAtAsc(List.of(ComplaintStatus.OPEN))
            .stream().findFirst().orElseThrow();
    }
}
