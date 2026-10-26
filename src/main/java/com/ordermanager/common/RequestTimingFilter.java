package com.ordermanager.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Stamps each request with its start time so the page footer can show server time ("rendered in 12 ms"). */
@Component
public class RequestTimingFilter extends OncePerRequestFilter {

    static final String START_ATTRIBUTE = "om.start";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        chain.doFilter(request, response);
    }
}
