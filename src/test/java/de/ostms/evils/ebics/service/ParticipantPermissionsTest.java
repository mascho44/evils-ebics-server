package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.client.EbicsKeyMaterial;
import de.ostms.evils.ebics.domain.ParticipantKey;
import de.ostms.evils.ebics.persistence.ParticipantKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-participant EBICS permissions (upload/download, MT7xx pull): the stored
 * flags, and - over real HTTP, like SecurityConfigurationTest - that /ebicsweb actually enforces
 * them with EBICS_AUTHORISATION_ORDERTYPE_FAILED (090003) instead of just displaying them.
 * Since downloads verify the request's X002 transport signature (see EbicsController), every
 * request is signed with USER001's own key material, which this test stores like HIA would. */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParticipantPermissionsTest {

    private static final String HOST = "EVILSBANK";
    private static final String USER = "USER001";

    @Autowired BankService service;
    @Autowired TestRestTemplate rest;
    @Autowired ParticipantKeyRepository keys;
    @Autowired BankKeyService bankKeys;
    EbicsKeyMaterial material;

    /** USER001 is shared by every test in this JVM's H2 database, and KeyManagementServiceTest
     * may reset (delete) its keys in between class runs - store idempotently what the signed
     * download tests need: an AUTHENTICATION key for request-signature verification and an
     * ENCRYPTION key for the E002-encrypted download response. Bank keys are only generated when
     * none exist yet: PaymentUploadTest captures the active set for its own response-signature
     * checks, so re-generating an existing one here would retire those and break that test. */
    @BeforeAll
    void setup() throws Exception {
        material = EbicsKeyMaterial.generate();
        if (keys.findByUserIdOrderByReceivedAtDesc(USER).stream().noneMatch(k -> "AUTHENTICATION".equals(k.getKeyType()))) {
            keys.save(new ParticipantKey(USER, "AUTHENTICATION", "X002", "RSA-3072",
                    Base64.getEncoder().encodeToString(material.authentication().getPublic().getEncoded()), "test"));
        }
        if (keys.findByUserIdOrderByReceivedAtDesc(USER).stream().noneMatch(k -> "ENCRYPTION".equals(k.getKeyType()))) {
            keys.save(new ParticipantKey(USER, "ENCRYPTION", "E002", "RSA-3072",
                    Base64.getEncoder().encodeToString(material.encryption().getPublic().getEncoded()), "test"));
        }
        if (bankKeys.activeAuthenticationAndEncryptionKeys().stream().noneMatch(k -> "AUTHENTICATION".equals(k.getKeyType()))) {
            bankKeys.generateActiveKeySet();
        }
    }

    /** The demo participant is shared by every test in this JVM's H2 database - always put it
     * back to "everything allowed". */
    @AfterEach
    void restoreDefaults() {
        service.setParticipantPermissions(HOST, USER, true, true, true);
    }

    private String btdRequest(String msgName) {
        return """
                <ebicsRequest xmlns="urn:org:ebics:H005" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" Version="H005">
                  <header authenticate="true">
                    <static>
                      <HostID>EVILSBANK</HostID>
                      <PartnerID>TEST001</PartnerID>
                      <UserID>USER001</UserID>
                      <OrderDetails>
                        <AdminOrderType>BTD</AdminOrderType>
                        <BTDOrderParams><Service><ServiceName>%s</ServiceName><MsgName>%s</MsgName></Service></BTDOrderParams>
                      </OrderDetails>
                    </static>
                    <mutable><TransactionPhase>Initialisation</TransactionPhase></mutable>
                  </header>
                  <AuthSignature/>
                  <body/>
                </ebicsRequest>
                """.formatted("TRC".equals(msgName) ? "TRC" : "STA", "TRC".equals(msgName) ? "MT700" : msgName);
    }

    /** Same envelope, but signed with the participant's X002 key - what a real EBICS client sends
     * for a download order (see EbicsAuthentication.sign / EbicsController.verifyDownloadSignature). */
    private String signed(String xml) throws Exception {
        return EbicsAuthentication.sign(xml, material.authentication().getPrivate());
    }

    private String post(String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return rest.postForObject("/ebicsweb", new HttpEntity<>(xml, headers), String.class);
    }

    @Test
    void new_and_existing_participants_default_to_everything_allowed() {
        var p = service.findParticipant(HOST, USER).orElseThrow();
        assertThat(p.isUploadAllowed()).isTrue();
        assertThat(p.isDownloadAllowed()).isTrue();
        assertThat(p.isLcDownloadAllowed()).isTrue();
    }

    @Test
    void null_leaves_a_permission_unchanged_and_the_change_is_persisted() {
        service.setParticipantPermissions(HOST, USER, false, null, null);

        var p = service.findParticipant(HOST, USER).orElseThrow();
        assertThat(p.isUploadAllowed()).isFalse();
        assertThat(p.isDownloadAllowed()).isTrue();
        assertThat(p.isLcDownloadAllowed()).isTrue();
    }

    @Test
    void permissions_of_another_tenants_participant_cannot_be_changed() {
        assertThatThrownBy(() -> service.setParticipantPermissions("SOMEOTHERHOST", USER, false, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.findParticipant(HOST, USER).orElseThrow().isUploadAllowed()).isTrue();
    }

    @Test
    void ebics_rejects_a_btd_download_for_a_user_without_download_permission() throws Exception {
        service.setParticipantPermissions(HOST, USER, null, false, null);

        assertThat(post(signed(btdRequest("camt.053")))).contains("090003");
    }

    @Test
    void ebics_does_not_reject_a_btd_download_for_a_user_with_download_permission() throws Exception {
        assertThat(post(signed(btdRequest("camt.053")))).doesNotContain("090003").contains("000000");
    }

    @Test
    void mt7xx_pull_needs_its_own_lc_permission_while_statements_stay_allowed() throws Exception {
        service.setParticipantPermissions(HOST, USER, null, null, false);

        assertThat(post(signed(btdRequest("TRC")))).contains("090003");
        assertThat(post(signed(btdRequest("camt.053")))).doesNotContain("090003").contains("000000");
    }

    @Test
    void lc_permission_alone_does_not_override_a_missing_download_permission() throws Exception {
        service.setParticipantPermissions(HOST, USER, null, false, true);

        assertThat(post(signed(btdRequest("TRC")))).contains("090003");
    }

    // ---- Request-signature enforcement (see EbicsController.verifyDownloadSignature) ----

    /** A download order without a valid X002 request signature is rejected with
     * EBICS_INVALID_REQUEST (091002) instead of a successful download initialization - before
     * this check existed, anyone who merely knew HostID/PartnerID/UserID could trigger statement
     * generation and download transactions. */
    @Test
    void an_unsigned_btd_download_is_rejected() {
        assertThat(post(btdRequest("camt.053"))).contains("091002").doesNotContain("000000");
    }

    /** A signature over a DIFFERENT envelope must not unlock this one: reuse the complete
     * AuthSignature block (sign() flattens the ds:* children directly under AuthSignature) from
     * a validly signed request on an unsigned envelope with different order parameters. */
    @Test
    void a_tampered_btd_download_is_rejected() throws Exception {
        String signed = signed(btdRequest("camt.053"));
        String authSignatureBlock = signed.substring(signed.indexOf("<AuthSignature>"),
                signed.indexOf("</AuthSignature>") + "</AuthSignature>".length());
        String tampered = btdRequest("MT700").replace("<AuthSignature/>", authSignatureBlock);
        assertThat(post(tampered)).contains("091002").doesNotContain("000000");
    }
}
