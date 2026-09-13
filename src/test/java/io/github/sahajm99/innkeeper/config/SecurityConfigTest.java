package io.github.sahajm99.innkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The two chains: who gets in, who is turned away, and what the browser is told on the way out.
 *
 * <p>The staff pages themselves arrive in a later task, so "a manager reaches the employees page"
 * can only assert that authorisation let the request through - a 404 from an empty router is a
 * pass, a 403 or a redirect to the sign-in page is not.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;

    // --- the page chain -------------------------------------------------------------------------

    @Test
    void anonymousVisitorsAreSentToTheSignInPage() throws Exception {
        mockMvc.perform(get("/staff"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotOpenTheEmployeesPage() throws Exception {
        mockMvc.perform(get("/staff/employees")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managersAreLetThroughToTheEmployeesPage() throws Exception {
        int status = mockMvc.perform(get("/staff/employees")).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403, 302);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void aPostWithoutACsrfTokenIsRefused() throws Exception {
        mockMvc.perform(post("/staff/inventory/1/adjust").param("delta", "1"))
            .andExpect(status().isForbidden());
    }

    // --- the API chain and the documentation ----------------------------------------------------

    @Test
    void theOpenApiDocumentIsPublicJson() throws Exception {
        mockMvc.perform(get("/api/openapi"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void theSwaggerUiIsReachableAnonymously() throws Exception {
        MvcResult docs = mockMvc.perform(get("/api/docs")).andReturn();

        assertThat(docs.getResponse().getStatus()).isIn(200, 302);
        if (docs.getResponse().getStatus() == 302) {
            String location = docs.getResponse().getRedirectedUrl();
            assertThat(location).contains("/swagger-ui/index.html");
            mockMvc.perform(get(URI.create(location))).andExpect(status().isOk());
        }
    }

    // --- actuator -------------------------------------------------------------------------------

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"status\":\"UP\"")));
    }

    @Test
    void everyOtherActuatorEndpointIsNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    // --- signing in -----------------------------------------------------------------------------

    @Test
    void theStaffAccountSignsInAndLandsOnTheDesk() throws Exception {
        mockMvc.perform(formLogin().user("staff").password("staff123"))
            .andExpect(redirectedUrl("/staff"))
            .andExpect(authenticated().withRoles("STAFF"));
    }

    @Test
    void aWrongPasswordGoesBackToTheSignInPage() throws Exception {
        mockMvc.perform(formLogin().user("staff").password("nope"))
            .andExpect(redirectedUrl("/login?error"))
            .andExpect(unauthenticated());
    }

    // --- what the browser is told -----------------------------------------------------------------

    @Test
    void everyPageCarriesTheContentSecurityPolicyAndReferrerPolicy() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'"))
            .andExpect(header().string("Referrer-Policy", "same-origin"));
    }

    @Test
    void robotsAreKeptOutOfTheStaffPages() throws Exception {
        mockMvc.perform(get("/robots.txt"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Disallow: /staff/")));
    }
}
