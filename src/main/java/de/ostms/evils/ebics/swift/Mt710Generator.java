package de.ostms.evils.ebics.swift;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Erzeugt eine realistische MT710-Demo-Nachricht (Avisierung eines von einer DRITTEN/avisierenden
 * Bank weitergeleiteten Dokumentenakkreditivs) - inhaltlich nah an MT700, aber mit :52A: (der
 * ISSUING BANK, die das Original-Akkreditiv eroeffnet hat) als unterscheidendem Pflichtfeld, sonst
 * waere eine MT710 von einer MT700 nicht zu unterscheiden. Gleicher schlanker Feld-Dialekt wie
 * Mt700Generator/Mt707Generator, siehe SwiftFormat - anders als bei MT700/MT707 gibt es in
 * corporate-lc-manager noch keinen Mt710Parser, der Feld-Dialekt orientiert sich deshalb hier direkt
 * am realen SWIFT-MT710-Feldsatz statt an einem bestehenden Parser. */
@Component
public class Mt710Generator {

    public SwiftNachricht generieren(String issuingBank, String applicant, String beneficiary) {
        String referenz = SwiftFormat.referenz("LC", 6);
        LocalDate heute = LocalDate.now();
        LocalDate verfallsdatum = heute.plusMonths(3);
        LocalDate letzteVerladung = verfallsdatum.minusDays(30);
        BigDecimal betrag = SwiftFormat.zufaelligerBetrag();

        String rohtext = ":20:" + referenz + "\n"
                + ":21:" + referenz + "\n"
                + ":31C:" + heute.format(SwiftFormat.DATUM) + "\n"
                + ":31D:" + verfallsdatum.format(SwiftFormat.DATUM) + "FRANKFURT\n"
                + ":32B:EUR" + SwiftFormat.betrag(betrag) + "\n"
                + ":44C:" + letzteVerladung.format(SwiftFormat.DATUM) + "\n"
                + ":50:" + applicant + "\n"
                + ":52A:" + issuingBank + "\n"
                + ":59:" + beneficiary + "\n"
                + ":46A:+SIGNED COMMERCIAL INVOICE IN 3 ORIGINALS\n"
                + "+PACKING LIST\n"
                + "+FULL SET CLEAN ON BOARD BILL OF LADING\n";

        return new SwiftNachricht("MT710", referenz, rohtext);
    }
}
