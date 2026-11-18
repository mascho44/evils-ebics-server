package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.persistence.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Laesst den Monatsabschluss (Entgelte, Zinsen, AZV-Abrechnungen) monatlich automatisch fuer alle
 * Konten laufen - wie eine echte Bank. Standardmaessig AUS, weil der Job von selbst Ledger-Buchungen
 * anlegt: aktivieren mit evils.fees.monthly.enabled=true. Idempotent (siehe FeeTestDataService),
 * ein verpasster oder doppelter Lauf ist daher harmlos.
 */
@Component
public class FeeClosingScheduler {
    private static final Logger log = LoggerFactory.getLogger(FeeClosingScheduler.class);

    private final FeeTestDataService fees;
    private final AccountRepository accounts;
    private final boolean enabled;
    private final int azvProMonat;

    public FeeClosingScheduler(FeeTestDataService fees, AccountRepository accounts,
                               @Value("${evils.fees.monthly.enabled:false}") boolean enabled,
                               @Value("${evils.fees.monthly.azv-per-month:2}") int azvProMonat) {
        this.fees = fees;
        this.accounts = accounts;
        this.enabled = enabled;
        this.azvProMonat = azvProMonat;
    }

    @Scheduled(cron = "${evils.fees.monthly.cron:0 30 3 1 * *}", zone = "${evils.fees.monthly.zone:Europe/Berlin}")
    public void run() {
        if (!enabled) {
            return;
        }
        accounts.findAll().forEach(account -> {
            try {
                var ergebnis = fees.generate(account.getIban(), 1, azvProMonat);
                log.info("Monatsabschluss {}: {}", account.getIban(), ergebnis);
            } catch (RuntimeException e) {
                log.warn("Monatsabschluss fuer {} fehlgeschlagen: {}", account.getIban(), e.getMessage());
            }
        });
    }
}
