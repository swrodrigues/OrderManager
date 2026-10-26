package com.ordermanager.order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Keyset position: the (createdAt, id) of the last row on the previous page. URL-safe as {@code <epochMicros>_<id>}. */
public record Cursor(Instant createdAt, long id) {

    public String encode() {
        return ChronoUnit.MICROS.between(Instant.EPOCH, createdAt) + "_" + id;
    }

    public static Cursor decode(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] parts = s.trim().split("_");
        try {
            if (parts.length != 2) {
                throw new NumberFormatException();
            }
            return new Cursor(Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
