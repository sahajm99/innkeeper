package io.github.sahajm99.innkeeper.ops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The endpoint a scheduled workflow calls with curl. No session, no CSRF token, no browser: one
 * header decides, and a deployment with no token configured answers as if the endpoint did not
 * exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResetEndpointTest {

    private static final String HEADER = "X-Reset-Token";
    private static final String TOKEN = "test-reset-token";

    @Autowired MockMvc mockMvc;

    @Test
    void aRequestWithoutTheTokenIsRefused() throws Exception {
        mockMvc.perform(post("/internal/reset")).andExpect(status().isForbidden());
    }

    @Test
    void aRequestWithTheWrongTokenIsRefused() throws Exception {
        mockMvc.perform(post("/internal/reset").header(HEADER, "test-reset-tokeN"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aForcedRequestWithTheRightTokenResetsWithoutACsrfToken() throws Exception {
        mockMvc.perform(post("/internal/reset").param("force", "true").header(HEADER, TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.performed").value(true))
            .andExpect(jsonPath("$.lastResetAt").isNotEmpty());
    }

    @Test
    void anUnforcedRequestJustAfterAResetIsSkipped() throws Exception {
        mockMvc.perform(post("/internal/reset").param("force", "true").header(HEADER, TOKEN))
            .andExpect(status().isOk());

        mockMvc.perform(post("/internal/reset").header(HEADER, TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.performed").value(false));
    }

    /** A deployment with no token configured - a local run, or a fork - has no reset endpoint. */
    @Nested
    @TestPropertySource(properties = "innkeeper.reset-token=")
    class WithoutAToken {

        @Autowired MockMvc mockMvc;

        @Test
        void theEndpointIsNotThere() throws Exception {
            mockMvc.perform(post("/internal/reset").header(HEADER, TOKEN))
                .andExpect(status().isNotFound());
        }

        @Test
        void theEndpointIsNotThereWithoutAHeaderEither() throws Exception {
            mockMvc.perform(post("/internal/reset")).andExpect(status().isNotFound());
        }
    }
}
