package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.PaymentAuthorization;
import de.ostms.evils.ebics.domain.PaymentAuthorizationId;
import de.ostms.evils.ebics.persistence.AccountRepository;
import de.ostms.evils.ebics.persistence.ParticipantRepository;
import de.ostms.evils.ebics.persistence.PaymentAuthorizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Signature classes (E, A, B, T) of the EBICS subscribers. Each user has an account-wide default
 * class and can have an override per account; without any entry a user is technical (T) and may not
 * approve or submit payments. {@link #forAccount} resolves the override first and falls back to the default.
 */
@Service
public class PaymentAuthorizationService {
    private static final java.util.Set<String> CLASSES = java.util.Set.of("E", "A", "B", "T");
    private static final String DEFAULT_ACCOUNT = PaymentAuthorization.DEFAULT_ACCOUNT;
    private final PaymentAuthorizationRepository authorizations;
    private final ParticipantRepository participants;
    private final AccountRepository accounts;
    public PaymentAuthorizationService(PaymentAuthorizationRepository authorizations, ParticipantRepository participants, AccountRepository accounts) {
        this.authorizations = authorizations;
        this.participants = participants;
        this.accounts = accounts;
    }

    /** The user's account-wide default signature class. */
    public PaymentAuthorization forUser(String userId) {
        return authorizations.findById(new PaymentAuthorizationId(userId, DEFAULT_ACCOUNT))
                .orElse(new PaymentAuthorization(userId, DEFAULT_ACCOUNT, "T"));
    }

    /** The signature class for one account: a per-account override if one exists, otherwise
     * the user's account-wide default. */
    public PaymentAuthorization forAccount(String userId, String iban) {
        if (iban == null || iban.isBlank() || DEFAULT_ACCOUNT.equals(iban)) return forUser(userId);
        return authorizations.findById(new PaymentAuthorizationId(userId, iban)).orElseGet(() -> forUser(userId));
    }

    /** All authorizations held for a user: the account-wide default plus any per-account
     * overrides, default first. */
    public List<PaymentAuthorization> listForUser(String userId) {
        List<PaymentAuthorization> found = authorizations.findByUserId(userId);
        if (found.stream().noneMatch(a -> DEFAULT_ACCOUNT.equals(a.getIban()))) {
            found = new java.util.ArrayList<>(found);
            found.addFirst(forUser(userId));
        }
        return found.stream()
                .sorted(java.util.Comparator.comparing((PaymentAuthorization a) -> !DEFAULT_ACCOUNT.equals(a.getIban())).thenComparing(PaymentAuthorization::getIban))
                .toList();
    }

    /** Sets the account-wide default (iban blank/null) or a per-account override. */
    @Transactional public PaymentAuthorization set(String userId, String iban, String signatureClass) {
        if (!participants.existsById(userId)) throw new IllegalArgumentException("Unknown UserID: " + userId);
        String normalizedIban = iban == null ? DEFAULT_ACCOUNT : iban.trim();
        if (!DEFAULT_ACCOUNT.equals(normalizedIban) && !accounts.existsById(normalizedIban))
            throw new IllegalArgumentException("Unknown IBAN: " + normalizedIban);
        String value = signatureClass == null ? "" : signatureClass.trim().toUpperCase(Locale.ROOT);
        if (!CLASSES.contains(value)) throw new IllegalArgumentException("signatureClass must be E, A, B or T");
        var id = new PaymentAuthorizationId(userId, normalizedIban);
        return authorizations.save(authorizations.findById(id).map(a -> { a.setSignatureClass(value); return a; })
                .orElse(new PaymentAuthorization(userId, normalizedIban, value)));
    }

    @Transactional public void removeOverride(String userId, String iban) {
        if (iban == null || iban.isBlank() || DEFAULT_ACCOUNT.equals(iban)) throw new IllegalArgumentException("Der Konto-Override erfordert eine IBAN; der Standardwert kann nicht entfernt werden");
        authorizations.deleteById(new PaymentAuthorizationId(userId, iban));
    }

    @Transactional public void seedDemoAuthorization() { if (participants.existsById("USER001") && authorizations.findByUserId("USER001").isEmpty()) set("USER001", DEFAULT_ACCOUNT, "E"); }
}
