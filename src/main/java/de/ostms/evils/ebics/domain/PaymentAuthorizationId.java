package de.ostms.evils.ebics.domain;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link PaymentAuthorization}: a user's signature class, optionally
 * scoped to one account (iban=""  means the user's account-wide default). */
public class PaymentAuthorizationId implements Serializable {
    private String userId;
    private String iban;
    protected PaymentAuthorizationId() { }
    public PaymentAuthorizationId(String userId, String iban) { this.userId = userId; this.iban = iban; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentAuthorizationId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(iban, that.iban);
    }
    @Override public int hashCode() { return Objects.hash(userId, iban); }
}
