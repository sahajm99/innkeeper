package io.github.sahajm99.innkeeper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The branch list, which is what the home page and any client starts from. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BranchApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void theThreeBranchesAreListedByName() throws Exception {
        mockMvc.perform(get("/api/branches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].code").value("AUS"))
            .andExpect(jsonPath("$[1].code").value("DEN"))
            .andExpect(jsonPath("$[2].code").value("FTW"))
            .andExpect(jsonPath("$[1].name").value("Denton Square"))
            .andExpect(jsonPath("$[1].city").value("Denton"))
            .andExpect(jsonPath("$[1].state").value("TX"))
            .andExpect(jsonPath("$[1].timezone").value("America/Chicago"));
    }

    @Test
    void eachBranchCarriesTheRateOfItsCheapestRoom() throws Exception {
        JsonNode branches = objectMapper.readTree(mockMvc.perform(get("/api/branches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[1].code").value("DEN"))
            .andReturn().getResponse().getContentAsString());

        assertThat(branches.get(1).get("fromRate").decimalValue())
            .isEqualByComparingTo(new BigDecimal("89.00"));
        assertThat(branches.get(2).get("fromRate").decimalValue())
            .isEqualByComparingTo(new BigDecimal("99.00"));
    }

    @Test
    void theListIsJsonAndNeedsNoSignIn() throws Exception {
        mockMvc.perform(get("/api/branches"))
            .andExpect(status().isOk())
            .andExpect(content -> assertThat(content.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE));
    }
}
