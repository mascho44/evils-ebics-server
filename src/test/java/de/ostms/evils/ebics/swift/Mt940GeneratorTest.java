package de.ostms.evils.ebics.swift;

import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** MT940 for the EBICS 2.5 STA order type The two patterns below are the ones
 * a typical MT940 parser applies to :61: and :60F:, so a file passing them imports there. */
class Mt940GeneratorTest {

    private static final Pattern ZEILE_61 = Pattern.compile("^(\\d{6})(\\d{4})?(RC|RD|C|D)(\\d+,\\d{0,2})(.*)$");
    private static final Pattern ZEILE_60F = Pattern.compile("^(C|D)(\\d{6})([A-Z]{3})(\\d+,\\d{0,2})$");

    private Transaction tx(String id, boolean credit, String amount, String purpose) {
        return new Transaction(id, "DE00123456789012345678", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3),
                new BigDecimal(amount), "EUR", credit, "Müller & Söhne GmbH", "DE12500105170648489890", "INGDDEFFXXX",
                purpose, "E2E-" + id, java.time.LocalDateTime.of(2026, 1, 2, 10, 0));
    }

    private Statement statement(String opening, String closing, Transaction... txs) {
        return new Statement("S1", "DE00123456789012345678", "EUR", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                new BigDecimal(opening), new BigDecimal(closing), List.of(txs));
    }

    @Test
    void rendersTheStandardTagsAndBalances() {
        String mt = new Mt940Generator().generate(statement("1000.00", "1087.66", tx("T1", true, "100.00", "Rechnung 42"), tx("T2", false, "12.34", "Gebuehr")));

        assertThat(mt).startsWith(":20:EVILS260131\r\n:25:DE00123456789012345678\r\n:28C:00000/001\r\n");
        assertThat(mt).contains(":60F:C260101EUR1000,00\r\n", ":62F:C260131EUR1087,66\r\n").endsWith("-\r\n");
    }

    @Test
    void everyBookingLineMatchesWhatTheOsTmsParserExpects() {
        String mt = new Mt940Generator().generate(statement("1000.00", "1087.66", tx("T1", true, "100.00", "Rechnung 42"), tx("T2", false, "12.34", "Gebuehr")));

        var lines = mt.split("\r\n");
        long bookings = 0;
        for (String line : lines) {
            if (line.startsWith(":61:")) {
                var m = ZEILE_61.matcher(line.substring(4));
                assertThat(m.matches()).as(line).isTrue();
                bookings++;
            }
            if (line.startsWith(":60F:")) assertThat(ZEILE_60F.matcher(line.substring(5)).matches()).as(line).isTrue();
            assertThat(line.length()).isLessThanOrEqualTo(65);
        }
        assertThat(bookings).isEqualTo(2);
        assertThat(mt).contains(":61:260103" + "0102C100,00NTRFE2E-T1//T1", ":61:2601030102D12,34NTRFE2E-T2//T2");
    }

    @Test
    void structuresTheNarrativeAndTransliteratesUmlauts() {
        String mt = new Mt940Generator().generate(statement("0.00", "100.00", tx("T1", true, "100.00", "Rechnung 42")));

        var narrative = new StringBuilder();
        boolean in86 = false;
        for (String line : mt.split("\r\n")) {
            if (line.startsWith(":86:")) { in86 = true; narrative.append(line.substring(4)); }
            else if (in86 && !line.startsWith(":")) narrative.append(line);
            else in86 = false;
        }
        assertThat(narrative.toString()).startsWith("166?00Gutschrift?20Rechnung 42")
                .contains("?30INGDDEFFXXX", "?31DE12500105170648489890", "?32Mueller & Soehne GmbH");
    }

    @Test
    void aNegativeBalanceIsMarkedAsDebit() {
        String mt = new Mt940Generator().generate(statement("-50.00", "-25.50"));

        assertThat(mt).contains(":60F:D260101EUR50,00\r\n", ":62F:D260131EUR25,50\r\n");
    }

    @Test
    void longPurposesAreWrappedAt65CharactersAndNeverContainSubfieldDelimiters() {
        String mt = new Mt940Generator().generate(statement("0.00", "1.00", tx("T1", true, "1.00", "Sehr langer Verwendungszweck: mit Doppelpunkt? und Fragezeichen fuer den Test der Zeilenlaenge")));

        for (String line : mt.split("\r\n")) assertThat(line.length()).isLessThanOrEqualTo(65);
        assertThat(mt).doesNotContain("Doppelpunkt?");
    }
}
