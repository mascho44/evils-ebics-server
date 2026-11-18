package de.ostms.evils.ebics.swift;

import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the intraday bookings of accounts as MT942 interim transaction reports - the payload of
 * the classic EBICS 2.5 (H004) order type VMK. Same slim dialect as {@link Mt940Generator}
 * for the {@code :20:}/{@code :25:}/{@code :28C:} header and the {@code :61:}/{@code :86:} booking
 * pairs; instead of opening/closing balances an MT942 carries the floor limit ({@code :34F:}), the
 * creation time ({@code :13D:}) and the number and sum of debit and credit entries ({@code :90D:}/{@code :90C:}).
 */
@org.springframework.stereotype.Component
public class Mt942Generator {

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMdd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMddHHmm");

    public String generate(Statement statement) {
        return generate(statement, LocalDateTime.now());
    }

    String generate(Statement statement, LocalDateTime created) {
        var out = new StringBuilder();
        out.append(":20:EVILS").append(statement.to().format(SHORT)).append("\r\n");
        out.append(":25:").append(statement.iban()).append("\r\n");
        out.append(":28C:00000/001\r\n");
        out.append(":34F:").append(statement.currency()).append("0,\r\n");
        out.append(":13D:").append(created.format(STAMP)).append("+0000\r\n");
        int debits = 0, credits = 0;
        BigDecimal debitSum = BigDecimal.ZERO, creditSum = BigDecimal.ZERO;
        for (Transaction t : statement.transactions()) {
            out.append(":61:")
                    .append(t.valueDate().format(SHORT))
                    .append(t.bookingDate().format(MONTH_DAY))
                    .append(t.credit() ? "C" : "D")
                    .append(Mt940Generator.amount(t.amount()))
                    .append("NTRF")
                    .append(t.endToEndId() == null || t.endToEndId().isBlank() ? "NONREF" : Mt940Generator.clip(Mt940Generator.swift(t.endToEndId()), 16))
                    .append("//").append(Mt940Generator.clip(Mt940Generator.swift(t.transactionId()), 16))
                    .append("\r\n");
            Mt940Generator.wrap(out, ":86:" + Mt940Generator.structuredPurpose(t));
            if (t.credit()) { credits++; creditSum = creditSum.add(t.amount()); }
            else { debits++; debitSum = debitSum.add(t.amount()); }
        }
        out.append(":90D:").append(debits).append(statement.currency()).append(Mt940Generator.amount(debitSum)).append("\r\n");
        out.append(":90C:").append(credits).append(statement.currency()).append(Mt940Generator.amount(creditSum)).append("\r\n");
        out.append("-\r\n");
        return out.toString();
    }

    public String generateAll(List<Statement> statements) {
        var out = new StringBuilder();
        for (Statement statement : statements) out.append(generate(statement));
        return out.toString();
    }
}
