package de.ostms.evils.ebics.protocol;

import de.ostms.evils.ebics.domain.Bank;
import de.ostms.evils.ebics.domain.BankKey;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;

class EbicsResponseBuilderTest {

    @Test
    void hpbResponseUsesTheKeyManagementResponseRootElement() throws Exception {
        RSAPublicKey recipientKey = generateKeyPair().pub;
        String response = new EbicsResponseBuilder().hpb(
                new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "HPB", "ORDER-HPB", ""),
                new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX"),
                List.of(), recipientKey);
        assertThat(response).contains("<ebicsKeyManagementResponse").doesNotContain("<ebicsResponse");
        assertThat(response).contains("DataEncryptionInfo", "TransactionKey", "EncryptionPubKeyDigest");
    }

    @Test
    void hpbResponseCanBeDecryptedByTheRecipientAndContainsTheBankRealPublicKeys() throws Exception {
        var keyPair = generateKeyPair();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey authKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        RSAPublicKey encKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        List<BankKey> bankKeys = List.of(
                new BankKey("AUTHENTICATION", "RSA-2048", Base64.getEncoder().encodeToString(authKey.getEncoded()), "cipher", "salt", "iv", "fp1"),
                new BankKey("ENCRYPTION", "RSA-2048", Base64.getEncoder().encodeToString(encKey.getEncoded()), "cipher", "salt", "iv", "fp2"));

        String response = new EbicsResponseBuilder().hpb(
                new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "HPB", "ORDER-HPB", ""),
                new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX"),
                bankKeys, keyPair.pub);

        String transactionKeyB64 = extract(response, "TransactionKey");
        String orderDataB64 = extract(response, "OrderData");

        byte[] aesKey = rsaDecrypt(Base64.getDecoder().decode(transactionKeyB64), keyPair.priv);
        byte[] compressed = aesDecrypt(Base64.getDecoder().decode(orderDataB64), aesKey);
        String orderDataXml = new String(inflate(compressed), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(orderDataXml).contains("<HPBResponseOrderData", "AuthenticationPubKeyInfo", "EncryptionPubKeyInfo",
                "X002", "E002", "X509Certificate", "<HostID>EVILSBANK</HostID>");

        // Real EBICS clients extract the bank's key from an X.509 certificate, not a raw
        // PubKeyValue - verify the certificate actually embeds the real bank key.
        String certB64 = extract(orderDataXml, "ds:X509Certificate");
        var cert = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(certB64)));
        assertThat(((RSAPublicKey) cert.getPublicKey()).getModulus()).isEqualTo(authKey.getModulus());
    }

    @Test
    void keyManagementSuccessUsesTheKeyManagementResponseRootElement() {
        String response = new EbicsResponseBuilder().keyManagementSuccess(
                new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "INI", "ORDER-INI", ""));
        assertThat(response).contains("<ebicsKeyManagementResponse", "<ReturnCode>000000</ReturnCode>")
                .doesNotContain("<ebicsResponse");
    }

    @Test
    void keyManagementErrorUsesTheKeyManagementResponseRootElement() {
        String response = new EbicsResponseBuilder().keyManagementError("091002", "boom");
        assertThat(response).contains("<ebicsKeyManagementResponse", "<ReturnCode>091002</ReturnCode>", "boom")
                .doesNotContain("<ebicsResponse");
    }

    @Test
    void h004RequestsGetH004NamespacedResponses() {
        var h004Request = new EbicsRequest("H004", "EVILSBANK", "TEST001", "USER001", "INI", "ORDER-INI", "");
        assertThat(new EbicsResponseBuilder().keyManagementSuccess(h004Request))
                .contains("xmlns=\"urn:org:ebics:H004\"", "Version=\"H004\"")
                .doesNotContain("urn:org:ebics:H005");
        assertThat(new EbicsResponseBuilder().success(new EbicsRequest("H004", "EVILSBANK", "TEST001", "USER001", "HKD", "ORDER-HKD", "")))
                .contains("xmlns=\"urn:org:ebics:H004\"", "Version=\"H004\"");
    }

    @Test
    void h004AuthSignatureCoversTheH004NamespacedResponse() throws Exception {
        var keyPair = generateKeyPair();
        var request = new EbicsRequest("H004", "EVILSBANK", "TEST001", "USER001", "INI", "ORDER-INI", "");
        String unsigned = new EbicsResponseBuilder().keyManagementSuccess(request)
                .replace("<body>", "<AuthSignature/><body>");
        String signed = EbicsAuthentication.sign(unsigned, keyPair.priv);
        assertThat(signed).contains("ds:Signature", "urn:org:ebics:H004");
        var doc = de.ostms.evils.ebics.service.HpbCodec.parse(signed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        EbicsAuthentication.verify(doc, keyPair.pub);
    }

    @Test
    void hpdResponseContainsStoredBankProfile() {
        String response = new EbicsResponseBuilder().hpd(
                new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "HPD", "ORDER-HPD", ""),
                new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX"));
        assertThat(response).contains("EVILSBankProfile", "EVILS Test Bank", "EVILDEFFXXX", "<Simulator>true</Simulator>");
    }

    private record KeyPairHolder(RSAPublicKey pub, RSAPrivateKey priv) {
    }

    private static KeyPairHolder generateKeyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new KeyPairHolder((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
    }

    private static String extract(String xml, String element) {
        Matcher m = Pattern.compile("<" + element + "[^>]*>([^<]+)</" + element + ">").matcher(xml);
        if (!m.find()) throw new AssertionError("Element not found: " + element);
        return m.group(1);
    }

    private static byte[] rsaDecrypt(byte[] data, RSAPrivateKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    private static byte[] aesDecrypt(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        byte[] padded = cipher.doFinal(data);
        int padLength = padded[padded.length - 1] & 0xFF;
        byte[] unpadded = new byte[padded.length - padLength];
        System.arraycopy(padded, 0, unpadded, 0, unpadded.length);
        return unpadded;
    }

    private static byte[] inflate(byte[] data) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        var out = new java.io.ByteArrayOutputStream(data.length * 3);
        byte[] buffer = new byte[4096];
        while (!inflater.finished()) {
            int n = inflater.inflate(buffer);
            if (n == 0 && inflater.needsInput()) break;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
