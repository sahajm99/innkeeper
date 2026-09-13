package io.github.sahajm99.innkeeper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The chrome every page inherits: one stylesheet, one head script, the demo strip, a skip link and
 * the under-the-hood line.
 *
 * <p>The two negative assertions are the ones that matter most. The content security policy is
 * {@code default-src 'self'} with no allowance for inline code, so a {@code <script>} without a
 * {@code src} or a {@code style=} attribute would be silently dropped by the browser and the page
 * would look broken only in production.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LayoutTest {

    @Autowired MockMvc mockMvc;

    @Test
    void everyPageLoadsTheStylesheetAndTheThemeScript() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "<link rel=\"stylesheet\" href=\"/css/innkeeper.css\">")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/theme.js")));
    }

    @Test
    void everyPageCarriesTheDemoStripAndASkipLink() throws Exception {
        String html = page("/login");

        assertThat(html).contains("Resets nightly at 03:00 Central");
        assertThat(html).contains("href=\"#main\"");
        assertThat(html).contains("Skip to content");
    }

    @Test
    void nothingIsInline() throws Exception {
        String html = page("/login");

        assertThat(html).doesNotContain("<script>").doesNotContain("<style>")
            .doesNotContain("style=\"");
    }

    @Test
    void theStylesheetCarriesTheTokensAndTheSelfHostedFaces() throws Exception {
        String css = mockMvc.perform(get("/css/innkeeper.css"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(css).contains("--paper: #F7F3EC");
        assertThat(css).contains("--ink: #1F1A17");
        assertThat(css).contains("@font-face");
        assertThat(css).contains("Fraunces");
        assertThat(css).contains("Source Sans 3");
    }

    @Test
    void theStylesheetDefinesBothThemes() throws Exception {
        String css = mockMvc.perform(get("/css/innkeeper.css"))
            .andReturn().getResponse().getContentAsString();

        assertThat(css).contains("[data-theme=\"dark\"]");
        assertThat(css).contains(":root:not([data-theme=\"light\"])");
        assertThat(css).contains("prefers-reduced-motion");
    }

    @Test
    void theFontsAreServedAsFontsAndCachedForAWeek() throws Exception {
        mockMvc.perform(get("/fonts/fraunces.woff2"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("font/woff2"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("604800")));
    }

    @Test
    void theFooterSaysWhatIsUnderTheHood() throws Exception {
        assertThat(page("/about")).contains("Under the hood");
    }

    @Test
    void theHeaderOffersTheThemeToggleAndTheStaffDoor() throws Exception {
        String html = page("/about");

        assertThat(html).contains("id=\"theme-toggle\"").contains("aria-pressed");
        assertThat(html).contains("Staff sign in");
        assertThat(html).contains("Find my booking");
    }

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }
}
