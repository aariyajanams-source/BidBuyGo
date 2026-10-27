package com.bidbuygo.auth;

import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                            JsonSecurityErrorHandler errorHandler, Environment environment) throws Exception {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(path.matcher("/auth/**"), path.matcher("/error")).permitAll();
                    // Browsing is public; every other /listings call (create, check) needs a token.
                    auth.requestMatchers(path.matcher(HttpMethod.GET, "/listings"),
                            path.matcher(HttpMethod.GET, "/listings/{id}")).permitAll();
                    // Controllers also use @PreAuthorize; this is a backstop for any admin endpoint that forgets it.
                    auth.requestMatchers(path.matcher("/admin/**")).hasRole("ADMIN");
                    auth.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll();
                    if (dev) {
                        auth.requestMatchers(PathRequest.toH2Console()).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        if (dev) {
            // The H2 console renders in frames.
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
