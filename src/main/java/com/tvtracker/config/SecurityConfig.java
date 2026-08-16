package com.tvtracker.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final Environment env;

    public SecurityConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean googleConfigured = StringUtils.hasText(env.getProperty("GOOGLE_CLIENT_ID"))
                && StringUtils.hasText(env.getProperty("GOOGLE_CLIENT_SECRET"));

        http.csrf(AbstractHttpConfigurer::disable);

        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(
                    "/",
                    "/index.html",
                    "/assets/**",
                    "/favicon.ico",
                    "/favicon.*",
                    "/manifest.json",
                    "/error",
                    "/login",
                    "/oauth2/**",
                    "/login/oauth2/**",
                    "/api/shows/popular"
            ).permitAll();

            if (googleConfigured) {
                auth.requestMatchers("/api/**").authenticated();
            } else {
                auth.requestMatchers("/api/**").permitAll();
            }

            auth.anyRequest().permitAll();
        });

        if (googleConfigured) {
            final String frontendUrlCandidate = env.getProperty("app.frontend.url");
            final String frontendUrl = StringUtils.hasText(frontendUrlCandidate) ? frontendUrlCandidate : env.getProperty("FRONTEND_URL");
            String computedSuccessUrl;
            if (StringUtils.hasText(frontendUrl)) {
                if (frontendUrl.contains("localhost")) {
                    // Bust any cached production index.html when developing with Vite
                    String ts = String.valueOf(System.currentTimeMillis());
                    computedSuccessUrl = frontendUrl.endsWith("/")
                            ? frontendUrl + "?_ts=" + ts + "#dashboard"
                            : frontendUrl + "/?_ts=" + ts + "#dashboard";
                } else {
                    computedSuccessUrl = frontendUrl.endsWith("/") ? frontendUrl + "#dashboard" : frontendUrl + "/#dashboard";
                }
            } else {
                computedSuccessUrl = "/#dashboard";
            }
            final String successUrl = computedSuccessUrl;

            http.oauth2Login(oauth2 -> oauth2.defaultSuccessUrl(successUrl, true));
            http.logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl(StringUtils.hasText(frontendUrl) ? frontendUrl : "/")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID"));
            http.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                if (request.getRequestURI().startsWith("/api/")) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                response.sendRedirect(StringUtils.hasText(frontendUrl) ? frontendUrl : "/");
            }));
        } else {
            http.httpBasic(AbstractHttpConfigurer::disable);
            http.formLogin(AbstractHttpConfigurer::disable);
        }

        return http.build();
    }
}
