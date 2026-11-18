package de.ostms.evils.ebics.util;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.function.Predicate;

/** Erzeugt gueltige (Mod-97/ISO 7064 mod 97-10) deutsche Test-IBANs - fuer evils selbst (die
 * Ordnungsnummer der Bank ist der Alle-Neunen-Block "99999999", ein ueblicher, offensichtlich
 * fiktiver Bankleitzahl-Platzhalter, da evils keine echte numerische BLZ fuehrt, siehe Bank.java)
 * UND fuer Nutzer, die ein Testkonto in der Admin-Oberflaeche anlegen und dabei eine schnell
 * getippte, aber ungueltige IBAN vermeiden wollen (siehe AdminController.generateIban). Bisher
 * lebte dieselbe Pruefziffern-Formel nur privat in TestScenarioService - hierher gezogen, damit
 * beide Verwender dieselbe, einmal geprüfte Implementierung teilen statt sie zu duplizieren. */
public final class IbanGenerator {

    private static final String BANKLEITZAHL_PLATZHALTER = "99999999";
    private static final SecureRandom RANDOM = new SecureRandom();

    private IbanGenerator() {
    }

    /** Deterministisch aus einem beliebigen Seed-Text - gleicher Seed liefert immer dieselbe IBAN
     * (siehe TestScenarioService: ein Szenario darf beim erneuten Anlegen nicht plötzlich andere
     * Konten erzeugen). */
    public static String vonSeed(String seed) {
        BigInteger hash = new BigInteger(1, seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String kontonummer = String.format("%010d", hash.mod(BigInteger.valueOf(10_000_000_000L)));
        return mitPruefziffer(kontonummer);
    }

    /** Zufaellig, fuer die "IBAN generieren"-Schaltflaeche im Testkonto-Anlegen-Dialog - der
     * Aufrufer prueft selbst auf Kollision mit einer bereits vergebenen IBAN (siehe
     * AdminController.generateIban) und ruft im (praktisch nie eintretenden) Kollisionsfall
     * erneut auf. */
    public static String zufaellig() {
        String kontonummer = String.format("%010d", (long) (RANDOM.nextDouble() * 10_000_000_000L));
        return mitPruefziffer(kontonummer);
    }

    /** Wie zufaellig(), wiederholt aber bis nichtVergeben.test(iban) zutrifft - Kollisionen sind
     * bei 10 Milliarden moeglichen Kontonummern praktisch ausgeschlossen, ein harter Anschlag
     * verhindert trotzdem eine Endlosschleife bei einem Aufrufer-Fehler. */
    public static String zufaelligUndUnbenutzt(Predicate<String> nichtVergeben) {
        for (int versuch = 0; versuch < 100; versuch++) {
            String kandidat = zufaellig();
            if (nichtVergeben.test(kandidat)) {
                return kandidat;
            }
        }
        throw new IllegalStateException("Keine freie Test-IBAN gefunden");
    }

    private static String mitPruefziffer(String kontonummer) {
        String bban = BANKLEITZAHL_PLATZHALTER + kontonummer;
        // DE -> 1314, Laenderkennung ans Ende, "00" als Platzhalter-Pruefziffer davor - klassische
        // ISO-7064-Mod-97-10-Berechnung fuer den IBAN-Pruefziffern-Algorithmus.
        int pruefziffer = 98 - new BigInteger(bban + "131400").mod(BigInteger.valueOf(97)).intValue();
        return "DE" + String.format("%02d", pruefziffer) + bban;
    }
}
