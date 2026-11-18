package de.ostms.evils.ebics.protocol;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PubKeyValueXmlTest {

    @Test
    void rendersAndParsesBackTheSameRsaKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey original = (RSAPublicKey) generator.generateKeyPair().getPublic();

        String xml = PubKeyValueXml.render(original);
        RSAPublicKey parsed = PubKeyValueXml.parse(parseElement(xml));

        assertThat(parsed.getModulus()).isEqualTo(original.getModulus());
        assertThat(parsed.getPublicExponent()).isEqualTo(original.getPublicExponent());
    }

    @Test
    void rejectsPubKeyValueWithoutRsaKeyValue() throws Exception {
        Element element = parseElement("<PubKeyValue/>");
        assertThrows(EbicsProtocolException.class, () -> PubKeyValueXml.parse(element));
    }

    @Test
    void rejectsRsaKeyValueMissingExponent() throws Exception {
        String xml = """
                <PubKeyValue><ds:RSAKeyValue xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                  <ds:Modulus>AQAB</ds:Modulus>
                </ds:RSAKeyValue></PubKeyValue>
                """;
        Element element = parseElement(xml);
        assertThrows(EbicsProtocolException.class, () -> PubKeyValueXml.parse(element));
    }

    @Test
    void parsesFromKeyInfoWithDirectPubKeyValue() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey original = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String xml = "<SignaturePubKeyInfo>" + PubKeyValueXml.render(original) + "</SignaturePubKeyInfo>";

        RSAPublicKey parsed = PubKeyValueXml.parseFromKeyInfo(parseElement(xml));

        assertThat(parsed.getModulus()).isEqualTo(original.getModulus());
    }

    /** Real EBICS client libraries (e.g. kopi-ebics-client) don't actually send PubKeyValue for
     * INI/HIA - they wrap the public key in a self-signed X.509 certificate instead (its
     * PubKeyValue code path is dead/commented out). This is a real cert/key pair generated once
     * via openssl, not created in-test, to avoid pulling in a certificate-building dependency. */
    private static final String SELF_SIGNED_CERT_DER_BASE64 =
            "MIIC/zCCAeegAwIBAgIUOLTVp1dnqxMVIbi774ePhooJWY8wDQYJKoZIhvcNAQELBQAwDzENMAsGA1UEAwwEdGVzdDAeFw0yNjA5MTYwNTM2NTlaFw0yNjA5MTcwNTM2NTlaMA8xDTALBgNVBAMMBHRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDDA3b7jgacfuNDRvy9FHJXXlWuXQcsA7j7gcVwTfDHif5sqY9yYp8tkek4A2OEl23eNWp7z7e8DFxK9y9SBia/EexZv+mMYkd0E4MPnvGBorhJBwEI32PuREYh5BchH+qgKGWERiKQW1Q0UpKwOAk4ewHlyExAgzRyZm2o7TAf8DkwWk/R7krprPRVd94Esk3pRw0oyuiHyu6eUK+MBG4mLQlyOWLeTMMZ0qKM8keZK4PB7Syyr+e5qWroM2H1S1N41+5Bc9LEPt/XCRSK3S7UZ/oC8PnZQueUKHCswcWRQN6sLp3To6Fw7XzSu7iQeMmHdwBTY4Gqn3SLN1R3iw6dAgMBAAGjUzBRMB0GA1UdDgQWBBRN6FCjqV6JdV7oTubGu6N7qOe3xTAfBgNVHSMEGDAWgBRN6FCjqV6JdV7oTubGu6N7qOe3xTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBxdzQ8qH2P088tBWG6Ma1aFOa97gfRgOCUQ9p9MzIwK0j2OyCmRBGKseh0BbTJV8swIzRt5oYrPfNlBgQTf4BDDl1H/yVMJ/l3g/8VSp3myzAf8NKxLcdHKqM9nBve/3+F5h8ku5G8Ma/YoaTraooU1w/PQAONsjqpXDNMhUsELj3Ahh7k3Ow4AqkH+SxQ0rDqrwge4bCJoYR2qxmtfvvjxWhoJu39aHn05TxC2qAcHH5urggJ+BM3qW1l02bnJ07wgWaOPab90Ugi2tGBT6BDHOWUyTiqmNw10/lWqxd+zKZbYLurWXGBeCYDOPa2Sg6TDr6P0ArWzG6KL7xvIKhq";

    @Test
    void parsesFromKeyInfoWithX509DataLikeARealEbicsClientSends() throws Exception {
        String xml = "<AuthenticationPubKeyInfo><ds:X509Data xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"
                + "<ds:X509Certificate>" + SELF_SIGNED_CERT_DER_BASE64 + "</ds:X509Certificate>"
                + "</ds:X509Data></AuthenticationPubKeyInfo>";

        RSAPublicKey parsed = PubKeyValueXml.parseFromKeyInfo(parseElement(xml));

        assertThat(parsed.getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void rejectsKeyInfoWithNeitherPubKeyValueNorX509Data() throws Exception {
        Element element = parseElement("<AuthenticationPubKeyInfo/>");
        assertThrows(EbicsProtocolException.class, () -> PubKeyValueXml.parseFromKeyInfo(element));
    }

    private static Element parseElement(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return doc.getDocumentElement();
    }
}
