package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.*;

import de.ostms.evils.ebics.persistence.*;
import de.ostms.evils.ebics.util.IbanGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Multi-tenant: every Bank row is an independent tenant (see de.ostms.evils.ebics.security.TenantContext),
 * identified by its hostId. Customer/Participant/Account keep globally-unique natural keys (same
 * as before this became multi-tenant) but are tagged with the hostId of the tenant that owns them
 * - hostId is used for admin-visibility scoping and defense-in-depth EBICS lookups, not as part of
 * a composite uniqueness domain. This keeps the many downstream call sites that look entities up
 * by their natural key alone (e.g. VeuService/PaymentReportService) correct without threading
 * hostId through all of them - those are only ever reached after EbicsController.process() has
 * already validated hostId+partnerId+userId consistency.
 */
@Service
public class BankService {

    private final BankRepository banks; private final CustomerRepository customers; private final ParticipantRepository participants; private final AccountRepository accounts; private final TransactionRepository transactions; private final PaymentOrderRepository paymentOrders; private final ParticipantKeyRepository participantKeys; private final PaymentAuthorizationRepository paymentAuthorizations; private final PaymentSignatureRepository paymentSignatures;
    public BankService(BankRepository banks, CustomerRepository customers, ParticipantRepository participants, AccountRepository accounts, TransactionRepository transactions, PaymentOrderRepository paymentOrders, ParticipantKeyRepository participantKeys, PaymentAuthorizationRepository paymentAuthorizations, PaymentSignatureRepository paymentSignatures) { this.banks=banks; this.customers=customers; this.participants=participants; this.accounts=accounts; this.transactions=transactions; this.paymentOrders=paymentOrders; this.participantKeys=participantKeys; this.paymentAuthorizations=paymentAuthorizations; this.paymentSignatures=paymentSignatures; }
    @Transactional public void seedDemoData() {
        if (banks.count() != 0) return;
        banks.save(new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX"));
        customers.save(new Customer("EVILSBANK", "TEST001", "EVILS Demo GmbH", true));
        participants.save(new Participant("EVILSBANK", "USER001", "TEST001", "Max Mustermann", true, "NOT_INITIALIZED", "NOT_INITIALIZED", "NOT_INITIALIZED"));
        accounts.save(new Account("EVILSBANK", "DE00123456789012345678", "EVILDEFFXXX", "TEST001", "EVILS Demo GmbH", new BigDecimal("100000.00"), LocalDate.now().minusDays(30), "EUR", true));
    }

    /**
     * One-time migration for an instance that existed before multi-tenancy: Hibernate's ddl-auto
     * adds the new hostId column as NULL on every pre-existing row, which would otherwise make
     * EbicsController's now-hostId-scoped lookups (and every admin endpoint) suddenly report
     * "Unknown PartnerID/UserID/IBAN" for data that used to work. Every such installation only
     * ever had the single hardcoded "EVILSBANK" tenant (see seedDemoData), so backfilling to it
     * is safe and matches what BankKeyService/other still-unscoped services already assumed.
     * AdminUser.hostId is deliberately left alone - it already defaults to NULL (SUPERADMIN),
     * which is the correct outcome for a pre-existing admin (see AdminUserService's class
     * comment on why the bootstrap admin becomes SUPERADMIN).
     */
    @Transactional public void backfillLegacyHostId() {
        customers.findAllByHostIdIsNull().forEach(this::backfillCustomer);
        participants.findAllByHostIdIsNull().forEach(this::backfillParticipant);
        accounts.findAllByHostIdIsNull().forEach(this::backfillAccount);
    }

    private void backfillCustomer(Customer customer) {
        customers.save(new Customer("EVILSBANK", customer.getPartnerId(), customer.getName(), customer.isActive()));
    }

    private void backfillParticipant(Participant participant) {
        Participant migrated = new Participant("EVILSBANK", participant.getUserId(), participant.getPartnerId(),
                participant.getName(), participant.isActive(), participant.getAuthenticationKeyStatus(),
                participant.getEncryptionKeyStatus(), participant.getSignatureKeyStatus());
        if (participant.isSignatureKeyReleased()) migrated.releaseSignatureKey();
        participants.save(migrated);
    }

    private void backfillAccount(Account account) {
        accounts.save(new Account("EVILSBANK", account.getIban(), account.getBic(), account.getPartnerId(),
                account.getAccountHolder(), account.getOpeningBalance(), account.getOpeningBalanceDate(),
                account.getCurrency(), account.isActive()));
    }

    public Bank bank(String hostId) {
        return banks.findById(hostId).orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + hostId));
    }

    public Optional<Bank> findBank(String hostId) {
        return banks.findById(hostId);
    }

    public Collection<Bank> banks() {
        return banks.findAll();
    }

    @Transactional
    public Bank createBank(String hostId, String hostName, String protocolVersion, String bic) {
        if (hostId == null || hostId.isBlank() || hostName == null || hostName.isBlank()) {
            throw new IllegalArgumentException("hostId and hostName are required");
        }
        if (banks.existsById(hostId)) throw new IllegalArgumentException("Duplicate HostID: " + hostId);
        return banks.save(new Bank(hostId, hostName, protocolVersion == null || protocolVersion.isBlank() ? "H005" : protocolVersion, bic));
    }

    /** Only allowed while the tenant is empty - mirrors deleteAccount/deleteParticipant's "can't
     * delete while X exists" guard style, avoids silently orphaning real (mock) test data. */
    @Transactional
    public void deleteBank(String hostId) {
        if (!banks.existsById(hostId)) throw new IllegalArgumentException("Unknown tenant: " + hostId);
        if (!customers.findAllByHostId(hostId).isEmpty()) {
            throw new IllegalStateException("Mandant kann nicht gelöscht werden, solange Kunden vorhanden sind.");
        }
        banks.deleteById(hostId);
    }

    public Collection<Customer> customersForTenant(String hostId) {
        return customers.findAllByHostId(hostId);
    }

    public Collection<Participant> participantsForTenant(String hostId) {
        return participants.findAllByHostId(hostId);
    }

    public Collection<Account> accountsForTenant(String hostId) {
        return accounts.findAllByHostId(hostId);
    }

    /** Unscoped - only for the EBICS protocol path (EbicsController.htd/btd), which has already
     * validated hostId/partnerId/userId consistency before reaching these callers. */
    public Collection<Account> accounts() {
        return accounts.findAll();
    }

    public Optional<Customer> findCustomer(String hostId, String partnerId) {
        return customers.findById(partnerId).filter(c -> hostId.equals(c.getHostId()));
    }

    public Optional<Participant> findParticipant(String hostId, String userId) {
        return participants.findById(userId).filter(p -> hostId.equals(p.getHostId()));
    }

    /** Unscoped - see findParticipant(hostId, userId) for the scoped variant used by the EBICS
     * protocol path and admin API. Kept for callers already downstream of that validation (e.g.
     * PaymentReportService.authenticate, VeuService.publish) that only have a bare userId. */
    public Optional<Participant> findParticipant(String userId) {
        return participants.findById(userId);
    }

    @Transactional
    public Customer createCustomer(String hostId, String partnerId, String name) {
        if (partnerId == null || partnerId.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("partnerId and name are required");
        }
        if (!banks.existsById(hostId)) throw new IllegalArgumentException("Unknown tenant: " + hostId);
        if (customers.existsById(partnerId)) throw new IllegalArgumentException("Duplicate PartnerID: " + partnerId);
        return customers.save(new Customer(hostId, partnerId, name, true));
    }

    @Transactional
    public Account createAccount(String hostId, String iban, String bic, String partnerId, String accountHolder,
                                  BigDecimal openingBalance, LocalDate openingBalanceDate, String currency) {
        if (iban == null || iban.isBlank() || accountHolder == null || accountHolder.isBlank()) {
            throw new IllegalArgumentException("iban and accountHolder are required");
        }
        Customer customer = customers.findById(partnerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown PartnerID: " + partnerId));
        if (!hostId.equals(customer.getHostId())) throw new IllegalArgumentException("Unknown PartnerID: " + partnerId);
        if (accounts.existsById(iban)) throw new IllegalArgumentException("Duplicate IBAN: " + iban);
        return accounts.save(new Account(hostId, iban, bic, partnerId, accountHolder,
                openingBalance == null ? BigDecimal.ZERO : openingBalance,
                openingBalanceDate == null ? LocalDate.now() : openingBalanceDate,
                currency == null || currency.isBlank() ? "EUR" : currency, true));
    }

    /** Fuer die "IBAN generieren"-Schaltflaeche im Testkonto-Anlegen-Dialog (siehe admin.html) -
     * eine frei getippte Test-IBAN ist erfahrungsgemaess die Fehlerquelle, wenn eine damit erzeugte
     * Zahlung spaeter mit "IBAN-Pruefsumme ist ungueltig" abgelehnt wird. Prueft global (nicht nur
     * innerhalb des Tenants) auf Kollision, da Account.iban ein global eindeutiger Natural Key ist
     * (siehe Klassenkommentar oben). */
    public String generiereUnbenutzteTestIban() {
        return IbanGenerator.zufaelligUndUnbenutzt(iban -> !accounts.existsById(iban));
    }

    @Transactional
    public Participant createParticipant(String hostId, String userId, String partnerId, String name) {
        if (userId == null || userId.isBlank() || partnerId == null || partnerId.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("userId, partnerId and name are required");
        }
        Customer customer = customers.findById(partnerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown PartnerID: " + partnerId));
        if (!hostId.equals(customer.getHostId())) throw new IllegalArgumentException("Unknown PartnerID: " + partnerId);
        if (participants.existsById(userId)) throw new IllegalArgumentException("Duplicate UserID: " + userId);
        return participants.save(new Participant(hostId, userId, partnerId, name, true, "NOT_INITIALIZED", "NOT_INITIALIZED", "NOT_INITIALIZED"));
    }

    @Transactional
    public Participant setParticipantActive(String hostId, String userId, boolean active) {
        Participant participant = participants.findById(userId)
                .filter(p -> hostId.equals(p.getHostId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown UserID: " + userId));
        participant.setActive(active);
        return participant;
    }

    /** Sets the participant's EBICS permissions: upload (BTU/FUL), download
     * (BTD) and MT7xx trade-finance download. A null argument leaves that permission unchanged,
     * so the admin UI can flip one switch at a time. */
    @Transactional
    public Participant setParticipantPermissions(String hostId, String userId, Boolean uploadAllowed,
                                                 Boolean downloadAllowed, Boolean lcDownloadAllowed) {
        Participant participant = participants.findById(userId)
                .filter(p -> hostId.equals(p.getHostId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown UserID: " + userId));
        if (uploadAllowed != null) participant.setUploadAllowed(uploadAllowed);
        if (downloadAllowed != null) participant.setDownloadAllowed(downloadAllowed);
        if (lcDownloadAllowed != null) participant.setLcDownloadAllowed(lcDownloadAllowed);
        return participant;
    }

    /**
     * Removes an EBICS participant and the credentials/authorization that belong only to that
     * participant. A participant who has already signed a payment cannot be removed: retaining
     * those signatures is essential for the order's audit trail, and the operator must first
     * resolve or archive the affected payment orders.
     */
    @Transactional
    public void deleteParticipant(String hostId, String userId) {
        Participant participant = participants.findById(userId).filter(p -> hostId.equals(p.getHostId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown UserID: " + userId));
        if (paymentSignatures.existsByUserId(userId)) {
            throw new IllegalStateException("Benutzer kann nicht gelöscht werden, weil er Zahlungsaufträge signiert hat.");
        }
        participantKeys.deleteByUserId(userId);
        paymentAuthorizations.deleteByUserId(userId);
        participants.delete(participant);
    }

    public Optional<Account> findAccount(String hostId, String iban) {
        return accounts.findById(iban).filter(a -> hostId.equals(a.getHostId()));
    }

    /** Unscoped - see findAccount(hostId, iban) for the scoped variant. Kept for callers already
     * downstream of hostId validation (e.g. StatementService, reached only via EbicsController's
     * already-validated request/participant/account chain). */
    public Optional<Account> findAccount(String iban) {
        return accounts.findById(iban);
    }

    @Transactional
    public void deleteAccount(String hostId, String iban) {
        Account account = accounts.findById(iban).filter(a -> hostId.equals(a.getHostId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown IBAN: " + iban));
        if (paymentOrders.existsByIban(iban)) throw new IllegalStateException("Konto kann nicht gelöscht werden, solange Zahlungsaufträge vorhanden sind.");
        transactions.deleteByIban(iban);
        accounts.delete(account);
    }
}
