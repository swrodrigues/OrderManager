package com.ordermanager.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void keepsTwoCharsEachEnd() {
        assertThat(Masking.mask("Kerkstraat 1")).isEqualTo("Ke**** 1");
    }

    @Test
    void shortValuesAreFullyMasked() {
        assertThat(Masking.mask("abcd")).isEqualTo("****");
    }

    @Test
    void nullStaysNull() {
        assertThat(Masking.mask(null)).isNull();
    }
}
