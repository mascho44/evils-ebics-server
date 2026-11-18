package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.Statement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Camt054GeneratorTest {
    @Test
    void generatesOneNotificationPerAccount() {
        Statement eur = new Statement("EUR-1", "DE00123456789012345678", "EUR",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        Statement usd = new Statement("USD-1", "DE00987654321098765432", "USD",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        String xml = new Camt054Generator().generateAll(List.of(eur, usd));

        assertEquals(2, xml.split("<Ntfctn>", -1).length - 1);
        assertTrue(xml.contains("<IBAN>DE00123456789012345678</IBAN>"));
        assertTrue(xml.contains("<IBAN>DE00987654321098765432</IBAN>"));
        assertTrue(xml.contains("<Ccy>EUR</Ccy>"));
        assertTrue(xml.contains("<Ccy>USD</Ccy>"));
    }
}
