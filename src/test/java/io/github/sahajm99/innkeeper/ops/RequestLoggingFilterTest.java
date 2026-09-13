package io.github.sahajm99.innkeeper.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * One line per request, and one id that ties a log line, a response header and an error page
 * together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestLoggingFilterTest {

    private static final String HEADER = "X-Request-Id";

    @Autowired MockMvc mockMvc;

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void listen() {
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void stopListening() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/login")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader(HEADER)).isNotBlank();
    }

    @Test
    void anIncomingRequestIdIsEchoedBack() throws Exception {
        MvcResult result = mockMvc.perform(get("/login").header(HEADER, "abc-12345678"))
            .andReturn();

        assertThat(result.getResponse().getHeader(HEADER)).isEqualTo("abc-12345678");
    }

    @Test
    void anIncomingRequestIdThatIsNotOneIsReplaced() throws Exception {
        MvcResult result = mockMvc.perform(get("/login").header(HEADER, "no thanks!"))
            .andReturn();

        assertThat(result.getResponse().getHeader(HEADER))
            .isNotEqualTo("no thanks!")
            .matches("[A-Za-z0-9-]{8,64}");
    }

    @Test
    void oneLineIsLoggedWithTheRequestInTheMappedDiagnosticContext() throws Exception {
        mockMvc.perform(get("/login").param("next", "1")).andExpect(status().isOk());

        List<ILoggingEvent> events = captured.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getMDCPropertyMap())
            .containsEntry("http_method", "GET")
            .containsEntry("http_path", "/login")
            .containsEntry("http_status", "200")
            .containsKeys("request_id", "duration_ms", "principal", "client_ip");
        assertThat(event.getMDCPropertyMap().get("http_path")).doesNotContain("?", "next");
        assertThat(event.getFormattedMessage()).contains("GET", "/login", "200");
    }

    @Test
    void staticAssetsAreNotLogged() throws Exception {
        mockMvc.perform(get("/robots.txt")).andExpect(status().isOk());

        assertThat(captured.list).isEmpty();
    }

    @Test
    void theMappedDiagnosticContextIsEmptyAfterTheRequest() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());

        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
