package com.ordermanager.common;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Used from templates as {@code ${@timing.elapsedMs()}}. */
@Component("timing")
public class RequestTiming {

    public long elapsedMs() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object start = attrs == null ? null : attrs.getAttribute(RequestTimingFilter.START_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return start instanceof Long nanos ? (System.nanoTime() - nanos) / 1_000_000 : 0;
    }
}
