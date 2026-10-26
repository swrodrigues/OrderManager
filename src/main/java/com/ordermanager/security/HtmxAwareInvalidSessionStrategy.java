package com.ordermanager.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.session.InvalidSessionStrategy;

import java.io.IOException;

/**
 * The session has expired (and with it the CSRF token stored in it). Send the browser to the login page:
 * HTMX requests via {@code HX-Redirect}, normal requests via a redirect. A new session is created first so a
 * stale session cookie cannot cause a redirect loop.
 */
public class HtmxAwareInvalidSessionStrategy implements InvalidSessionStrategy {

    @Override
    public void onInvalidSessionDetected(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.getSession(true);
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", "/login");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        response.sendRedirect(request.getContextPath() + "/login?expired");
    }
}
