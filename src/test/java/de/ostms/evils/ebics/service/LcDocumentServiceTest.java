package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.protocol.EbicsProtocolException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deckt die Persistenz-Bruecke zwischen den zustandslosen MT7xx-Generatoren und dem spaeteren
 * EBICS-BTD-Abruf ab (siehe EbicsController.mt7xx). */
@SpringBootTest
@ActiveProfiles("test")
class LcDocumentServiceTest {

    @Autowired LcDocumentService service;

    @Test
    void generiertes_mt700_ist_danach_fuer_denselben_partner_abrufbar() {
        var nachricht = service.mt700("EVILSBANK", "LCTEST01", "Applicant GmbH", "Beneficiary Ltd");

        String abgerufen = service.abrufen("EVILSBANK", "LCTEST01", "MT700");

        assertThat(abgerufen).isEqualTo(nachricht.rohtext());
    }

    @Test
    void liefert_bei_mehrfacher_generierung_immer_das_zuletzt_erzeugte_dokument() {
        service.mt700("EVILSBANK", "LCTEST02", "Erster Applicant", "Erster Beneficiary");
        var zweite = service.mt700("EVILSBANK", "LCTEST02", "Zweiter Applicant", "Zweiter Beneficiary");

        String abgerufen = service.abrufen("EVILSBANK", "LCTEST02", "MT700");

        assertThat(abgerufen).isEqualTo(zweite.rohtext());
    }

    @Test
    void ohne_zuvor_generiertes_dokument_wird_klar_abgelehnt_statt_leer_zu_antworten() {
        assertThatThrownBy(() -> service.abrufen("EVILSBANK", "LCTEST-UNBEKANNT", "MT700"))
                .isInstanceOf(EbicsProtocolException.class)
                .hasMessageContaining("MT700")
                .hasMessageContaining("LCTEST-UNBEKANNT");
    }

    @Test
    void verschiedene_mandanten_und_nachrichtentypen_bleiben_getrennt() {
        service.mt700("EVILSBANK", "LCTEST03", "A", "B");
        service.mt707("EVILSBANK", "LCTEST03", "LC2026999999");
        service.mt710("EVILSBANK", "LCTEST03", "Issuing Bank AG", "A", "B");

        assertThat(service.abrufen("EVILSBANK", "LCTEST03", "MT700")).contains(":50:A");
        assertThat(service.abrufen("EVILSBANK", "LCTEST03", "MT707")).contains(":20:LC2026999999");
        assertThat(service.abrufen("EVILSBANK", "LCTEST03", "MT710")).contains(":52A:Issuing Bank AG");
    }
}
