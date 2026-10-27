package com.bidbuygo.auth;

import java.io.IOException;
import java.util.List;

import com.bidbuygo.common.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Writes 401/403 responses raised inside the security filter chain in the same ApiError shape as controllers. */
@Component
@RequiredArgsConstructor
class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        Object tokenError = request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE);
        String reason = tokenError != null ? tokenError.toString() : "A valid bearer token is required";
        write(response, HttpStatus.UNAUTHORIZED, new ApiError("Unauthorized", List.of(reason)));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN,
                new ApiError("Forbidden", List.of("You do not have permission to perform this action")));
    }

    private void write(HttpServletResponse response, HttpStatus status, ApiError body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
