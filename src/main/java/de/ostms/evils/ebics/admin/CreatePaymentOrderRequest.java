package de.ostms.evils.ebics.admin;
import java.math.BigDecimal;
import java.time.LocalDate;
public record CreatePaymentOrderRequest(String iban, BigDecimal amount, String beneficiaryName, String beneficiaryIban, String beneficiaryBic, String purpose, String endToEndId, LocalDate executionDate) { }
