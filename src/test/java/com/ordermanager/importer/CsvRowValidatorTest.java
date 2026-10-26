package com.ordermanager.importer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CsvRowValidatorTest {

    static final Set<String> SKUS = Set.of("A", "B");

    static CsvRow row(String ref, String name, String address, String phone, String sku, String qty) {
        return new CsvRow(2, ref, name, address, phone, sku, qty);
    }

    static CsvRow valid() {
        return row("EXT-1", "Zoë Müller", "Kerkstraat 1", "+32 470 12 34 56", "A", "3");
    }

    @Test
    void validRowHasNoErrors() {
        assertThat(CsvRowValidator.validate(valid(), SKUS)).isEmpty();
    }

    @Test
    void phoneIsOptional() {
        assertThat(CsvRowValidator.validate(row("EXT-1", "n", "a", "", "A", "1"), SKUS)).isEmpty();
    }

    @Test
    void externalRefRequired() {
        assertThat(CsvRowValidator.validate(row(" ", "n", "a", "", "A", "1"), SKUS))
                .containsExactly("external_ref is required");
    }

    @Test
    void externalRefMaxLength() {
        assertThat(CsvRowValidator.validate(row("x".repeat(65), "n", "a", "", "A", "1"), SKUS))
                .containsExactly("external_ref is too long (max 64)");
    }

    @Test
    void nameRequired() {
        assertThat(CsvRowValidator.validate(row("E", "", "a", "", "A", "1"), SKUS))
                .containsExactly("ship_to_name is required");
    }

    @Test
    void addressRequiredAndMaxLength() {
        assertThat(CsvRowValidator.validate(row("E", "n", "", "", "A", "1"), SKUS))
                .containsExactly("ship_to_address is required");
        assertThat(CsvRowValidator.validate(row("E", "n", "a".repeat(501), "", "A", "1"), SKUS))
                .containsExactly("ship_to_address is too long (max 500)");
    }

    @Test
    void phoneFormat() {
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "call me", "A", "1"), SKUS))
                .containsExactly("ship_to_phone is not a valid phone number");
    }

    @Test
    void unknownSku() {
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "", "ZZZ", "1"), SKUS))
                .containsExactly("Unknown SKU: ZZZ");
    }

    @Test
    void qtyMustBeWholeNumberInRange() {
        String msg = "qty must be a whole number between 1 and 100000";
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "", "A", "0"), SKUS)).containsExactly(msg);
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "", "A", "100001"), SKUS)).containsExactly(msg);
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "", "A", "2.5"), SKUS)).containsExactly(msg);
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "", "A", "abc"), SKUS)).containsExactly(msg);
        assertThat(CsvRowValidator.validate(row("E", "n", "a", "", "A", " 7 "), SKUS)).isEmpty();
    }

    @Test
    void reportsAllProblemsOfARow() {
        assertThat(CsvRowValidator.validate(row("", "", "", "", "ZZZ", "0"), SKUS)).hasSize(5);
    }
}
