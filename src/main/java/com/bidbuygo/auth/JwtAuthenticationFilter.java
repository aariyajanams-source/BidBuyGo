package com.bidbuygo.auth;

import java.io.IOException;
import java.util.List;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying "Authorization: Bearer <jwt>". An invalid token leaves the request
 * unauthenticated; the entry point then answers 401 with the reason stored here.
 * Not a @Component, so Boot doesn't also register it as a plain servlet filter.
 */
@RequiredArgsConstructor
class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                AuthUser user = jwtService.parse(header.substring(BEARER_PREFIX.length()).trim());
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(ERROR_ATTRIBUTE, "Bearer token is invalid or expired");
            }
        }
        chain.doFilter(request, response);
    }
}
