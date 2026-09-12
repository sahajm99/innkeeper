package io.github.sahajm99.innkeeper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class InnkeeperApplicationTests {
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAppliesTheSchema() {
        Integer applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true and version is not null",
            Integer.class);
        assertThat(applied).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from room_night", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_metadata", Integer.class)).isZero();
    }
}
