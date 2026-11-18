package de.ostms.evils.ebics.admin;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateAccountRequest(String iban, String bic, String partnerId, String accountHolder,
                                    BigDecimal openingBalance, LocalDate openingBalanceDate, String currency) { }
