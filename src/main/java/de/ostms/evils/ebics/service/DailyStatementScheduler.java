package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.DailyStatementRun;
import de.ostms.evils.ebics.persistence.AccountRepository;
import de.ostms.evils.ebics.persistence.DailyStatementRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates one deterministic, currency-correct test booking per active account and day. */
@Service
public class DailyStatementScheduler {
    private static final Logger log = LoggerFactory.getLogger(DailyStatementScheduler.class);
    private final AccountRepository accounts;
    private final DailyStatementRunRepository runs;
    private final TransactionService transactions;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Value("${evils.statements.daily.enabled:true}") private boolean enabled;
    @org.springframework.beans.factory.annotation.Value("${evils.statements.daily.accounts:}") private String selectedAccounts;
    @org.springframework.beans.factory.annotation.Value("${evils.statements.daily.zone:Europe/Berlin}") private String zone;

    public DailyStatementScheduler(AccountRepository accounts, DailyStatementRunRepository runs, TransactionService transactions) {
        this.accounts = accounts; this.runs = runs; this.transactions = transactions;
    }

    @Scheduled(cron = "${evils.statements.daily.cron:0 0 2 * * *}", zone = "${evils.statements.daily.zone:Europe/Berlin}")
    public void scheduledRun() { if(enabled) generateFor(LocalDate.now(java.time.ZoneId.of(zone))); }

    public int generateFor(LocalDate date) {
        if(!enabled) return 0;
        java.util.Set<String> selected = new java.util.HashSet<>(java.util.Arrays.asList(selectedAccounts.split(",")));
        selected = selected.stream().map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        final var selection=selected;
        AtomicInteger created = new AtomicInteger();
        accounts.findAll().stream().filter(a -> a.isActive() && (selection.isEmpty()||selection.contains(a.getIban()))).forEach(candidate -> {
          try {
           Boolean added=transactionTemplate.execute(status -> {
            var account=entityManager.find(de.ostms.evils.ebics.domain.Account.class,candidate.getIban(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if(account==null||!account.isActive()||(account.getOpeningBalanceDate()!=null&&date.isBefore(account.getOpeningBalanceDate())))return false;
            String runId = account.getIban() + "|" + date;
            if (runs.existsById(runId)) return false;
            String txId = "TX-DAILY-" + date + "-" + account.getIban();
            transactions.add(txId, account.getIban(), date, date, new BigDecimal("125.00"), true,
                    "EVILS Testgenerator", null, account.getBic(), "Täglicher Testauszug " + date, "E2E-DAILY-" + date);
            runs.save(new DailyStatementRun(runId, account.getIban(), date));
            return true;
           });
           if(Boolean.TRUE.equals(added)) created.incrementAndGet();
          } catch(RuntimeException e) { log.warn("Daily statement generation failed: account={}, date={}, error={}",candidate.getIban(),date,e.getClass().getSimpleName()); }
        });
        if (created.get() > 0) log.info("Daily test statements generated: date={}, accounts={}", date, created.get());
        return created.get();
    }
}
