package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sahajm99.innkeeper.model.Employee;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.EmployeeRepository;
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
 * Who works here, and who may look.
 *
 * <p>The page is the only one in the application a signed-in clerk is refused, so the refusal is
 * asserted as hard as the feature: a clerk gets the designed 403 rather than a page with the
 * buttons missing.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmployeePagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired EmployeeRepository employees;
    @Autowired BranchRepository branches;
    @Autowired ResetService reset;

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    @WithMockUser(username = "staff", roles = "STAFF")
    void aClerkIsRefusedWithTheDesignedPage() throws Exception {
        String html = mockMvc.perform(get("/staff/employees"))
            .andExpect(status().isForbidden())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Managers only");
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void aManagerSeesEverybodyByHotel() throws Exception {
        String html = mockMvc.perform(get("/staff/employees"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Ben Sample").contains("Front desk").contains("Denton Square");
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void hiringSomebodyAndThenMarkingThemGone() throws Exception {
        mockMvc.perform(post("/staff/employees")
                .param("branchId", String.valueOf(denton()))
                .param("firstName", "Remy")
                .param("lastName", "Sample")
                .param("email", "remy.sample@example.com")
                .param("position", "Night porter")
                .param("hiredOn", "2026-01-05")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("Remy Sample added")));

        Employee hired = employees.findByEmail("remy.sample@example.com").orElseThrow();
        String listed = mockMvc.perform(get("/staff/employees"))
            .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains("Remy Sample").contains("Night porter");

        mockMvc.perform(post("/staff/employees/" + hired.getId() + "/deactivate").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("message", containsString("no longer works here")));

        String gone = mockMvc.perform(get("/staff/employees"))
            .andReturn().getResponse().getContentAsString();
        assertThat(gone).contains("No longer works here");
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void anEmailSomebodyAlreadyHasIsAnErrorOnTheField() throws Exception {
        String html = mockMvc.perform(post("/staff/employees")
                .param("branchId", String.valueOf(denton()))
                .param("firstName", "Another")
                .param("lastName", "Sample")
                .param("email", "ben.sample@example.com")
                .param("position", "Front desk")
                .param("hiredOn", "2026-01-05")
                .with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Somebody already works here with that email address");
    }

    private Long denton() {
        return branches.findAllByOrderByName().stream()
            .filter(branch -> "DEN".equals(branch.getCode()))
            .findFirst().orElseThrow().getId();
    }
}
