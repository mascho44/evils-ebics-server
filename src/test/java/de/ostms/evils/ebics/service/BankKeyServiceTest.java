package de.ostms.evils.ebics.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BankKeyServiceTest {
    @Autowired BankKeyService service;
    @Test
    void generatesThreeEncryptedRsaBankKeys() {
        var keys = service.generateActiveKeySet();
        assertThat(keys).hasSize(3);
        assertThat(keys).extracting(k -> k.getKeyType()).containsExactlyInAnyOrder("AUTHENTICATION", "ENCRYPTION", "SIGNATURE");
        assertThat(keys).allSatisfy(k -> {
            assertThat(k.getAlgorithm()).isEqualTo("RSA-3072");
            assertThat(k.getEncryptedPrivateKeyBase64()).isNotBlank();
            assertThat(k.getFingerprintSha256()).hasSize(64);
        });
    }
}
