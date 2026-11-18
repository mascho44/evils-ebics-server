package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

/** The user's EBICS payment signature class, not an allowed order type. iban="" is the
 * user's account-wide default; a non-empty iban overrides that default for one account.
 * New table (account_payment_authorizations) rather than reusing the old single-key
 * payment_authorizations table, to avoid a ddl-auto=update primary-key migration. */
@Entity(name = "account_payment_authorizations")
@IdClass(PaymentAuthorizationId.class)
public class PaymentAuthorization {
    public static final String DEFAULT_ACCOUNT = "";
    @Id private String userId;
    @Id private String iban;
    private String signatureClass;
    protected PaymentAuthorization() { }
    public PaymentAuthorization(String userId, String iban, String signatureClass) {
        this.userId = userId;
        this.iban = iban == null ? DEFAULT_ACCOUNT : iban;
        this.signatureClass = signatureClass;
    }
    public String getUserId() { return userId; }
    public String getIban() { return iban; }
    public String getSignatureClass() { return signatureClass; }
    public void setSignatureClass(String signatureClass) { this.signatureClass = signatureClass; }
}
