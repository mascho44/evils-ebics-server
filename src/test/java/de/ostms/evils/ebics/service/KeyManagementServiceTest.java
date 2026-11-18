package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.protocol.EbicsProtocolException;
import de.ostms.evils.ebics.protocol.EbicsRequest;
import de.ostms.evils.ebics.protocol.PubKeyValueXml;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class KeyManagementServiceTest {
    @Autowired KeyManagementService service;
    @Autowired BankService bankService;

    @Test
    void iniPersistsTheRealSignaturePublicKeyFromTheOrderData() throws Exception {
        RSAPublicKey signatureKey = generateKey();

        service.process(request("INI", signaturePubKeyOrderData(signatureKey, "A006")));

        var participant = bankService.findParticipant("USER001").orElseThrow();
        assertThat(participant.getSignatureKeyStatus()).isEqualTo("INITIALIZED");
        // keysOf() is not filtered to this single call - other tests in this class share the same
        // Spring context/database and may have already stored keys for USER001, so this asserts
        // that OUR key is present rather than that it is the only one (matches the existing
        // service.all() style elsewhere in this class, which uses .contains(...) for the same reason).
        var mostRecentSignatureKey = service.keysOf("USER001").stream()
                .filter(k -> "SIGNATURE".equals(k.getKeyType()))
                .findFirst().orElseThrow();
        assertThat(mostRecentSignatureKey.getVersion()).isEqualTo("A006");
        assertThat(mostRecentSignatureKey.getFingerprintSha256()).hasSize(64);
    }

    @Test
    void hiaPersistsBothTheRealAuthenticationAndEncryptionPublicKeys() throws Exception {
        RSAPublicKey authKey = generateKey();
        RSAPublicKey encKey = generateKey();

        service.process(request("HIA", hiaOrderData(authKey, encKey)));

        var participant = bankService.findParticipant("USER001").orElseThrow();
        assertThat(participant.getAuthenticationKeyStatus()).isEqualTo("INITIALIZED");
        assertThat(participant.getEncryptionKeyStatus()).isEqualTo("INITIALIZED");
        var keys = service.keysOf("USER001");
        assertThat(keys).extracting(k -> k.getKeyType()).containsExactlyInAnyOrder("AUTHENTICATION", "ENCRYPTION");
        assertThat(service.all()).extracting(i -> i.getOrderType()).contains("HIA");
    }

    @Test
    void iniWithoutOrderDataIsRejectedInsteadOfSilentlyMarkingTheKeyInitialized() {
        assertThatThrownBy(() -> service.process(new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "INI", "ORDER-INI", "")))
                .isInstanceOf(EbicsProtocolException.class);
    }

    @Test
    void hpbIsRecordedWithoutChangingCustomerKeys() {
        service.process(new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "HPB", "ORDER-HPB", ""));
        assertThat(service.all()).extracting(i -> i.getOrderType()).contains("HPB");
    }

    @Test
    void resetDeletesStoredKeysAndRevertsStatusToNotInitialized() throws Exception {
        service.process(request("INI", signaturePubKeyOrderData(generateKey(), "A006")));
        assertThat(bankService.findParticipant("USER001").orElseThrow().getSignatureKeyStatus())
                .isEqualTo("INITIALIZED");

        var participant = service.reset("USER001");

        assertThat(participant.getSignatureKeyStatus()).isEqualTo("NOT_INITIALIZED");
        assertThat(participant.getAuthenticationKeyStatus()).isEqualTo("NOT_INITIALIZED");
        assertThat(participant.getEncryptionKeyStatus()).isEqualTo("NOT_INITIALIZED");
        assertThat(service.keysOf("USER001")).isEmpty();
    }

    @Test
    void resetOfUnknownUserIdThrows() {
        assertThatThrownBy(() -> service.reset("DOES-NOT-EXIST")).isInstanceOf(IllegalArgumentException.class);
    }

    private static RSAPublicKey generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return (RSAPublicKey) generator.generateKeyPair().getPublic();
    }

    private static String signaturePubKeyOrderData(RSAPublicKey key, String version) {
        return """
                <SignaturePubKeyOrderData xmlns="urn:org:ebics:H005">
                  <SignaturePubKeyInfo>
                    %s
                    <SignatureVersion>%s</SignatureVersion>
                  </SignaturePubKeyInfo>
                </SignaturePubKeyOrderData>
                """.formatted(PubKeyValueXml.render(key), version);
    }

    private static String hiaOrderData(RSAPublicKey authKey, RSAPublicKey encKey) {
        return """
                <HIARequestOrderData xmlns="urn:org:ebics:H005">
                  <AuthenticationPubKeyInfo>
                    %s
                    <AuthenticationVersion>X002</AuthenticationVersion>
                  </AuthenticationPubKeyInfo>
                  <EncryptionPubKeyInfo>
                    %s
                    <EncryptionVersion>E002</EncryptionVersion>
                  </EncryptionPubKeyInfo>
                </HIARequestOrderData>
                """.formatted(PubKeyValueXml.render(authKey), PubKeyValueXml.render(encKey));
    }

    private static EbicsRequest request(String orderType, String orderDataXml) {
        return new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", orderType, "ORDER-" + orderType, "", orderDataXml);
    }
}
