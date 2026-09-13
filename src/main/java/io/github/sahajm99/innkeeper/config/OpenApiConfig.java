package io.github.sahajm99.innkeeper.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The title page of the API documentation.
 *
 * <p>springdoc finds the endpoints on its own; what it cannot know is what this API is for, and
 * anyone who opens the UI of a public demo deserves to be told before they book a room that nobody
 * will keep. The version comes from the build when there is one and says so when there is not.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String TITLE = "Innkeeper API";

    private static final String DESCRIPTION = """
        The JSON half of Innkeeper, a hotel-management demo.

        Everything here is fictional and the whole database is deleted and re-seeded every night at
        03:00 America/Chicago, so bookings made through this API do not last. Writing endpoints are
        rate limited to twenty an hour per address. Confirmation codes are shown once, at creation:
        a booking is read back with the code and the email it was made with, which is why the lookup
        is a POST.""";

    @Bean
    public OpenAPI innkeeperOpenApi(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        String version = properties == null ? "unversioned" : properties.getVersion();
        return new OpenAPI().info(new Info()
            .title(TITLE)
            .version(version)
            .description(DESCRIPTION));
    }
}
