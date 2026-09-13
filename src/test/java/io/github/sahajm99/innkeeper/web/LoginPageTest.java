package io.github.sahajm99.innkeeper.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sahajm99.innkeeper.seed.DemoAccounts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The sign-in page. The demo passwords are printed on it on purpose: there is nothing behind them
 * but fictional data, and a visitor with no credentials cannot see the staff half of the demo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginPageTest {

    @Autowired MockMvc mockMvc;

    @Test
    void theSignInPageRendersTheFormAndTheDemoCredentials() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(content().string(containsString("manager123")))
            .andExpect(content().string(containsString(DemoAccounts.STAFF.password())))
            .andExpect(content().string(containsString("name=\"username\"")))
            .andExpect(content().string(containsString("name=\"password\"")));
    }

    @Test
    void theFormCarriesACsrfToken() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void aFailedSignInSaysSo() throws Exception {
        mockMvc.perform(get("/login").param("error", ""))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Wrong username or password")));
    }

    @Test
    void signingOutSaysSo() throws Exception {
        mockMvc.perform(get("/login").param("logout", ""))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("signed out")));
    }

    @Test
    void nothingOnThePageIsAnInlineScriptOrStyle() throws Exception {
        String html = mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("<script>").doesNotContain("<style>");
    }
}
