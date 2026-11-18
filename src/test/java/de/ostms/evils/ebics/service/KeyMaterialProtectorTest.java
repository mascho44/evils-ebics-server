package de.ostms.evils.ebics.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialProtectorTest {
    @Test
    void encryptsAndAuthenticatesPrivateMaterial() {
        char[] secret = "test-only-master-secret".toCharArray();
        byte[] original = "private-key-material".getBytes(StandardCharsets.UTF_8);
        var protectedMaterial = KeyMaterialProtector.protect(original, secret);
        assertThat(protectedMaterial.ciphertext()).isNotEqualTo(original);
        assertThat(KeyMaterialProtector.unprotect(protectedMaterial, secret)).isEqualTo(original);
        assertThatThrownBy(() -> KeyMaterialProtector.unprotect(protectedMaterial, "different-secret".toCharArray()))
                .isInstanceOf(IllegalStateException.class);
    }
}
