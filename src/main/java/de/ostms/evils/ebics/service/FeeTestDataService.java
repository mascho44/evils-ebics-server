package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.Account;
import de.ostms.evils.ebics.domain.Bank;
import de.ostms.evils.ebics.domain.Transaction;
import de.ostms.evils.ebics.persistence.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bankgebuehren-Testdaten fuer Gebuehrenanalysen in Clients:
 * pro abgeschlossenem Monat ein realistischer MONATSABSCHLUSS (Kontofuehrungsentgelt,
 * Buchungspostenentgelt, EBICS-Entgelt, Soll-/Habenzinsen) und AZV-Zahlungen (Auslandszahlungsverkehr)
 * mit je einer eigenen ENTGELTABRECHNUNG pro Zahlung (Provision, Auslandsentgelt, bei Kostenteilung
 * OUR zusaetzlich Fremdspesen der Korrespondenzbank). Alles deterministisch (gleiche Eingabe = gleiche
 * Buchungen) und idempotent: bereits vorhandene Buchungen werden uebersprungen, nichts ueberschrieben.
 * Die Entgelte stehen wie auf echten Kontoauszuegen nur im Verwendungszweck - eine Auswertung
 * erkennt sie an Schluesselwoertern (Entgelt, Provision, Fremdspesen, Zinsen, AZV).
 */
@Service
public class FeeTestDataService {

    private static final BigDecimal KONTOFUEHRUNG = new BigDecimal("12.50");
    private static final BigDecimal EBICS_ENTGELT = new BigDecimal("4.90");
    private static final BigDecimal POSTENPREIS = new BigDecimal("0.30");
    private static final int FREIPOSTEN = 10;
    private static final BigDecimal SOLLZINS = new BigDecimal("0.0825");
    private static final BigDecimal HABENZINS = new BigDecimal("0.0010");
    private static final BigDecimal AZV_PROVISION_SATZ = new BigDecimal("0.0015");
    private static final BigDecimal AZV_PROVISION_MIN = new BigDecimal("7.50");
    private static final BigDecimal AZV_PROVISION_MAX = new BigDecimal("150.00");
    private static final BigDecimal AZV_AUSLANDSENTGELT = new BigDecimal("5.00");

    /** Fremdwaehrung, Kurs (Einheiten je 1 EUR), Basisbetrag in Fremdwaehrung, Empfaenger, BIC. */
    private record Auslandspartner(String waehrung, String kurs, String betrag, String name, String bic) {
    }

    private static final List<Auslandspartner> PARTNER = List.of(
            new Auslandspartner("USD", "1.0832", "12345.00", "ACME Corp., New York, US", "CHASUS33XXX"),
            new Auslandspartner("GBP", "0.8590", "8900.50", "Northwind Ltd., London, GB", "NWBKGB2LXXX"),
            new Auslandspartner("CHF", "0.9420", "21000.00", "Alpenhandel AG, Zuerich, CH", "UBSWCHZH80A"),
            new Auslandspartner("JPY", "162.40", "1850000", "Tokyo Parts KK, Tokyo, JP", "BOTKJPJTXXX"));

    public record Ergebnis(int monatsabschluss, int azvZahlungen, int azvEntgelte, int uebersprungen) {
    }

    private final TransactionService transactions;
    private final AccountRepository accounts;
    private final BankService bankService;

    public FeeTestDataService(TransactionService transactions, AccountRepository accounts, BankService bankService) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.bankService = bankService;
    }

    @Transactional
    public Ergebnis generate(String iban, int months, int azvProMonat) {
        Account account = accounts.findById(iban).orElseThrow(() -> new IllegalArgumentException("Unknown IBAN: " + iban));
        if (months < 1 || months > 24) throw new IllegalArgumentException("months must be between 1 and 24");
        if (azvProMonat < 0 || azvProMonat > 10) throw new IllegalArgumentException("azvProMonat must be between 0 and 10");
        Bank bank = bankService.bank(account.getHostId());

        int abschluss = 0, azv = 0, entgelte = 0, uebersprungen = 0;
        // aelteste zuerst: die Salden spaeterer Monate bauen auf den frueheren Buchungen auf
        for (int m = months; m >= 1; m--) {
            YearMonth monat = YearMonth.now().minusMonths(m);
            String kennung = monat.format(DateTimeFormatter.ofPattern("yyyyMM"));
            String anzeige = monat.format(DateTimeFormatter.ofPattern("MM/yyyy"));

            for (int k = 0; k < azvProMonat; k++) {
                Auslandspartner partner = PARTNER.get((m + k) % PARTNER.size());
                LocalDate tag = werktag(monat.atDay(Math.min(3 + k * 8, 26)));
                BigDecimal faktor = BigDecimal.ONE.add(new BigDecimal((m * 7 + k * 13) % 40).movePointLeft(2));
                BigDecimal fremd = new BigDecimal(partner.betrag()).multiply(faktor).setScale(partner.waehrung().equals("JPY") ? 0 : 2, RoundingMode.HALF_UP);
                BigDecimal eur = fremd.divide(new BigDecimal(partner.kurs()), 2, RoundingMode.HALF_UP);
                boolean kostenteilungOur = k % 3 == 2;
                String referenz = "AZV-" + kennung + "-" + (k + 1);
                String id = "TX-FEE-" + iban + "-" + kennung + "-";

                if (buche(id + "AZV" + k, iban, tag, eur, false, partner.name(), partner.bic(),
                        "AZV Zahlung " + referenz + " " + partner.waehrung() + " " + zahl(fremd) + " Kurs " + partner.kurs()
                                + (kostenteilungOur ? " Kosten OUR" : " Kosten SHA"), referenz)) azv++; else uebersprungen++;

                BigDecimal provision = eur.multiply(AZV_PROVISION_SATZ).max(AZV_PROVISION_MIN).min(AZV_PROVISION_MAX).setScale(2, RoundingMode.HALF_UP);
                if (buche(id + "AZVENT" + k, iban, tag, provision.add(AZV_AUSLANDSENTGELT), false, bank.hostName(), bank.bic(),
                        "AZV-Entgelt Zahlung " + referenz + ": Provision 0,15 % (min 7,50 / max 150,00) " + zahl(provision)
                                + " EUR, Auslandsentgelt " + zahl(AZV_AUSLANDSENTGELT) + " EUR", referenz + "-ENTGELT")) entgelte++; else uebersprungen++;
                if (kostenteilungOur) {
                    BigDecimal fremdspesen = new BigDecimal("15.00").add(new BigDecimal(k * 5));
                    if (buche(id + "AZVFREMD" + k, iban, tag, fremdspesen, false, bank.hostName(), bank.bic(),
                            "Fremdspesen Korrespondenzbank Zahlung " + referenz + " (Kosten OUR) " + zahl(fremdspesen) + " EUR",
                            referenz + "-FREMDSPESEN")) entgelte++; else uebersprungen++;
                }
            }

            // Monatsabschluss am letzten Werktag - nach den AZV-Buchungen, damit Posten und Saldo stimmen
            LocalDate abschlussTag = werktagRueckwaerts(monat.atEndOfMonth());
            String id = "TX-FEE-" + iban + "-" + kennung + "-";
            List<Transaction> imMonat = transactions.find(iban, monat.atDay(1), monat.atEndOfMonth());
            int posten = (int) imMonat.stream().filter(t -> !t.transactionId().startsWith(id + "KTO")
                    && !t.transactionId().startsWith(id + "POSTEN") && !t.transactionId().startsWith(id + "EBICS")
                    && !t.transactionId().startsWith(id + "ZINS")).count();
            BigDecimal saldo = account.getOpeningBalance();
            for (Transaction t : transactions.before(iban, abschlussTag.plusDays(1))) {
                saldo = saldo.add(t.credit() ? t.amount() : t.amount().negate());
            }

            if (buche(id + "KTO", iban, abschlussTag, KONTOFUEHRUNG, false, bank.hostName(), bank.bic(),
                    "Entgelt Kontoführung " + anzeige + " Kontomodell Business", "ABSCHLUSS-" + kennung + "-KONTO")) abschluss++; else uebersprungen++;
            int abrechenbar = Math.max(0, posten - FREIPOSTEN);
            if (abrechenbar > 0 && buche(id + "POSTEN", iban, abschlussTag, POSTENPREIS.multiply(new BigDecimal(abrechenbar)), false,
                    bank.hostName(), bank.bic(), "Entgelt Buchungsposten " + anzeige + ": " + posten + " Posten, davon " + FREIPOSTEN
                            + " frei, " + abrechenbar + " x " + zahl(POSTENPREIS) + " EUR", "ABSCHLUSS-" + kennung + "-POSTEN")) abschluss++;
            if (buche(id + "EBICS", iban, abschlussTag, EBICS_ENTGELT, false, bank.hostName(), bank.bic(),
                    "Entgelt elektronischer Zahlungsverkehr EBICS " + anzeige, "ABSCHLUSS-" + kennung + "-EBICS")) abschluss++;
            if (saldo.signum() < 0) {
                BigDecimal zins = saldo.abs().multiply(SOLLZINS).divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
                if (zins.signum() > 0 && buche(id + "ZINS", iban, abschlussTag, zins, false, bank.hostName(), bank.bic(),
                        "Sollzinsen " + anzeige + " auf " + zahl(saldo.abs()) + " EUR zu 8,25 % p.a.", "ABSCHLUSS-" + kennung + "-ZINS")) abschluss++;
            } else {
                BigDecimal zins = saldo.multiply(HABENZINS).divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
                if (zins.signum() > 0 && buche(id + "ZINS", iban, abschlussTag, zins, true, bank.hostName(), bank.bic(),
                        "Habenzinsen " + anzeige + " auf " + zahl(saldo) + " EUR zu 0,10 % p.a.", "ABSCHLUSS-" + kennung + "-ZINS")) abschluss++;
            }
        }
        return new Ergebnis(abschluss, azv, entgelte, uebersprungen);
    }

    /** @return true, wenn neu angelegt; false, wenn es diese Buchung schon gab (dann unveraendert). */
    private boolean buche(String id, String iban, LocalDate tag, BigDecimal betrag, boolean gutschrift,
                          String gegenpartei, String bic, String zweck, String endToEnd) {
        if (transactions.exists(id)) {
            return false;
        }
        transactions.add(id, iban, tag, tag, betrag, gutschrift, gegenpartei, null, bic, zweck, endToEnd);
        return true;
    }

    private static LocalDate werktag(LocalDate tag) {
        while (tag.getDayOfWeek() == DayOfWeek.SATURDAY || tag.getDayOfWeek() == DayOfWeek.SUNDAY) tag = tag.plusDays(1);
        return tag;
    }

    private static LocalDate werktagRueckwaerts(LocalDate tag) {
        while (tag.getDayOfWeek() == DayOfWeek.SATURDAY || tag.getDayOfWeek() == DayOfWeek.SUNDAY) tag = tag.minusDays(1);
        return tag;
    }

    private static String zahl(BigDecimal wert) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
        format.setMinimumFractionDigits(wert.scale() > 0 ? 2 : 0);
        format.setMaximumFractionDigits(wert.scale() > 0 ? 2 : 0);
        return format.format(wert);
    }
}
