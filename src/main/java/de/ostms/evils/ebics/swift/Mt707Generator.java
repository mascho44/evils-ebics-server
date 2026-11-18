package de.ostms.evils.ebics.swift;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Erzeugt eine realistische MT707-Demo-Nachricht (Aenderung eines Dokumentenakkreditivs) - Feld
 * :20: ist hier bewusst die Referenz des zu aendernden Akkreditivs, kein neues Sender-Reference
 * (so liest es auch corporate-lc-manager's Mt707Parser: dessen Rueckgabe-Record nennt es
 * lcReference). Gleicher schlanker Feld-Dialekt wie Mt700Generator, siehe SwiftFormat. */
@Component
public class Mt707Generator {

    /** lcReferenz null/leer -> frische, plausibel aussehende Referenz (z.B. wenn der Nutzer vorher
     * kein MT700 generiert hat, siehe SwiftDemoController.mt707) - sonst genau diese Referenz, damit
     * die Aenderung sichtbar zu einem zuvor generierten MT700 gehoert. */
    public SwiftNachricht generieren(String lcReferenz) {
        String referenz = (lcReferenz == null || lcReferenz.isBlank()) ? SwiftFormat.referenz("LC", 6) : lcReferenz;
        LocalDate heute = LocalDate.now();
        LocalDate neuesVerfallsdatum = heute.plusMonths(4);
        LocalDate neueLetzteVerladung = neuesVerfallsdatum.minusDays(30);
        BigDecimal erhoehung = SwiftFormat.zufaelligerBetrag();

        String rohtext = ":20:" + referenz + "\n"
                + ":26E:001\n"
                + ":30:" + heute.format(SwiftFormat.DATUM) + "\n"
                + ":31E:" + neuesVerfallsdatum.format(SwiftFormat.DATUM) + "FRANKFURT\n"
                + ":32B:EUR" + SwiftFormat.betrag(erhoehung) + "\n"
                + ":44C:" + neueLetzteVerladung.format(SwiftFormat.DATUM) + "\n"
                + ":46B:\n"
                + "+PACKING LIST IN 3 COPIES\n";

        return new SwiftNachricht("MT707", referenz, rohtext);
    }
}
