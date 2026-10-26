package com.ordermanager.common;

/** Masks personal data (addresses, phone numbers) before it reaches a log line. */
public final class Masking {

    private Masking() {
    }

    public static String mask(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= 4) {
            return "****";
        }
        return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }
}
