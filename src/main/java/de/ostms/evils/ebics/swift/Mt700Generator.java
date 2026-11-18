package de.ostms.evils.ebics.swift;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Erzeugt eine realistische MT700-Demo-Nachricht (Dokumentenakkreditiv-Eroeffnung) fuer Vorfuehr-/
 * Testzwecke - im selben schlanken Feld-Dialekt, den corporate-lc-manager's eigener Mt700Parser
 * erwartet (siehe SwiftFormat-Klassenkommentar): kein vollstaendiges ISO-15022-SWIFT, nur die
 * Pflicht-/haeufigen Felder :20:/:31C:/:31D:/:32B:/:44C:/:50:/:59:/:46A:, damit eine hier per
 * Copy-paste generierte Nachricht dort direkt einlesbar ist. */
@Component
public class Mt700Generator {

    public SwiftNachricht generieren(String applicant, String beneficiary) {
        String referenz = SwiftFormat.referenz("LC", 6);
        LocalDate heute = LocalDate.now();
        LocalDate verfallsdatum = heute.plusMonths(3);
        LocalDate letzteVerladung = verfallsdatum.minusDays(30);
        BigDecimal betrag = SwiftFormat.zufaelligerBetrag();

        String rohtext = ":20:" + referenz + "\n"
                + ":31C:" + heute.format(SwiftFormat.DATUM) + "\n"
                + ":31D:" + verfallsdatum.format(SwiftFormat.DATUM) + "FRANKFURT\n"
                + ":32B:EUR" + SwiftFormat.betrag(betrag) + "\n"
                + ":44C:" + letzteVerladung.format(SwiftFormat.DATUM) + "\n"
                + ":50:" + applicant + "\n"
                + ":59:" + beneficiary + "\n"
                + ":46A:+SIGNED COMMERCIAL INVOICE IN 3 ORIGINALS\n"
                + "+PACKING LIST\n"
                + "+FULL SET CLEAN ON BOARD BILL OF LADING\n";

        return new SwiftNachricht("MT700", referenz, rohtext);
    }
}
