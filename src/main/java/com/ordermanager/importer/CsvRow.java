package com.ordermanager.importer;

/** One parsed CSV data row; {@code lineNo} counts the header as line 1. Values are raw (untrimmed) text. */
public record CsvRow(long lineNo, String externalRef, String shipToName, String shipToAddress, String shipToPhone,
                     String skuCode, String qtyText) {

    String ref() { return trim(externalRef); }
    String name() { return trim(shipToName); }
    String address() { return trim(shipToAddress); }
    String phone() { String p = trim(shipToPhone); return p.isEmpty() ? null : p; }
    String sku() { return trim(skuCode); }
    int qty() { return Integer.parseInt(trim(qtyText)); }

    /** Two rows of the same order must agree on where it ships. */
    boolean sameShipTo(CsvRow other) {
        return name().equals(other.name()) && address().equals(other.address())
                && java.util.Objects.equals(phone(), other.phone());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
