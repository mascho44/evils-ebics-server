package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.Transaction;
import de.ostms.evils.ebics.persistence.AccountRepository;
import de.ostms.evils.ebics.persistence.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The booking ledger. Adds credit and debit entries to an account, finds the entries of an account and
 * period, deletes them and creates demo data. All statements (camt.052/053/054, MT940, MT942) are rendered
 * from these entries on demand, so deleting an entry also changes statements generated afterwards; no
 * statement file is stored.
 */
@Service
public class TransactionService {

    private final TransactionRepository entries;
    private final AccountRepository accounts;
    public TransactionService(TransactionRepository entries, AccountRepository accounts) { this.entries=entries; this.accounts=accounts; }
    @Transactional public void seedDemoData() {
        if (entries.count() != 0) return;
        addCredit(
                "TX-DEMO-001",
                "DE00123456789012345678",
                LocalDate.now().minusDays(2),
                new BigDecimal("12500.00"),
                "Demo Kunde GmbH",
                "DE00987654321098765432",
                "DEMO BANK",
                "Rechnung 2026-1001",
                "E2E-DEMO-001"
        );

        addDebit(
                "TX-DEMO-002",
                "DE00123456789012345678",
                LocalDate.now().minusDays(1),
                new BigDecimal("1850.00"),
                "EVILS Lieferant GmbH",
                "DE00111111111111111111",
                "DEMO BANK",
                "Lieferantenrechnung",
                "E2E-DEMO-002"
        );
    }

    public Transaction addCredit(
            String id, String iban, LocalDate date, BigDecimal amount,
            String counterparty, String counterpartyIban, String bic,
            String purpose, String endToEndId) {
        return add(id, iban, date, date, amount, true, counterparty,
                counterpartyIban, bic, purpose, endToEndId);
    }

    public Transaction addDebit(
            String id, String iban, LocalDate date, BigDecimal amount,
            String counterparty, String counterpartyIban, String bic,
            String purpose, String endToEndId) {
        return add(id, iban, date, date, amount, false, counterparty,
                counterpartyIban, bic, purpose, endToEndId);
    }

    @Transactional public Transaction add(
            String id, String iban, LocalDate date, LocalDate valueDate, BigDecimal amount,
            boolean credit, String counterparty, String counterpartyIban,
            String bic, String purpose, String endToEndId) {

        var account=accounts.findById(iban).orElseThrow(() -> new IllegalArgumentException("Unknown IBAN: " + iban));
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        String transactionId = id == null || id.isBlank() ? "TX-" + UUID.randomUUID() : id;
        if (entries.existsById(transactionId)) throw new IllegalArgumentException("Duplicate transactionId: " + transactionId);
        Transaction tx = new Transaction(
                transactionId,
                iban,
                valueDate == null ? date : valueDate,
                date,
                amount,
                account.getCurrency(),
                credit,
                counterparty,
                counterpartyIban,
                bic,
                purpose,
                endToEndId,
                LocalDateTime.now()
        );

        return entries.save(tx);
    }

    public boolean exists(String transactionId) { return entries.existsById(transactionId); }

    public List<Transaction> find(String iban, LocalDate from, LocalDate to) {
        return entries.findByIbanAndBookingDateBetweenOrderByBookingDateAscTransactionIdAsc(iban, from, to);
    }

    public List<Transaction> all() {
        return entries.findAllByOrderByBookingDateAscTransactionIdAsc();
    }

    @Transactional
    public void delete(String transactionId) {
        if (!entries.existsById(transactionId)) throw new IllegalArgumentException("Unknown transaction: " + transactionId);
        entries.deleteById(transactionId);
    }

    /** CAMT statements are generated from these ledger entries. Removing them immediately removes
     * their contents from subsequently generated statements; no XML statement payload is stored. */
    @Transactional
    public long deleteForAccount(String iban) {
        if (!accounts.existsById(iban)) throw new IllegalArgumentException("Unknown IBAN: " + iban);
        return entries.deleteByIban(iban);
    }

    public List<Transaction> before(String iban, LocalDate date) { return entries.findByIbanAndBookingDateBeforeOrderByBookingDateAscTransactionIdAsc(iban, date); }

    @Transactional
    public List<Transaction> generateDemoTransactions(String iban, int count) {
        if (!accounts.existsById(iban)) throw new IllegalArgumentException("Unknown IBAN: " + iban);
        if (count < 1 || count > 1000) throw new IllegalArgumentException("count must be between 1 and 1000");
        List<Transaction> generated = new ArrayList<>();
        String[] creditors = {"Musterkunde GmbH", "Nordwind AG", "Contoso Services", "Fabrikam KG"};
        String[] debtors = {"Bürobedarf Beispiel", "Cloud Hosting GmbH", "Versicherung Demo", "Lieferant Europa"};
        for (int i = 0; i < count; i++) {
            boolean credit = i % 3 != 0;
            LocalDate date = LocalDate.now().minusDays((long) (count - i) % 90);
            // €25,000.00–€1,999,000.00: suitable for meaningful CAMT statement simulations,
            // while preserving deterministic variation across generated entries.
            BigDecimal amount = BigDecimal.valueOf(2_500_000L + ((i * 73_000L) % 197_400_000L), 2);
            String id = "TX-GEN-" + UUID.randomUUID();
            generated.add(add(id, iban, date, date, amount, credit,
                    credit ? creditors[i % creditors.length] : debtors[i % debtors.length],
                    null, "EVILDEFFXXX", credit ? "Generierte Kundenzahlung" : "Generierte Betriebsausgabe",
                    "E2E-GEN-" + (i + 1)));
        }
        return generated;
    }
}
