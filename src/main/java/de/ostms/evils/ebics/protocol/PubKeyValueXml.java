package de.ostms.evils.ebics.protocol;

import org.w3c.dom.Element;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.io.ByteArrayInputStream;

/**
 * Reads and writes the real EBICS/XML-DSig {@code <PubKeyValue><ds:RSAKeyValue>...} fragment used
 * inside SignaturePubKeyOrderData (INI), HIARequestOrderData (HIA) and, on the way back, an HPB
 * response - the same wire format any genuine EBICS client library produces, unlike the rest of
 * this simulator's simplified EVILS*-prefixed informational payloads. Kept as a small standalone
 * class (not folded into the request/response builders) so both directions share one definition
 * of the fragment.
 */
public final class PubKeyValueXml {

    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";

    private PubKeyValueXml() {
    }

    /** {@code pubKeyValue} must be a {@code <PubKeyValue>} element containing a nested
     * {@code ds:RSAKeyValue> with ds:Modulus/ds:Exponent (both base64). */
    public static RSAPublicKey parse(Element pubKeyValue) {
        Element rsaKeyValue = child(pubKeyValue, "RSAKeyValue");
        if (rsaKeyValue == null) {
            throw new EbicsProtocolException("PubKeyValue is missing RSAKeyValue");
        }
        Element modulusEl = child(rsaKeyValue, "Modulus");
        Element exponentEl = child(rsaKeyValue, "Exponent");
        if (modulusEl == null || exponentEl == null) {
            throw new EbicsProtocolException("RSAKeyValue is missing Modulus or Exponent");
        }
        try {
            BigInteger modulus = new BigInteger(1, Base64.getMimeDecoder().decode(modulusEl.getTextContent().trim()));
            BigInteger exponent = new BigInteger(1, Base64.getMimeDecoder().decode(exponentEl.getTextContent().trim()));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new EbicsProtocolException("Invalid RSA key material in PubKeyValue", e);
        }
    }

    /** {@code keyInfo} is a *PubKeyInfo element (SignaturePubKeyInfo, AuthenticationPubKeyInfo,
     * EncryptionPubKeyInfo) and may carry the key either as a direct {@code <PubKeyValue>}
     * (what {@link #render} produces, and what a minimal/simplified client might send) or as a
     * {@code <ds:X509Data><ds:X509Certificate>} self-signed certificate - which is what the real
     * kopi-ebics-client library actually sends for INI/HIA (see EbicsXmlFactory#createX509DataType
     * in that library; its PubKeyValue code path is dead/commented out). Both forms carry the same
     * RSA public key, just wrapped differently. */
    public static RSAPublicKey parseFromKeyInfo(Element keyInfo) {
        Element pubKeyValue = child(keyInfo, "PubKeyValue");
        if (pubKeyValue != null) {
            return parse(pubKeyValue);
        }
        Element x509Data = child(keyInfo, "X509Data");
        if (x509Data != null) {
            return parseFromX509Data(x509Data);
        }
        throw new EbicsProtocolException("Key info is missing both PubKeyValue and X509Data");
    }

    private static RSAPublicKey parseFromX509Data(Element x509Data) {
        Element certEl = child(x509Data, "X509Certificate");
        if (certEl == null) {
            throw new EbicsProtocolException("X509Data is missing X509Certificate");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(certEl.getTextContent().trim());
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
            return (RSAPublicKey) cert.getPublicKey();
        } catch (IllegalArgumentException | CertificateException | ClassCastException e) {
            throw new EbicsProtocolException("Invalid X509Certificate in X509Data", e);
        }
    }

    /** Renders the same fragment for outgoing responses - the counterpart to {@link #parse}.
     * Kept for round-trip testing and as the simpler of the two wire formats, but real EBICS
     * clients (see {@link #renderX509}) don't actually accept this for HPB. */
    public static String render(RSAPublicKey key) {
        String modulus = Base64.getEncoder().encodeToString(unsigned(key.getModulus()));
        String exponent = Base64.getEncoder().encodeToString(unsigned(key.getPublicExponent()));
        return "<PubKeyValue><ds:RSAKeyValue xmlns:ds=\"%s\"><ds:Modulus>%s</ds:Modulus><ds:Exponent>%s</ds:Exponent></ds:RSAKeyValue></PubKeyValue>"
                .formatted(DS_NS, modulus, exponent);
    }

    /** Wraps the key in a self-signed X.509 certificate instead (see SelfSignedCertificate) - the
     * format a real EBICS client (e.g. kopi-ebics-client's HPBResponseOrderDataElement) actually
     * requires for the bank's own keys in an HPB response; it has no PubKeyValue fallback. */
    public static String renderX509(RSAPublicKey key) {
        byte[] der = SelfSignedCertificate.wrap(key);
        return "<ds:X509Data xmlns:ds=\"%s\"><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>"
                .formatted(DS_NS, Base64.getEncoder().encodeToString(der));
    }

    /** BigInteger.toByteArray() may carry a leading zero sign byte that a real EBICS client would
     * not expect back out of Modulus/Exponent - strip it so render(parse(x)) round-trips exactly. */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
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
}
