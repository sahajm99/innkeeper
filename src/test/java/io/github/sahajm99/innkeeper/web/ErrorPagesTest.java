package io.github.sahajm99.innkeeper.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The designed error pages.
 *
 * <p>MockMvc has no servlet container behind it, so it records the error dispatch a real container
 * would perform rather than running it: an unknown path answers 404 with an empty body here. The
 * page that body would have carried is asserted by dispatching to {@code /error} with the
 * attributes the container sets, which is exactly what Tomcat does with it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorPagesTest {

    @Autowired MockMvc mockMvc;

    @Test
    void anUnknownPathIsANotFound() throws Exception {
        mockMvc.perform(get("/nope")).andExpect(status().isNotFound());
    }

    @Test
    void theNotFoundPageNamesTheRequestId() throws Exception {
        mockMvc.perform(get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/nope"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("Request id")));
    }

    @Test
    void anUnexpectedFailureRendersTheServerErrorPage() throws Exception {
        mockMvc.perform(get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/staff"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("Request id")));
    }

    @Test
    void aStatusWithNoPageOfItsOwnFallsBackToTheServerErrorPage() throws Exception {
        mockMvc.perform(get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 418)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/teapot"))
            .andExpect(status().is(418))
            .andExpect(content().string(containsString("Request id")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffDeniedTheEmployeesPageSeeTheForbiddenPage() throws Exception {
        mockMvc.perform(get("/staff/employees"))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("Managers only")))
            .andExpect(content().string(containsString("Request id")));
    }

    @Test
    void theDesignedPagesAreAlsoReachableDirectly() throws Exception {
        mockMvc.perform(get("/error/429"))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().string(containsString("Too many requests")));
        mockMvc.perform(get("/error/409"))
            .andExpect(status().isConflict());
        mockMvc.perform(get("/error/403"))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("Managers only")));
    }
}
