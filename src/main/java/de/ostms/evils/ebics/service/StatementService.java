package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.Account;
import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class StatementService {

    private final TransactionService transactionService;
    private final BankService bankService;

    public StatementService(TransactionService transactionService,
                            BankService bankService) {
        this.transactionService = transactionService;
        this.bankService = bankService;
    }

    public Statement createStatement(String iban, LocalDate from, LocalDate to) {
        Account account = bankService.findAccount(iban)
                .orElseThrow(() -> new IllegalArgumentException("Unknown IBAN: " + iban));

        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        List<Transaction> txs = transactionService.find(iban, from, to);

        BigDecimal movement = txs.stream()
                .map(t -> t.credit() ? t.amount() : t.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal priorMovement = transactionService.before(iban, from).stream()
                .map(t -> t.credit() ? t.amount() : t.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal opening = account.getOpeningBalance().add(priorMovement);
        BigDecimal closing = opening.add(movement);

        return new Statement(
                "STMT-" + iban + "-" + from + "-" + to,
                iban,
                account.getCurrency(),
                from,
                to,
                opening,
                closing,
                txs
        );
    }
}
