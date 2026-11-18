package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.camt.Camt053Generator;
import de.ostms.evils.ebics.camt.Camt052Generator;
import de.ostms.evils.ebics.camt.Camt054Generator;
import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;
import de.ostms.evils.ebics.service.FeeTestDataService;
import de.ostms.evils.ebics.service.StatementService;
import de.ostms.evils.ebics.service.TransactionService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin REST interface for the ledger and for generated reports: list, create, generate and delete
 * bookings, create bank-fee test data, and render camt.052, camt.053 and camt.054 for an IBAN and a
 * period ({@code iban}, {@code from}, {@code to} as {@code YYYY-MM-DD}).
 */
@RestController
@RequestMapping("/admin/api")
public class StatementController {

    @org.springframework.beans.factory.annotation.Autowired private FeeTestDataService feeTestDataService;
    private final TransactionService transactionService;
    private final StatementService statementService;
    private final Camt053Generator camt053Generator;
    private final Camt052Generator camt052Generator;
    private final Camt054Generator camt054Generator;

    public StatementController(TransactionService transactionService,
                               StatementService statementService,
                               Camt053Generator camt053Generator, Camt052Generator camt052Generator,
                               Camt054Generator camt054Generator) {
        this.transactionService = transactionService;
        this.statementService = statementService;
        this.camt053Generator = camt053Generator;
        this.camt052Generator = camt052Generator;
        this.camt054Generator = camt054Generator;
    }

    @GetMapping("/transactions")
    public List<Transaction> transactions() {
        return transactionService.all();
    }

    @PostMapping(value = "/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction createTransaction(@RequestBody CreateTransactionRequest request) {
        if (request.iban() == null || request.bookingDate() == null || request.credit() == null) {
            throw new IllegalArgumentException("iban, bookingDate and credit are required");
        }
        return transactionService.add(request.transactionId(), request.iban(), request.bookingDate(),
                request.valueDate(), request.amount(), request.credit(), request.counterpartyName(),
                request.counterpartyIban(), request.counterpartyBic(), request.purpose(), request.endToEndId());
    }

    @PostMapping("/transactions/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Transaction> generateTransactions(@RequestParam String iban, @RequestParam(defaultValue = "100") int count) {
        return transactionService.generateDemoTransactions(iban, count);
    }

    /** Bankgebuehren-Testdaten (siehe FeeTestDataService): Monatsabschluss mit Entgelten/Zinsen und
     * AZV-Zahlungen mit Entgeltabrechnung je Zahlung - idempotent, nichts wird ueberschrieben. */
    @PostMapping("/transactions/gebuehren-testdaten")
    @ResponseStatus(HttpStatus.CREATED)
    public FeeTestDataService.Ergebnis generateFeeTestData(@RequestParam String iban,
                                                           @RequestParam(defaultValue = "6") int months,
                                                           @RequestParam(defaultValue = "2") int azvProMonat) {
        return feeTestDataService.generate(iban, months, azvProMonat);
    }

    @DeleteMapping("/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable String transactionId) { transactionService.delete(transactionId); }

    @DeleteMapping("/transactions")
    public Map<String, Long> deleteTransactionsForAccount(@RequestParam String iban) {
        return Map.of("deleted", transactionService.deleteForAccount(iban));
    }

    @GetMapping(value = "/statements/camt053",
            produces = MediaType.APPLICATION_XML_VALUE)
    public String camt053(
            @RequestParam String iban,
            @RequestParam String from,
            @RequestParam String to) {

        Statement statement = statementService.createStatement(
                iban, LocalDate.parse(from), LocalDate.parse(to));

        return camt053Generator.generate(statement);
    }

    @GetMapping(value = "/statements/camt052", produces = MediaType.APPLICATION_XML_VALUE)
    public String camt052(@RequestParam String iban, @RequestParam String from, @RequestParam String to) {
        Statement statement = statementService.createStatement(iban, LocalDate.parse(from), LocalDate.parse(to));
        return camt052Generator.generate(statement);
    }

    @GetMapping(value = "/statements/camt054",
            produces = MediaType.APPLICATION_XML_VALUE)
    public String camt054(
            @RequestParam String iban,
            @RequestParam String from,
            @RequestParam String to) {

        Statement statement = statementService.createStatement(
                iban, LocalDate.parse(from), LocalDate.parse(to));

        return camt054Generator.generate(statement);
    }
}
