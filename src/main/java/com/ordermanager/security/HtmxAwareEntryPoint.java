package com.ordermanager.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;

/**
 * When a session has expired, an HTMX click must send the whole browser to the login page,
 * not swap the login page into a table row. HTMX follows the {@code HX-Redirect} header.
 */
public class HtmxAwareEntryPoint implements AuthenticationEntryPoint {

    private final LoginUrlAuthenticationEntryPoint fallback = new LoginUrlAuthenticationEntryPoint("/login");

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, jakarta.servlet.ServletException {
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", "/login");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        fallback.commence(request, response, authException);
    }
}
