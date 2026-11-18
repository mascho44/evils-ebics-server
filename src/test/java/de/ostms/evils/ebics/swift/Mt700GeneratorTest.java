package de.ostms.evils.ebics.swift;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Mt700GeneratorTest {

    private final Mt700Generator generator = new Mt700Generator();

    /** Gleiches Feld-Regex wie corporate-lc-manager's eigener Mt700Parser (siehe dessen FIELD-
     * Konstante) - stellt sicher, dass eine hier generierte Nachricht dort tatsaechlich einlesbar
     * waere, nicht nur "sieht plausibel aus". */
    private static final Pattern FIELD = Pattern.compile("(?m)^:(\\d{2}[A-Z]?):(.*?)(?=^:\\d{2}[A-Z]?:|\\z)", Pattern.DOTALL);

    private Map<String, String> felderParsen(String rohtext) {
        Map<String, String> felder = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(rohtext.strip());
        while (m.find()) {
            felder.put(m.group(1), m.group(2).trim());
        }
        return felder;
    }

    @Test
    void erzeugt_alle_vom_corporate_lc_manager_parser_erwarteten_felder() {
        SwiftNachricht nachricht = generator.generieren("Applicant GmbH", "Beneficiary Ltd");

        assertThat(nachricht.messageType()).isEqualTo("MT700");
        Map<String, String> felder = felderParsen(nachricht.rohtext());
        assertThat(felder).containsKeys("20", "31C", "31D", "32B", "44C", "50", "59", "46A");
        assertThat(felder.get("20")).isEqualTo(nachricht.referenz());
        assertThat(felder.get("50")).isEqualTo("Applicant GmbH");
        assertThat(felder.get("59")).isEqualTo("Beneficiary Ltd");
    }

    @Test
    void referenz_beginnt_mit_lc_und_aktuellem_jahr() {
        SwiftNachricht nachricht = generator.generieren("A", "B");

        assertThat(nachricht.referenz()).startsWith("LC" + LocalDate.now().getYear());
    }

    @Test
    void betrag_ist_ein_gueltiges_swift_dezimalformat() {
        SwiftNachricht nachricht = generator.generieren("A", "B");

        Map<String, String> felder = felderParsen(nachricht.rohtext());
        String betragsfeld = felder.get("32B").replace("\n", "").trim();
        assertThat(betragsfeld).matches("EUR\\d+,\\d{2}");
        BigDecimal betrag = new BigDecimal(betragsfeld.substring(3).replace(',', '.'));
        assertThat(betrag).isBetween(new BigDecimal("10000.00"), new BigDecimal("500000.00"));
    }

    @Test
    void verfallsdatum_liegt_nach_ausstellungsdatum_und_platz_ist_angehaengt() {
        SwiftNachricht nachricht = generator.generieren("A", "B");

        Map<String, String> felder = felderParsen(nachricht.rohtext());
        assertThat(felder.get("31D")).startsWith(LocalDate.now().plusMonths(3).format(SwiftFormat.DATUM));
        assertThat(felder.get("31D")).endsWith("FRANKFURT");
    }
}
