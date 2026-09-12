package io.github.sahajm99.innkeeper.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Locale;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The context only starts when Hibernate validates every entity against the Flyway schema, so these
 * tests prove the mapping matches V1 column by column rather than merely compiling.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SchemaValidationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Test
    void everyTableInTheMigrationHasAnEntityAndNoEntityInventsOne() {
        List<String> mapped = em.getMetamodel().getEntities().stream()
            .map(entity -> entity.getJavaType().getAnnotation(Table.class).name())
            .map(name -> name.toLowerCase(Locale.ROOT))
            .toList();

        assertThat(mapped).containsExactlyInAnyOrderElementsOf(schemaTables());
    }

    @Test
    void everyEntityCanBeQueried() {
        for (EntityType<?> entity : em.getMetamodel().getEntities()) {
            String jpql = "select count(e) from " + entity.getName() + " e";
            assertThatCode(() -> em.createQuery(jpql, Long.class).getSingleResult())
                .as(jpql)
                .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayAppliedExactlyOneVersionedMigration() {
        Integer applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true and version is not null",
            Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    private List<String> schemaTables() {
        return jdbc.queryForList(
                "select table_name from information_schema.tables where lower(table_schema) = 'public'",
                String.class)
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .filter(name -> !name.equals("flyway_schema_history"))
            .toList();
    }
}
