package de.ostms.evils.ebics.admin;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateTransactionRequest(
        String transactionId, String iban, LocalDate bookingDate, LocalDate valueDate,
        BigDecimal amount, Boolean credit, String counterpartyName, String counterpartyIban,
        String counterpartyBic, String purpose, String endToEndId) { }
