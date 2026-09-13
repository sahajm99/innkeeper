package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.InventoryItemRepository;
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
 * The cupboard.
 *
 * <p>Three things matter on this page: that a shelf at or below its reorder level is marked, that
 * an adjustment says what the count now is, and that the one move the database refuses comes back
 * as a sentence instead of a stack trace.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryPagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired InventoryItemRepository items;
    @Autowired BranchRepository branches;
    @Autowired ResetService reset;

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aShelfBelowItsReorderLevelIsMarkedOnItsRow() throws Exception {
        String html = mockMvc.perform(get("/staff/inventory"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Toilet paper").contains("class=\"low\"").contains("Reorder");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void takingFiveOffTheShelfShowsTheNewCount() throws Exception {
        InventoryItem paper = item("Toilet paper");

        mockMvc.perform(post("/staff/inventory/" + paper.getId() + "/adjust")
                .param("delta", "-5").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("13 roll on the shelf")));

        String html = mockMvc.perform(get("/staff/inventory"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("13 roll");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aMoveThatWouldEmptyTheShelfPastZeroIsRefused() throws Exception {
        InventoryItem paper = item("Toilet paper");

        mockMvc.perform(post("/staff/inventory/" + paper.getId() + "/adjust")
                .param("delta", "1000").param("sign", "-1").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("error", containsString("cannot go below zero")));
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void anItemAddedToTheShelfIsListed() throws Exception {
        Long denton = denton();

        mockMvc.perform(post("/staff/inventory")
                .param("branchId", String.valueOf(denton))
                .param("name", "Ironing boards")
                .param("category", "Guest supplies")
                .param("quantity", "12")
                .param("reorderLevel", "4")
                .param("unit", "each")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Ironing boards added")));

        String html = mockMvc.perform(get("/staff/inventory"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Ironing boards").contains("12 each");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void anItemWithNoNameComesBackWithTheErrorOnTheField() throws Exception {
        Long denton = denton();

        String html = mockMvc.perform(post("/staff/inventory")
                .param("branchId", String.valueOf(denton))
                .param("name", "")
                .param("category", "Linen")
                .param("quantity", "3")
                .param("reorderLevel", "1")
                .param("unit", "each")
                .with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Name the item");
    }

    /** Every branch holds toilet paper; the one below its reorder level is Denton's. */
    private InventoryItem item(String name) {
        return items.findByBranchIdOrderByName(denton()).stream()
            .filter(held -> name.equals(held.getName()))
            .findFirst().orElseThrow();
    }

    private Long denton() {
        return branches.findAllByOrderByName().stream()
            .filter(branch -> "DEN".equals(branch.getCode()))
            .findFirst().orElseThrow().getId();
    }
}
