package com.ordermanager.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Field rules for one CSV row. Returns every problem found, so the user can fix a row in one go. */
public final class CsvRowValidator {

    private static final Pattern PHONE = Pattern.compile("[+0-9 ()-]{5,40}");
    private static final Pattern QTY = Pattern.compile("\\d{1,6}");

    private CsvRowValidator() {
    }

    public static List<String> validate(CsvRow row, Set<String> knownSkuCodes) {
        List<String> errors = new ArrayList<>();
        required(errors, "external_ref", row.ref(), 64);
        required(errors, "ship_to_name", row.name(), 120);
        required(errors, "ship_to_address", row.address(), 500);
        if (row.phone() != null && !PHONE.matcher(row.phone()).matches()) {
            errors.add("ship_to_phone is not a valid phone number");
        }
        if (row.sku().isEmpty()) {
            errors.add("sku_code is required");
        } else if (!knownSkuCodes.contains(row.sku())) {
            errors.add("Unknown SKU: " + row.sku());
        }
        String qty = row.qtyText() == null ? "" : row.qtyText().trim();
        if (!QTY.matcher(qty).matches() || Integer.parseInt(qty) < 1 || Integer.parseInt(qty) > 100_000) {
            errors.add("qty must be a whole number between 1 and 100000");
        }
        return errors;
    }

    private static void required(List<String> errors, String field, String value, int max) {
        if (value.isEmpty()) {
            errors.add(field + " is required");
        } else if (value.length() > max) {
            errors.add(field + " is too long (max " + max + ")");
        }
    }
}
