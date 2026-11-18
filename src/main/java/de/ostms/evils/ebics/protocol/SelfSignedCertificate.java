package de.ostms.evils.ebics.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Real EBICS clients (e.g. kopi-ebics-client) require the public keys in INI/HIA and HPB to be
 * wrapped in an X.509 certificate (ds:X509Data/ds:X509Certificate), not the plain
 * PubKeyValue/RSAKeyValue fragment (see PubKeyValueXml's own scope note) - the client library
 * doesn't validate the certificate chain here, only reads the subject's public key back out of
 * it, so this self-signs with a disposable signing key rather than needing the bank's real
 * private key.
 */
final class SelfSignedCertificate {

    private SelfSignedCertificate() {
    }

    static byte[] wrap(PublicKey subjectPublicKey) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair signingKeyPair = generator.generateKeyPair();

            X500Name name = new X500Name("CN=EVILS Test Bank");
            Date notBefore = new Date();
            Date notAfter = new Date(notBefore.getTime() + TimeUnit.DAYS.toMillis(3650));
            BigInteger serial = BigInteger.valueOf(SecureRandomHolder.RANDOM.nextLong() & Long.MAX_VALUE);

            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, serial, notBefore, notAfter, name, subjectPublicKey);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(signingKeyPair.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            return cert.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build self-signed certificate", e);
        }
    }

    private static final class SecureRandomHolder {
        static final SecureRandom RANDOM = new SecureRandom();
    }
}
