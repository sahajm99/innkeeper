package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.ComplaintRepository;
import io.github.sahajm99.innkeeper.service.ResetService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The complaint form, its refusals and the ticket it produces.
 *
 * <p>The linking rule is the interesting one: a confirmation code only attaches the stay when the
 * email that booked it is given too, so the form cannot be used to find out which codes exist or to
 * hang a complaint on somebody else's stay.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComplaintPagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired BranchRepository branches;
    @Autowired ComplaintRepository complaints;
    @Autowired ResetService reset;

    private Branch denton;

    @BeforeEach
    void findABranch() {
        denton = branches.findByCode("DEN").orElseThrow();
    }

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    @Test
    void theFormOffersTheThreeHotelsAndTheCategories() throws Exception {
        String html = mockMvc.perform(get("/complaints/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("name=\"branchId\"").contains("Denton Square");
        assertThat(html).contains("name=\"category\"").contains("The room").contains("The bill");
        assertThat(html).contains("do not enter real personal data");
    }

    @Test
    void aComplaintWithNothingWrittenInItComesBackWithTheError() throws Exception {
        String html = mockMvc.perform(complaintPost(Map.of("description", "")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Tell us what went wrong");
        assertThat(html).contains("class=\"field-error\"");
    }

    @Test
    void aCodeThatDoesNotMatchTheEmailIsAFieldError() throws Exception {
        String html = mockMvc.perform(complaintPost(Map.of(
                "bookingCode", "INN-ZZZZZZ", "bookingEmail", "nobody@example.com")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("That code and email do not match a booking");
    }

    @Test
    void aFiledComplaintRedirectsToItsTicket() throws Exception {
        String location = mockMvc.perform(complaintPost())
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/complaints/CMP-*"))
            .andReturn().getResponse().getRedirectedUrl();

        String ticket = location.substring("/complaints/".length());
        String html = mockMvc.perform(get(location))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(ticket).contains("Denton Square");
        assertThat(complaints.findByTicketNumber(ticket)).isPresent();
    }

    @Test
    void aFilledHoneypotFilesNothingAndSaysNothing() throws Exception {
        long before = complaints.count();

        mockMvc.perform(complaintPost(Map.of("website", "https://example.com/spam")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        assertThat(complaints.count()).isEqualTo(before);
    }

    @Test
    void aTicketThatDoesNotExistIsTheNotFoundPage() throws Exception {
        mockMvc.perform(get("/complaints/CMP-999999"))
            .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder complaintPost() {
        return complaintPost(Map.of());
    }

    /**
     * The complaint form as the browser would send it, with the named fields replaced. MockMvc
     * appends repeated parameters rather than replacing them, so the overrides go in before the
     * request is built.
     */
    private MockHttpServletRequestBuilder complaintPost(Map<String, String> overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("branchId", String.valueOf(denton.getId()));
        fields.put("category", "NOISE");
        fields.put("guestName", "Ada Example");
        fields.put("guestEmail", "ada.example@example.com");
        fields.put("bookingCode", "");
        fields.put("bookingEmail", "");
        fields.put("description", "The room above mine moved furniture at two in the morning.");
        fields.put("website", "");
        fields.putAll(overrides);

        MockHttpServletRequestBuilder request = post("/complaints/new").with(csrf());
        fields.forEach(request::param);
        return request;
    }
}
