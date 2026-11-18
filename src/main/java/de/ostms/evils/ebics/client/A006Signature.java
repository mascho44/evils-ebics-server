package de.ostms.evils.ebics.client;

import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/** A006: RSASSA-PSS applied to the SHA-256 order-data digest (double hashing). */
public final class A006Signature {
    private static final PSSParameterSpec PARAMETERS =
        new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
    private A006Signature() {}

    public static byte[] sign(byte[] orderData, PrivateKey key) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("RSASSA-PSS");
        signer.setParameter(PARAMETERS);
        signer.initSign(key);
        signer.update(digest(orderData));
        return signer.sign();
    }

    public static boolean verify(byte[] orderData, byte[] signature, PublicKey key) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(PARAMETERS);
        verifier.initVerify(key);
        verifier.update(digest(orderData));
        return verifier.verify(signature);
    }

    /** EBICS order-data hashing removes CR, LF and SUB, but retains spaces/tabs. */
    public static byte[] digest(byte[] orderData) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte b : orderData) if (b != 13 && b != 10 && b != 26) digest.update(b);
        return digest.digest();
    }
}
