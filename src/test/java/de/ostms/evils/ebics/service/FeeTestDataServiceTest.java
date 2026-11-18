package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FeeTestDataServiceTest {
    private static final String IBAN = "DE00123456789012345678";
    @Autowired FeeTestDataService fees;
    @Autowired TransactionService transactions;

    private List<Transaction> feeEntries() {
        return transactions.find(IBAN, LocalDate.now().minusMonths(12), LocalDate.now())
                .stream().filter(t -> t.transactionId().startsWith("TX-FEE-" + IBAN)).toList();
    }

    @Test
    void createsMonthlyClosingFeesAndAzvSettlementsPerPaymentIdempotently() {
        var first = fees.generate(IBAN, 3, 3);

        assertThat(first.azvZahlungen()).isEqualTo(9);
        // je AZV-Zahlung ein Entgelt, bei Kosten OUR (jede dritte) zusaetzlich Fremdspesen
        assertThat(first.azvEntgelte()).isEqualTo(9 + 3);
        assertThat(first.monatsabschluss()).isGreaterThanOrEqualTo(3 * 3);

        List<Transaction> entries = feeEntries();
        assertThat(entries).extracting(Transaction::purpose)
                .anyMatch(p -> p.startsWith("Entgelt Kontoführung"))
                .anyMatch(p -> p.startsWith("Entgelt elektronischer Zahlungsverkehr EBICS"))
                .anyMatch(p -> p.startsWith("AZV-Entgelt Zahlung AZV-"))
                .anyMatch(p -> p.startsWith("Fremdspesen Korrespondenzbank"))
                .anyMatch(p -> p.startsWith("AZV Zahlung AZV-"));
        // Gebuehren sind Belastungen
        assertThat(entries.stream().filter(t -> t.purpose().startsWith("Entgelt") || t.purpose().startsWith("AZV-Entgelt"))
                .allMatch(t -> !t.credit())).isTrue();

        int before = entries.size();
        var second = fees.generate(IBAN, 3, 3);

        assertThat(second.monatsabschluss()).isZero();
        assertThat(second.azvZahlungen()).isZero();
        assertThat(second.azvEntgelte()).isZero();
        assertThat(feeEntries()).hasSize(before);
    }

    @Test
    void rejectsUnknownIbanAndOutOfRangeParameters() {
        assertThatThrownBy(() -> fees.generate("DE99UNKNOWN", 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fees.generate(IBAN, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fees.generate(IBAN, 3, 11)).isInstanceOf(IllegalArgumentException.class);
    }
}
