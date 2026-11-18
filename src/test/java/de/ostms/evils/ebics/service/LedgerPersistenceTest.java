package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LedgerPersistenceTest {
    private static final String IBAN = "DE00123456789012345678";
    @Autowired TransactionService transactions;
    @Autowired StatementService statements;

    @Test
    void statementUsesEntriesBeforeTheRequestedPeriodForItsOpeningBalance() {
        LocalDate day = LocalDate.now().plusDays(20);
        // The account is shared with other tests (demo data, generated bookings), whose number and order
        // differ between machines - so measure the starting balance instead of assuming a fixed one.
        BigDecimal base = statements.createStatement(IBAN, day.minusDays(2), day.minusDays(2)).openingBalance();
        transactions.addCredit("TX-LEDGER-OPENING", IBAN, day.minusDays(2), new BigDecimal("10.00"), "Sender", "DE00", "BIC", "prior", "E2E-PRIOR");
        transactions.addDebit("TX-LEDGER-PERIOD", IBAN, day, new BigDecimal("3.00"), "Receiver", "DE01", "BIC", "period", "E2E-PERIOD");

        Statement statement = statements.createStatement(IBAN, day, day);

        assertThat(statement.openingBalance()).isEqualByComparingTo(base.add(new BigDecimal("10.00")));
        assertThat(statement.closingBalance()).isEqualByComparingTo(base.add(new BigDecimal("7.00")));
        assertThat(statement.transactions()).extracting(t -> t.transactionId()).contains("TX-LEDGER-PERIOD");
    }
}
