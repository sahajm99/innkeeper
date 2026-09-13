package io.github.sahajm99.innkeeper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The published specification: everything under /api and nothing else. The reset endpoint is the
 * one that matters here - it is hidden, and a document that advertised it would be an invitation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {

    @Autowired MockMvc mockMvc;

    @Test
    void theDocumentListsEveryApiPath() throws Exception {
        mockMvc.perform(get("/api/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("Innkeeper API"))
            .andExpect(jsonPath("$.info.version").isNotEmpty())
            .andExpect(jsonPath("$.paths['/api/branches']").exists())
            .andExpect(jsonPath("$.paths['/api/rooms']").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{id}']").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{id}/availability']").exists())
            .andExpect(jsonPath("$.paths['/api/bookings']").exists())
            .andExpect(jsonPath("$.paths['/api/bookings/lookup']").exists())
            .andExpect(jsonPath("$.paths['/api/bookings/{code}/cancel']").exists())
            .andExpect(jsonPath("$.paths['/api/demo/race']").exists());
    }

    @Test
    void theResetEndpointIsNotInIt() throws Exception {
        mockMvc.perform(get("/api/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/internal/reset']").doesNotExist())
            .andExpect(jsonPath("$.paths['/login']").doesNotExist())
            .andExpect(jsonPath("$.paths['/about']").doesNotExist());
    }

    @Test
    void theDescriptionWarnsThatTheDataIsThrownAwayNightly() throws Exception {
        mockMvc.perform(get("/api/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.description")
                .value(org.hamcrest.Matchers.containsString("re-seeded every night")));
    }
}
