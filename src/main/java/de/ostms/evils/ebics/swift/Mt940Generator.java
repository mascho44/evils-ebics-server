package de.ostms.evils.ebics.swift;

import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders account statements as MT940 customer statement messages - the payload of the classic
 * EBICS 2.5 (H004) order type STA. Same slim dialect German banks deliver:
 * {@code :20:}/{@code :25:}/{@code :28C:} header, {@code :60F:} opening balance, one
 * {@code :61:}/{@code :86:} pair per booking (the {@code :86:} in the structured "GVC?dd" form,
 * purpose in ?20-?29, counterparty BIC/IBAN/name in ?30/?31/?32), {@code :62F:} closing balance,
 * lines wrapped at 65 characters. Written so a standard MT940 parser reads it back, which is the
 * point of this mock: connection tests of EBICS 2.5 clients against realistic statement files.
 */
@org.springframework.stereotype.Component
public class Mt940Generator {

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMdd");
    private static final int LINE = 65;
    private static final int PURPOSE_CHUNK = 27;

    public String generate(Statement statement) {
        var out = new StringBuilder();
        out.append(":20:EVILS").append(statement.to().format(SHORT)).append("\r\n");
        out.append(":25:").append(statement.iban()).append("\r\n");
        out.append(":28C:00000/001\r\n");
        out.append(":60F:").append(balance(statement.openingBalance(), statement.from(), statement.currency())).append("\r\n");
        for (Transaction t : statement.transactions()) {
            out.append(":61:")
                    .append(t.valueDate().format(SHORT))
                    .append(t.bookingDate().format(MONTH_DAY))
                    .append(t.credit() ? "C" : "D")
                    .append(amount(t.amount()))
                    .append("NTRF")
                    .append(t.endToEndId() == null || t.endToEndId().isBlank() ? "NONREF" : clip(swift(t.endToEndId()), 16))
                    .append("//").append(clip(swift(t.transactionId()), 16))
                    .append("\r\n");
            wrap(out, ":86:" + structuredPurpose(t));
        }
        out.append(":62F:").append(balance(statement.closingBalance(), statement.to(), statement.currency())).append("\r\n");
        out.append("-\r\n");
        return out.toString();
    }

    public String generateAll(List<Statement> statements) {
        var out = new StringBuilder();
        for (Statement statement : statements) out.append(generate(statement));
        return out.toString();
    }

    /** GVC 166 (SEPA transfer) with the purpose split into the 27-character ?20..?29 subfields. */
    static String structuredPurpose(Transaction t) {
        var field = new StringBuilder("166?00").append(t.credit() ? "Gutschrift" : "Ueberweisung");
        String purpose = swift(t.purpose());
        int sub = 20;
        for (int i = 0; i < purpose.length() && sub <= 29; i += PURPOSE_CHUNK, sub++) {
            field.append('?').append(sub).append(purpose, i, Math.min(purpose.length(), i + PURPOSE_CHUNK));
        }
        if (t.counterpartyBic() != null && !t.counterpartyBic().isBlank()) field.append("?30").append(swift(t.counterpartyBic()));
        if (t.counterpartyIban() != null && !t.counterpartyIban().isBlank()) field.append("?31").append(swift(t.counterpartyIban()));
        if (t.counterpartyName() != null && !t.counterpartyName().isBlank()) field.append("?32").append(clip(swift(t.counterpartyName()), 27));
        return field.toString();
    }

    private static String balance(BigDecimal value, java.time.LocalDate date, String currency) {
        return (value.signum() < 0 ? "D" : "C") + date.format(SHORT) + currency + amount(value.abs());
    }

    static String amount(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** The first line carries the tag, continuation lines are plain text - all at most 65 chars. */
    static void wrap(StringBuilder out, String text) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < text.length(); i += LINE) lines.add(text.substring(i, Math.min(text.length(), i + LINE)));
        for (String line : lines) out.append(line).append("\r\n");
    }

    /** MT940 allows only a restricted character set - transliterate umlauts, drop the rest, and
     * keep '?' and ':' out of free text (they delimit the structured :86: subfields). */
    static String swift(String value) {
        if (value == null) return "";
        String mapped = value.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae")
                .replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss").replace("€", "EUR");
        var clean = new StringBuilder();
        for (char c : mapped.toCharArray()) {
            if (c == '?' || c == ':') clean.append(' ');
            else if (c >= 0x20 && c < 0x7F) clean.append(c);
        }
        return clean.toString().trim().replaceAll(" +", " ");
    }

    static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
