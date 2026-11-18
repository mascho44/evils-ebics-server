package de.ostms.evils.ebics.swift;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Gemeinsame Bausteine fuer die MT7xx-Demo-Generatoren (Mt700Generator/Mt707Generator/
 * Mt760Generator) - Datumsformat, Betragsformatierung und Referenznummern folgen demselben
 * schlanken Feld-Dialekt in allen dreien, orientiert an genau der Teilmenge, die corporate-lc-
 * manager's eigene MT700-/MT707-Parser erwarten (siehe dortige Mt700Parser/Mt707Parser -
 * :20:/:31C:/:31D:/:32B:/:44C:/:50:/:59:/:46A: bzw. :20:/:26E:/:30:/:31E:/:32B:/:33B:/:46B:), damit
 * eine hier generierte Nachricht dort ohne Anpassung eingelesen werden kann. */
final class SwiftFormat {

    /** SWIFT-Feld-Datumsformat JJMMTT (zweistelliges Jahr) - z.B. 2026-09-18 -> "260918". */
    static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("yyMMdd");

    private static final SecureRandom RANDOM = new SecureRandom();

    private SwiftFormat() {
    }

    static BigDecimal zufaelligerBetrag() {
        double wert = 10_000 + RANDOM.nextDouble() * 490_000;
        return BigDecimal.valueOf(wert).setScale(2, RoundingMode.HALF_UP);
    }

    /** SWIFT-Betragsfelder (z.B. 32B/33B) nutzen Komma statt Punkt als Dezimaltrennzeichen. */
    static String betrag(BigDecimal betrag) {
        return betrag.toPlainString().replace('.', ',');
    }

    static String referenz(String praefix, int ziffern) {
        int obergrenze = (int) Math.pow(10, ziffern);
        return praefix + LocalDate.now().getYear() + String.format("%0" + ziffern + "d", RANDOM.nextInt(obergrenze));
    }
}
