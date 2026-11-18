package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Camt053GeneratorTest {
    @Test void generatedLongReferencesAndNegativeBalancesValidate()throws Exception{
        var tx=new Transaction("TX-GEN-"+java.util.UUID.randomUUID(),"DE89370400440532013000",LocalDate.now(),LocalDate.now(),new BigDecimal("123.00"),"EUR",false,"Test",null,null,"Transfer",null,LocalDateTime.now());
        var statement=new Statement("STMT-DE89370400440532013000-2026-01-01-2026-12-31","DE89370400440532013000","EUR",LocalDate.now(),LocalDate.now(),BigDecimal.ZERO,new BigDecimal("-123.00"),List.of(tx));
        de.ostms.evils.ebics.protocol.SchemaAssertions.camt(new Camt053Generator().generate(statement));
    }

    @Test
    void generatesStatementXml() {
        Transaction tx = new Transaction(
                "TX1",
                "DE00123456789012345678",
                LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 10),
                new BigDecimal("100.00"),
                "EUR",
                true,
                "Test GmbH",
                "DE00987654321098765432",
                "TESTDEFFXXX",
                "Test payment",
                "E2E-1",
                LocalDateTime.now()
        );

        Statement s = new Statement(
                "STMT-1",
                "DE00123456789012345678",
                "USD",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 10),
                new BigDecimal("1000.00"),
                new BigDecimal("1100.00"),
                List.of(tx)
        );

        String xml = new Camt053Generator().generate(s);

        assertTrue(xml.contains("camt.053.001.08"));
        assertTrue(xml.contains("DE00123456789012345678"));
        assertTrue(xml.contains("100.00"));
        assertTrue(xml.contains("E2E-1"));
        assertTrue(xml.contains("<Ccy>USD</Ccy>"));
        assertTrue(xml.contains("Amt Ccy=\"USD\""));
    }

    @Test
    void generatesOneStatementBlockPerAccount() {
        Statement eur = new Statement(
                "STMT-EUR", "DE00123456789012345678", "EUR",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10),
                new BigDecimal("1.00"), new BigDecimal("2.00"), List.of());
        Statement usd = new Statement(
                "STMT-USD", "DE00987654321098765432", "USD",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10),
                new BigDecimal("3.00"), new BigDecimal("4.00"), List.of());

        String xml = new Camt053Generator().generateAll(List.of(eur, usd));

        assertEquals(2, xml.split("<Stmt>", -1).length - 1);
        assertTrue(xml.contains("<IBAN>DE00123456789012345678</IBAN>"));
        assertTrue(xml.contains("<IBAN>DE00987654321098765432</IBAN>"));
        assertTrue(xml.contains("<Ccy>EUR</Ccy>"));
        assertTrue(xml.contains("<Ccy>USD</Ccy>"));
    }
}
