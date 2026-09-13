package io.github.sahajm99.innkeeper.config;

import java.time.Duration;
import java.time.ZoneId;

import io.github.sahajm99.innkeeper.web.Dates;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * What the browser is told about our own files, and how a template writes a date.
 *
 * <p>The stylesheet, the scripts and the four font files never change inside one deployment - a
 * new build is a new container - so they are cached for a week. Each folder is registered on its
 * own rather than as one {@code /**} handler, which would take the mapping away from Spring
 * Boot's own handler and from springdoc's assets; a more specific pattern wins without displacing
 * anything.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Long enough to matter on a free tier, short enough that a stale asset dies within a week. */
    private static final Duration ASSET_CACHE = Duration.ofDays(7);

    /** The folders under static/ that hold nothing but versionless assets of ours. */
    private static final String[] ASSET_FOLDERS = {"css", "js", "fonts"};

    private final InnkeeperProperties properties;

    public WebConfig(InnkeeperProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String folder : ASSET_FOLDERS) {
            registry.addResourceHandler("/" + folder + "/**")
                .addResourceLocations("classpath:/static/" + folder + "/")
                .setCacheControl(CacheControl.maxAge(ASSET_CACHE).cachePublic());
        }
    }

    /**
     * The date and money formatter every page reads as {@code ${dates}}. It is a bean so the
     * timezone comes from configuration rather than from whatever the container happens to run in.
     */
    @Bean
    public Dates dates() {
        return new Dates(properties.timezone() == null
            ? ZoneId.systemDefault()
            : ZoneId.of(properties.timezone()));
    }
}
