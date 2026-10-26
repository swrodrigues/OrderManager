package com.ordermanager.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    CryptoService crypto = new CryptoService(KEY);

    @Test
    void roundTripsUnicode() {
        String s = "Rue de l'Église 12, Sint-Truiden — Zoë Müller";
        assertThat(crypto.decrypt(crypto.encrypt(s))).isEqualTo(s);
    }

    @Test
    void storedFormatIsVersionedAndHidesPlaintext() {
        String enc = crypto.encrypt("Kerkstraat 1");
        assertThat(enc).startsWith("v1:").doesNotContain("Kerkstraat");
    }

    @Test
    void samePlaintextGivesDifferentCiphertext() {
        assertThat(crypto.encrypt("x")).isNotEqualTo(crypto.encrypt("x"));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String enc = crypto.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(enc.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownFormatIsRejected() {
        assertThatThrownBy(() -> crypto.decrypt("plain text")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThatThrownBy(() -> new CryptoService(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void rejectsMissingKey() {
        assertThatThrownBy(() -> new CryptoService("")).hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void nullPassesThrough() {
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.decrypt(null)).isNull();
    }
}
