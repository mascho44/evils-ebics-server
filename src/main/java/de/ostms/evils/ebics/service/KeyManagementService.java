package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.KeyInitialization;
import de.ostms.evils.ebics.domain.Participant;
import de.ostms.evils.ebics.domain.ParticipantKey;
import de.ostms.evils.ebics.persistence.KeyInitializationRepository;
import de.ostms.evils.ebics.persistence.ParticipantKeyRepository;
import de.ostms.evils.ebics.persistence.ParticipantRepository;
import de.ostms.evils.ebics.protocol.EbicsProtocolException;
import de.ostms.evils.ebics.protocol.EbicsRequest;
import de.ostms.evils.ebics.protocol.PubKeyValueXml;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * INI/HIA now parse and persist the customer's real public keys from the order data (see
 * PubKeyValueXml) instead of only flipping a status flag - this is the "0.6 A/E/X cryptography"
 * milestone from the README's own roadmap. Deliberately still missing: the bank never receives or
 * needs the customer's PRIVATE key (real EBICS never transmits it either), and no signature
 * verification against these keys happens yet for later payment orders - that is a separate,
 * later increment (order-data / transport-level signature checking).
 */
@Service
public class KeyManagementService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final ParticipantRepository participants;
    private final ParticipantKeyRepository participantKeys;
    private final KeyInitializationRepository initializations;

    public KeyManagementService(ParticipantRepository participants, ParticipantKeyRepository participantKeys,
                                 KeyInitializationRepository initializations) {
        this.participants = participants;
        this.participantKeys = participantKeys;
        this.initializations = initializations;
    }

    @Transactional
    public void process(EbicsRequest request) {
        String orderType = request.orderType().toUpperCase();
        Participant participant = entityManager.find(Participant.class,request.userId(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(participant==null)throw new EbicsProtocolException("Unknown UserID: " + request.userId());
        if (!participant.isActive()) throw new EbicsProtocolException("Inactive UserID: " + request.userId());
        if(!participant.getPartnerId().equals(request.partnerId()))throw new EbicsProtocolException("Participant does not belong to partner");
        switch (orderType) {
            case "INI" -> {
                Element info = orderDataChild(request, "SignaturePubKeyOrderData", "SignaturePubKeyInfo");
                storeKey(participant.getUserId(), "SIGNATURE", versionOf(info, "SignatureVersion"), info);
                participant.initializeSignatureKey();
            }
            case "HIA" -> {
                Element root = orderDataRoot(request, "HIARequestOrderData");
                Element authInfo = child(root, "AuthenticationPubKeyInfo");
                Element encInfo = child(root, "EncryptionPubKeyInfo");
                storeKey(participant.getUserId(), "AUTHENTICATION", versionOf(authInfo, "AuthenticationVersion"), authInfo);
                storeKey(participant.getUserId(), "ENCRYPTION", versionOf(encInfo, "EncryptionVersion"), encInfo);
                participant.initializeAuthenticationAndEncryptionKeys();
            }
            case "HPB" -> { /* Bank parameters are returned; no customer key is changed. */ }
            default -> throw new IllegalArgumentException("Unsupported key-management order: " + orderType);
        }
        initializations.save(new KeyInitialization(request.hostId(), request.partnerId(), request.userId(), orderType, request.version()));
    }

    public List<KeyInitialization> all() {
        return initializations.findAllByOrderByReceivedAtDesc();
    }

    @Transactional public Participant releaseSignatureKey(String userId){
        var participant=entityManager.find(Participant.class,userId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(participant==null)throw new IllegalArgumentException("Unknown UserID");
        participant.releaseSignatureKey();return participant;
    }

    public List<ParticipantKey> keysOf(String userId) {
        return participantKeys.findByUserIdOrderByReceivedAtDesc(userId);
    }

    /** Admin-triggered reset for a participant who needs to re-run EBICS initialization (e.g.
     * after losing their key material): deletes their stored public keys and reverts
     * Participant.*KeyStatus back to NOT_INITIALIZED. The audit trail in KeyInitialization is
     * deliberately left untouched - it is a historical log, not current state. */
    @Transactional
    public Participant reset(String userId) {
        Participant participant = participants.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown UserID: " + userId));
        participantKeys.deleteAll(participantKeys.findByUserIdOrderByReceivedAtDesc(userId));
        participant.resetKeys();
        return participants.save(participant);
    }

    /** The participant's current ENCRYPTION (E002) public key, as uploaded via HIA - HPB responses
     * must be encrypted with it (see EbicsResponseBuilder/EbicsEncryption), so it is an error to
     * request HPB before HIA has been completed. */
    public RSAPublicKey activeEncryptionKey(String userId) {
        return keysOf(userId).stream()
                .filter(k -> "ENCRYPTION".equals(k.getKeyType()))
                .findFirst()
                .map(k -> {
                    try {
                        byte[] encoded = Base64.getDecoder().decode(k.getPublicKeyBase64());
                        return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA")
                                .generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not decode stored participant public key", e);
                    }
                })
                .orElseThrow(() -> new EbicsProtocolException(
                        "No ENCRYPTION key on file for UserID " + userId + " - HIA must be completed before HPB"));
    }

    /** The participant's current AUTHENTICATION (X002) public key, as uploaded via HIA - download
     * orders must verify the request's transport signature against it (see EbicsController), the
     * same way PaymentUploadService already does for uploads. Rejecting with a protocol error
     * when none is on file is correct: a subscriber that never completed HIA cannot send a
     * properly signed request in the first place. */
    public java.security.PublicKey activeAuthenticationKey(String userId) {
        return keysOf(userId).stream()
                .filter(k -> "AUTHENTICATION".equals(k.getKeyType()))
                .findFirst()
                .map(k -> {
                    try {
                        byte[] encoded = Base64.getDecoder().decode(k.getPublicKeyBase64());
                        return java.security.KeyFactory.getInstance("RSA")
                                .generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not decode stored participant public key", e);
                    }
                })
                .orElseThrow(() -> new EbicsProtocolException(
                        "No AUTHENTICATION key on file for UserID " + userId + " - HIA must be completed first"));
    }

    private void storeKey(String userId, String keyType, String version, Element keyInfo) {
        if(keysOf(userId).stream().anyMatch(k->keyType.equals(k.getKeyType())))throw new EbicsProtocolException("Keys already submitted; administrator reset required before replacement");
        if (keyInfo == null) {
            throw new EbicsProtocolException(keyType + " key info is missing from the order data");
        }
        RSAPublicKey key = PubKeyValueXml.parseFromKeyInfo(keyInfo);
        String publicKeyBase64 = Base64.getEncoder().encodeToString(key.getEncoded());
        String algorithm = "RSA-" + key.getModulus().bitLength();
        participantKeys.save(new ParticipantKey(userId, keyType, version, algorithm, publicKeyBase64, fingerprint(key)));
    }

    private static String versionOf(Element info, String localName) {
        String version = info == null ? null : text(info, localName);
        return version == null ? "unspecified" : version;
    }

    /** Parses request.orderDataXml() (already base64-decoded by EbicsRequestParser) and returns
     * the root element, verifying it matches the order type's expected element name. */
    private static Element orderDataRoot(EbicsRequest request, String expectedRootName) {
        if (request.orderDataXml() == null) {
            throw new EbicsProtocolException("Request has no order data (missing body/DataTransfer/OrderData)");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(request.orderDataXml())));
            Element root = doc.getDocumentElement();
            if (!expectedRootName.equals(root.getLocalName())) {
                throw new EbicsProtocolException("Expected " + expectedRootName + " order data, got " + root.getLocalName());
            }
            return root;
        } catch (EbicsProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new EbicsProtocolException("Invalid order data XML", e);
        }
    }

    private static Element orderDataChild(EbicsRequest request, String expectedRootName, String childName) {
        return child(orderDataRoot(request, expectedRootName), childName);
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) return null;
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element parent, String localName) {
        Element e = child(parent, localName);
        return e == null ? null : e.getTextContent().trim();
    }

    private static String fingerprint(RSAPublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute key fingerprint", e);
        }
    }
}
