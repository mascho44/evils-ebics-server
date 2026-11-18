package de.ostms.evils.ebics.protocol;

import de.ostms.evils.ebics.domain.Bank;
import de.ostms.evils.ebics.domain.BankKey;
import de.ostms.evils.ebics.domain.Participant;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Builds the EBICS response documents: HEV (an {@code H000} message with its own root element), plain
 * and key-management success and error responses, the single-segment download initialisation with the
 * order data encrypted for the subscriber's E002 key, the receipt acknowledgement and the "no data
 * available" answer. A response echoes the protocol version ({@code H004} or {@code H005}) of the request
 * it answers, because the two only differ in namespace and version attribute.
 */
public class EbicsResponseBuilder {

    /** H004 (EBICS 2.5) and H005 (EBICS 3.0) share the same X002/E002 transport crypto and
     * request/response shape - only the namespace and Version attribute actually differ, so
     * every response here echoes back whichever of the two the request used. Anything else
     * (including a null/unparsed request) falls back to H005. */
    private static String version(EbicsRequest request) {
        return request != null && "H004".equals(request.version()) ? "H004" : "H005";
    }
    private static String ns(EbicsRequest request) {
        return "H004".equals(version(request)) ? "urn:org:ebics:H004" : "urn:org:ebics:H005";
    }

    /**
     * EBICS version discovery (HEV) is the one H000 message a client sends
     * before it starts the H005 key-management flow.  It has a different root
     * element and namespace than ordinary H005 responses.
     */
    public String hev(boolean hostKnown) {
        String code = hostKnown ? "000000" : "091003";
        String report = hostKnown ? "[EBICS_OK] OK" : "Host unbekannt";
        // H004 (EBICS 2.5) is advertised alongside H005 (EBICS 3.0) - key management
        // (INI/HIA/HPB), the classic downloads (STA/VMK/C52/C53/C54/Z52/Z53/Z54/Z01) and the CCT upload accept
        // both; HTD/HPD/HKD and VEU are still H005-shaped.
        String version = hostKnown
                ? "<VersionNumber ProtocolVersion=\"H005\">03.00</VersionNumber>"
                + "<VersionNumber ProtocolVersion=\"H004\">02.50</VersionNumber>"
                : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsHEVResponse xmlns="http://www.ebics.org/H000"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.ebics.org/H000 ebics_hev.xsd">
                  <SystemReturnCode>
                    <ReturnCode>%s</ReturnCode>
                    <ReportText>%s</ReportText>
                  </SystemReturnCode>
                  %s
                </ebicsHEVResponse>
                """.formatted(code, escape(report), version);
    }

    public String success(EbicsRequest request) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static><HostID>%s</HostID></static>
                    <mutable>
                      <TransactionPhase>Initialisation</TransactionPhase>
                      <ReturnCode>000000</ReturnCode>
                      <ReportText>[EBICS_OK] OK</ReportText>
                    </mutable>
                  </header>
                  <body><ReturnCode authenticate="true">000000</ReturnCode></body>
                </ebicsResponse>
                """.formatted(ns(request), version(request), escape(request.hostId()));
    }

    /**
     * The single-segment download-initialisation response for an EBICS 3.0 BTD order (e.g. real
     * clients' camt.053 statement fetch, sent as AdminOrderType=BTD - see EbicsController). Like
     * HPB, a real client genuinely decrypts orderDataXml with its own E002 key (see
     * EbicsEncryption), and then expects a matching Receipt-phase exchange to close the
     * transaction (see downloadReceipt) before it considers the file received.
     */
    public String downloadInitialization(EbicsRequest request, byte[] orderDataXml,
                                          RSAPublicKey recipientEncryptionKey, String transactionIdHex) {
        EbicsEncryption.Envelope envelope = EbicsEncryption.encrypt(orderDataXml, recipientEncryptionKey);
        String digest = EbicsEncryption.sha256Base64(recipientEncryptionKey);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static>
                      <TransactionID>%s</TransactionID>
                      <NumSegments>1</NumSegments>
                    </static>
                    <mutable>
                      <TransactionPhase>Initialisation</TransactionPhase>
                      <SegmentNumber lastSegment="true">1</SegmentNumber>
                      <ReturnCode>000000</ReturnCode>
                      <ReportText>[EBICS_OK] OK</ReportText>
                    </mutable>
                  </header>
                  <AuthSignature/>
                  <body>
                    <DataTransfer><DataEncryptionInfo authenticate="true">
                      <EncryptionPubKeyDigest Algorithm="http://www.w3.org/2001/04/xmlenc#sha256" Version="E002">%s</EncryptionPubKeyDigest>
                      <TransactionKey>%s</TransactionKey>
                    </DataEncryptionInfo><OrderData>%s</OrderData></DataTransfer>
                    <ReturnCode authenticate="true">000000</ReturnCode>
                  </body>
                </ebicsResponse>
                """.formatted(ns(request), version(request), transactionIdHex, digest,
                Base64.getEncoder().encodeToString(envelope.transactionKey()),
                Base64.getEncoder().encodeToString(envelope.orderData()));
    }

    /** Acknowledges the client's Receipt-phase request that closes a download transaction.
     * ReturnCode 011000 (EBICS_DOWNLOAD_POSTPROCESS_DONE) is not a made-up code - a real client
     * library checks for this exact value to consider the download complete. */
    public String downloadReceipt(String transactionIdHex) {
        return downloadReceipt(transactionIdHex, null);
    }
    public String downloadReceipt(String transactionIdHex, EbicsRequest request) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static><TransactionID>%s</TransactionID></static>
                    <mutable>
                      <TransactionPhase>Receipt</TransactionPhase>
                      <ReturnCode>011000</ReturnCode>
                      <ReportText>[EBICS_DOWNLOAD_POSTPROCESS_DONE] Download postprocess done</ReportText>
                    </mutable>
                  </header>
                  <AuthSignature/>
                  <body><ReturnCode authenticate="true">000000</ReturnCode></body>
                </ebicsResponse>
                """.formatted(ns(request), version(request), transactionIdHex);
    }

    /** No-data-available response for a BTD download whose date range has nothing to report -
     * ReturnCode 090005 (EBICS_NO_DOWNLOAD_DATA_AVAILABLE) is what a real client checks for to
     * distinguish "nothing to download" from a genuine error. */
    public String downloadNoDataAvailable() {
        return downloadNoDataAvailable(null);
    }
    public String downloadNoDataAvailable(EbicsRequest request) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static/>
                    <mutable>
                      <TransactionPhase>Initialisation</TransactionPhase>
                      <ReturnCode>090005</ReturnCode>
                      <ReportText>[EBICS_NO_DOWNLOAD_DATA_AVAILABLE] No download data available</ReportText>
                    </mutable>
                  </header>
                  <AuthSignature/>
                  <body><ReturnCode authenticate="true">090005</ReturnCode></body>
                </ebicsResponse>
                """.formatted(ns(request), version(request));
    }

    /**
     * INI/HIA acknowledgement. Unlike ordinary order types (HKD, HPD, HAC, ...), the EBICS H005
     * schema requires key-management order types to be answered with an ebicsKeyManagementResponse
     * document, not an ebicsResponse - a client validating the response against the XSD rejects
     * the latter for INI/HIA/HPB with "not a valid ebicsKeyManagementResponse document".
     */
    public String keyManagementSuccess(EbicsRequest request) {
        return keyManagementResponse(request, null);
    }

    /** Same ebicsKeyManagementResponse shape as keyManagementSuccess(), but with an error ReturnCode. */
    public String keyManagementError(String returnCode, String reportText) {
        return keyManagementError(returnCode, reportText, null);
    }
    public String keyManagementError(String returnCode, String reportText, EbicsRequest request) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsKeyManagementResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static/>
                    <mutable>
                      <ReturnCode>%s</ReturnCode>
                      <ReportText>%s</ReportText>
                    </mutable>
                  </header>
                  <body><ReturnCode authenticate="true">%s</ReturnCode></body>
                </ebicsKeyManagementResponse>
                """.formatted(ns(request), version(request), returnCode, escape(reportText), returnCode);
    }

    /** Plain ebicsResponse error shape, for non-key-management order types (and requests too
     * malformed to even know their order type). */
    public String error(String returnCode) {
        return error(returnCode, null);
    }
    public String error(String returnCode, EbicsRequest request) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static/>
                    <mutable>
                      <TransactionPhase>Initialisation</TransactionPhase>
                      <ReturnCode>%s</ReturnCode>
                      <ReportText>Error %s</ReportText>
                    </mutable>
                  </header>
                  <body><ReturnCode authenticate="true">%s</ReturnCode></body>
                </ebicsResponse>
                """.formatted(ns(request), version(request), returnCode, returnCode, returnCode);
    }

    /**
     * Returns the bank's real active AUTHENTICATION (X002) and ENCRYPTION (E002) public keys
     * (see BankKeyService/BankKey) inside the same PubKeyValue/RSAKeyValue fragment a genuine
     * EBICS client would send for INI/HIA, wrapped in the real HPBResponseOrderData element the
     * EBICS H005 schema requires (AuthenticationPubKeyInfo, EncryptionPubKeyInfo, HostID - in
     * that order). Unlike this simulator's other, still-simplified download responses, a real
     * EBICS client genuinely decrypts this payload (see EbicsEncryption), so it must be encrypted
     * with the requesting participant's own E002 public key (recipientEncryptionKey) exactly the
     * way org.kopi.ebics.client.User#decrypt expects it, or a real client cannot read it at all.
     * bankKeys should contain the currently active AUTHENTICATION and ENCRYPTION keys; a missing
     * type is simply omitted (e.g. before generateActiveKeySet() has ever run).
     */
    public String hpb(EbicsRequest request, Bank bank, List<BankKey> bankKeys, RSAPublicKey recipientEncryptionKey) {
        String authKey = keyFragment(bankKeys, "AUTHENTICATION", "AuthenticationPubKeyInfo", "AuthenticationVersion", "X002");
        String encKey = keyFragment(bankKeys, "ENCRYPTION", "EncryptionPubKeyInfo", "EncryptionVersion", "E002");
        String orderDataXml = "<HPBResponseOrderData xmlns=\"" + ns(request) + "\">"
                + authKey + encKey + "<HostID>%s</HostID></HPBResponseOrderData>".formatted(escape(bank.hostId()));

        EbicsEncryption.Envelope envelope = EbicsEncryption.encrypt(
                orderDataXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), recipientEncryptionKey);
        String digest = EbicsEncryption.sha256Base64(recipientEncryptionKey);
        String dataTransfer = """
                <DataTransfer><DataEncryptionInfo authenticate="true">
                <EncryptionPubKeyDigest Algorithm="http://www.w3.org/2001/04/xmlenc#sha256" Version="E002">%s</EncryptionPubKeyDigest>
                <TransactionKey>%s</TransactionKey>
                </DataEncryptionInfo><OrderData>%s</OrderData></DataTransfer>
                """.formatted(digest,
                Base64.getEncoder().encodeToString(envelope.transactionKey()),
                Base64.getEncoder().encodeToString(envelope.orderData()));
        return keyManagementResponse(request, dataTransfer);
    }

    private String keyManagementResponse(EbicsRequest request, String dataTransferXml) {
        String body = dataTransferXml == null
                ? "<ReturnCode authenticate=\"true\">000000</ReturnCode>"
                : dataTransferXml + "<ReturnCode authenticate=\"true\">000000</ReturnCode>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsKeyManagementResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static/>
                    <mutable>
                      <ReturnCode>000000</ReturnCode>
                      <ReportText>[EBICS_OK] OK</ReportText>
                    </mutable>
                  </header>
                  <body>%s</body>
                </ebicsKeyManagementResponse>
                """.formatted(ns(request), version(request), body);
    }

    private String keyFragment(List<BankKey> bankKeys, String keyType, String infoElement, String versionElement, String version) {
        return bankKeys.stream()
                .filter(k -> keyType.equals(k.getKeyType()))
                .findFirst()
                .map(k -> "<%s>%s<%s>%s</%s></%s>".formatted(infoElement, PubKeyValueXml.renderX509(toRsaPublicKey(k)), versionElement, version, versionElement, infoElement))
                .orElse("");
    }

    private static RSAPublicKey toRsaPublicKey(BankKey key) {
        try {
            byte[] encoded = Base64.getDecoder().decode(key.getPublicKeyBase64());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("Could not decode stored bank public key", e);
        }
    }

    /** Development-only HPD representation of the bank's stored parameters. */
    public String hpd(EbicsRequest request, Bank bank) {
        String parameters = "<EVILSBankProfile><HostID>%s</HostID><Name>%s</Name><BIC>%s</BIC><ProtocolVersion>%s</ProtocolVersion>"
                .formatted(escape(bank.hostId()), escape(bank.hostName()), escape(bank.bic()), escape(bank.protocolVersion()))
                + "<Simulator>true</Simulator></EVILSBankProfile>";
        return informationResponse(request, parameters);
    }

    /** Development-only HKD representation of a participant and its simulated key states. */
    public String hkd(EbicsRequest request, Participant participant) {
        String parameters = "<EVILSParticipantProfile><UserID>%s</UserID><PartnerID>%s</PartnerID><Name>%s</Name><Active>%s</Active>"
                .formatted(escape(participant.getUserId()), escape(participant.getPartnerId()), escape(participant.getName()), participant.isActive())
                + "<SignatureKeyStatus>%s</SignatureKeyStatus><AuthenticationKeyStatus>%s</AuthenticationKeyStatus><EncryptionKeyStatus>%s</EncryptionKeyStatus><Simulator>true</Simulator></EVILSParticipantProfile>"
                .formatted(escape(participant.getSignatureKeyStatus()), escape(participant.getAuthenticationKeyStatus()), escape(participant.getEncryptionKeyStatus()));
        return informationResponse(request, parameters);
    }

    private String informationResponse(EbicsRequest request, String parameters) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsResponse xmlns="%s" Version="%s">
                  <header authenticate="true">
                    <static><HostID>%s</HostID></static>
                    <mutable>
                      <TransactionPhase>Initialisation</TransactionPhase>
                      <ReturnCode>000000</ReturnCode>
                      <ReportText>[EBICS_OK] OK</ReportText>
                    </mutable>
                  </header>
                  <body><DataTransfer><OrderData><![CDATA[%s]]></OrderData></DataTransfer><ReturnCode authenticate="true">000000</ReturnCode></body>
                </ebicsResponse>
                """.formatted(ns(request), version(request), escape(request.hostId()), parameters);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
