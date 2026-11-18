package de.ostms.evils.ebics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record Statement(
        String statementId,
        String iban,
        String currency,
        LocalDate from,
        LocalDate to,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        List<Transaction> transactions
) {}
