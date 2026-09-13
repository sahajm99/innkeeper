package io.github.sahajm99.innkeeper.config;

import io.github.sahajm99.innkeeper.web.ErrorPageRenderer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Two chains, because the site is two things.
 *
 * <p>{@code /api/**} is machine-facing: no session, no CSRF token to fetch first, everything
 * readable, and the rules that matter there - who owns a booking - are checked by the service, not
 * by a role. The pages are browser-facing: a form login, a CSRF token on every mutation and roles
 * that decide what the staff half shows. They have to be separate chains rather than separate rules
 * in one, because the two differ in session policy and CSRF, which are chain-wide settings.</p>
 *
 * <p>Almost everything is {@code permitAll}: this is a public demo, and the only doors are the
 * staff pages. {@code /internal/reset} is permitted here and guarded by its token in the
 * controller, so the caller is a {@code curl} from a scheduled workflow rather than a signed-in
 * user.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Every script and stylesheet is a file of ours, so nothing needs an inline allowance. */
    public static final String CONTENT_SECURITY_POLICY =
        "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'";

    /**
     * The springdoc endpoints. With the UI configured at /api/docs, springdoc serves its assets
     * from /api/swagger-ui, which the API chain already covers; the rest are listed so the page
     * chain still lets the documentation through if that configuration ever moves.
     */
    private static final String[] DOCUMENTATION = {
        "/api/**", "/swagger-ui/**", "/webjars/**", "/api/docs", "/api/openapi", "/v3/api-docs/**"
    };

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
            .headers(SecurityConfig::harden)
            .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain pageChain(HttpSecurity http, AccessDeniedHandler accessDenied)
            throws Exception {
        return http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/internal/reset"))
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(DOCUMENTATION).permitAll()
                .requestMatchers("/staff/employees/**").hasRole("MANAGER")
                .requestMatchers("/staff/reset").hasRole("MANAGER")
                .requestMatchers("/staff/**").hasAnyRole("STAFF", "MANAGER")
                .requestMatchers("/internal/reset").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/staff", false)
                .permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
            .exceptionHandling(handling -> handling.accessDeniedHandler(accessDenied))
            .headers(SecurityConfig::harden)
            .build();
    }

    /**
     * The designed 403 rather than the container's blank one.
     *
     * <p>It renders the page itself instead of forwarding to it, so the answer does not depend on
     * the servlet error mechanism being reachable from inside the security chain - and so a
     * refused CSRF token gets the same page as a refused role.</p>
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler(ErrorPageRenderer errorPages) {
        return (request, response, denied) ->
            errorPages.render(request, response, HttpStatus.FORBIDDEN.value(), null);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void harden(HeadersConfigurer<HttpSecurity> headers) {
        headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN));
    }
}
