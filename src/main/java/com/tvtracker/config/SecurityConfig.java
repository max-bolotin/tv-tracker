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
        boolean googleConfigured = StringUtils.hasText(env.getProperty("spring.security.oauth2.client.registration.google.client-id"))
                && StringUtils.hasText(env.getProperty("spring.security.oauth2.client.registration.google.client-secret"));

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
                    "/login/oauth2/**"
            ).permitAll();

            if (googleConfigured) {
                auth.requestMatchers("/api/**").authenticated();
            } else {
                auth.requestMatchers("/api/**").permitAll();
            }

            auth.anyRequest().permitAll();
        });

        if (googleConfigured) {
            http.oauth2Login(oauth2 -> oauth2.defaultSuccessUrl("/#dashboard", true));
            http.logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID"));
            http.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                if (request.getRequestURI().startsWith("/api/")) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                response.sendRedirect("/");
            }));
        } else {
            http.httpBasic(AbstractHttpConfigurer::disable);
            http.formLogin(AbstractHttpConfigurer::disable);
        }

        return http.build();
    }
}
