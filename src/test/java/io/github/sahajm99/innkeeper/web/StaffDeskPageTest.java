package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sahajm99.innkeeper.repository.BranchRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The first screen of a shift.
 *
 * <p>Two things are asserted that no service test can see: that the page is ordered the way the
 * morning is - who is leaving before who is arriving - and that nothing on it prints a guest's
 * address in full. The second is checked with a pattern rather than a sample, because the way to
 * leak an address is to add a field nobody thought to mask.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffDeskPageTest {

    /** Anything that still looks like a whole local part in front of the demo domain. */
    private static final String UNMASKED_EMAIL = "[A-Za-z0-9._%+-]{2,}@example\\.com";

    @Autowired MockMvc mockMvc;
    @Autowired BranchRepository branches;

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aClerkSeesTheirOwnBranchDeparturesBeforeArrivals() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Departures today (2)").contains("Arrivals today (1)");
        assertThat(html.indexOf("Departures today"))
            .isLessThan(html.indexOf("Arrivals today"));
        assertThat(html).contains("Denton Square");
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void aManagerSeesEveryBranch() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Departures today (4)").contains("Arrivals today (3)");
        assertThat(html).contains("Denton Square").contains("Fort Worth Stockyards")
            .contains("Austin Lakeline");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void theDentonOccupancyLineCountsRoomsInService() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Rooms occupied").containsPattern("\\d+ of \\d+ in service, \\d+%");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void lowStockIsNamedRatherThanCounted() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Low stock").contains("Toilet paper");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void everyGuestAddressOnTheDeskIsMasked() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("k***@example.com");
        assertThat(html).doesNotContainPattern(UNMASKED_EMAIL);
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void theBranchSwitcherNarrowsTheDeskToOneBranch() throws Exception {
        Long denton = branches.findAllByOrderByName().stream()
            .filter(branch -> "DEN".equals(branch.getCode()))
            .findFirst().orElseThrow().getId();

        String html = mockMvc.perform(get("/staff").param("branchId", String.valueOf(denton)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Departures today (2)").contains("Arrivals today (1)");
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void onlyAManagerIsOfferedTheReset() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Reset demo data");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aClerkIsNotOfferedTheResetAndCannotPostIt() throws Exception {
        String html = mockMvc.perform(get("/staff"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("Reset demo data");

        mockMvc.perform(post("/staff/reset").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void aVisitorWhoIsNotSignedInIsSentToTheLoginPage() throws Exception {
        mockMvc.perform(get("/staff"))
            .andExpect(status().is3xxRedirection());
    }
}
