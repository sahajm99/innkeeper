package io.github.sahajm99.innkeeper.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sahajm99.innkeeper.model.AppMetadata;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The page that says what this deployment actually is: which build, which database, which profile,
 * and when the demo data was last thrown away.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AboutPageTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theAboutPageNamesTheDatabaseAndTheActiveProfile() throws Exception {
        mockMvc.perform(get("/about"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("H2")))
            .andExpect(content().string(containsString("test")));
    }

    @Test
    void theAboutPageNamesWhenTheDemoDataWasSeeded() throws Exception {
        String seededAt = metadata(AppMetadata.SEEDED_AT);

        mockMvc.perform(get("/about"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(seededAt)));
    }

    @Test
    void theAboutPageReportsTheConnectionPool() throws Exception {
        mockMvc.perform(get("/about"))
            .andExpect(content().string(containsString("idle")));
    }

    private String metadata(String key) {
        return jdbc.queryForObject("select meta_value from app_metadata where meta_key = ?",
            String.class, key);
    }

    /**
     * A build made outside a git checkout has no git.properties on the classpath, and the page has
     * to say so rather than break. Pointing the git info at a resource that is not there is the
     * same thing to Spring Boot: no GitProperties bean at all.
     */
    @Nested
    @TestPropertySource(properties = "spring.info.git.location=classpath:no-such-git.properties")
    class WithoutGitProperties {

        @Autowired MockMvc mockMvc;

        @Test
        void theCommitIsUnknown() throws Exception {
            mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unknown")));
        }
    }
}
