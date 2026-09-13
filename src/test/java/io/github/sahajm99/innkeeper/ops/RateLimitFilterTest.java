package io.github.sahajm99.innkeeper.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The limiter is off in the test profile - twenty bookings an hour is a floor for the public
 * internet, not for a test suite - so this class turns it on for a context of its own. Each test
 * uses an address of its own, because the limiter outlives a single test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "innkeeper.rate-limit.enabled=true")
class RateLimitFilterTest {

    private static final int PER_HOUR = 20;

    @Autowired MockMvc mockMvc;

    @Test
    void theTwentyFirstApiCallFromOneAddressIsRefusedAsJson() throws Exception {
        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            int status = lookupFrom("203.0.113.9, 10.0.0.1");
            assertThat(status).as("attempt %d", attempt).isNotEqualTo(429);
        }

        MvcResult refused = mockMvc.perform(withHop(post("/api/bookings/lookup"), "203.0.113.9, 10.0.0.1"))
            .andExpect(status().isTooManyRequests())
            .andReturn();

        assertThat(refused.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(refused.getResponse().getContentAsString())
            .contains("\"status\":429")
            .contains("Too many requests");
    }

    @Test
    void anotherAddressBehindTheSameProxyIsNotRefused() throws Exception {
        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            lookupFrom("198.51.100.7, 10.0.0.1");
        }
        assertThat(lookupFrom("198.51.100.7, 10.0.0.1")).isEqualTo(429);

        assertThat(lookupFrom("198.51.100.8, 10.0.0.1")).isNotEqualTo(429);
    }

    @Test
    void readingIsNeverLimited() throws Exception {
        for (int attempt = 1; attempt <= PER_HOUR + 5; attempt++) {
            int status = mockMvc.perform(withHop(get("/api/branches"), "192.0.2.11"))
                .andReturn().getResponse().getStatus();
            assertThat(status).as("attempt %d", attempt).isNotEqualTo(429);
        }
    }

    @Test
    void theTwentyFirstComplaintFromAPageIsRefusedAsAPage() throws Exception {
        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            mockMvc.perform(withHop(post("/complaints/new"), "192.0.2.44"));
        }

        MvcResult refused = mockMvc.perform(withHop(post("/complaints/new"), "192.0.2.44"))
            .andExpect(status().isTooManyRequests())
            .andReturn();

        assertThat(refused.getResponse().getContentType()).startsWith("text/html");
        assertThat(refused.getResponse().getContentAsString()).contains("Too many requests");
    }

    private int lookupFrom(String forwardedFor) throws Exception {
        return mockMvc.perform(withHop(post("/api/bookings/lookup"), forwardedFor))
            .andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder withHop(MockHttpServletRequestBuilder request,
            String forwardedFor) {
        return request.header("X-Forwarded-For", forwardedFor);
    }
}
