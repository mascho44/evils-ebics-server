package de.ostms.evils.ebics.swift;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Erzeugt eine realistische MT760-Demo-Nachricht (Bankgarantie-Eroeffnung, URDG-Standard) fuer
 * Vorfuehr-/Testzwecke. Anders als MT700/MT707 hat corporate-lc-manager dafuer (noch) keinen
 * eigenen Parser (siehe dessen ROADMAP.md "Weitere SWIFT-Profile" unter "Spaeter") - Feld-Dialekt
 * folgt trotzdem demselben schlanken Muster (siehe SwiftFormat) fuer spaetere Kompatibilitaet. */
@Component
public class Mt760Generator {

    public SwiftNachricht generieren(String applicant, String beneficiary) {
        String referenz = SwiftFormat.referenz("GTEE", 4);
        BigDecimal betrag = SwiftFormat.zufaelligerBetrag();

        String rohtext = ":27:1/1\n"
                + ":20:" + referenz + "\n"
                + ":22A:ISSU\n"
                + ":22D:DGAR\n"
                + ":30:" + LocalDate.now().format(SwiftFormat.DATUM) + "\n"
                + ":40C:URDG\n"
                + ":32B:EUR" + SwiftFormat.betrag(betrag) + "\n"
                + ":50:" + applicant + "\n"
                + ":59:" + beneficiary + "\n"
                + ":77C:WE HEREBY ISSUE THIS DEMAND GUARANTEE.\n";

        return new SwiftNachricht("MT760", referenz, rohtext);
    }
}
