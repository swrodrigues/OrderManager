package com.ordermanager.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

import java.io.IOException;

/**
 * A 403 during an HTMX request shows a short message in the page's flash area instead of being dropped
 * (or swapping a full error page into a table row). Other requests get the normal error page.
 */
public class HtmxAwareAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        if (!"true".equals(request.getHeader("HX-Request"))) {
            fallback.handle(request, response, ex);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("HX-Retarget", "#flash");
        response.setHeader("HX-Reswap", "innerHTML");
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<div class=\"alert alert-error\" role=\"status\">"
                + "This action is not allowed, or the page is out of date. Please reload.</div>");
    }
}
