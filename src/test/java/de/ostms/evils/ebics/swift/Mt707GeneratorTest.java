package de.ostms.evils.ebics.swift;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Mt707GeneratorTest {

    private final Mt707Generator generator = new Mt707Generator();

    /** Gleiches Feld-Regex wie corporate-lc-manager's eigener Mt707Parser. */
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
    void feld_20_uebernimmt_die_uebergebene_lc_referenz_unveraendert() {
        SwiftNachricht nachricht = generator.generieren("LC2026123456");

        assertThat(nachricht.referenz()).isEqualTo("LC2026123456");
        Map<String, String> felder = felderParsen(nachricht.rohtext());
        assertThat(felder.get("20")).isEqualTo("LC2026123456");
    }

    @Test
    void erzeugt_eine_frische_referenz_wenn_keine_uebergeben_wird() {
        SwiftNachricht ohneReferenz = generator.generieren(null);
        SwiftNachricht mitLeererReferenz = generator.generieren("   ");

        assertThat(ohneReferenz.referenz()).startsWith("LC");
        assertThat(mitLeererReferenz.referenz()).startsWith("LC");
    }

    @Test
    void erzeugt_alle_vom_corporate_lc_manager_parser_erwarteten_felder() {
        SwiftNachricht nachricht = generator.generieren("LC2026123456");

        Map<String, String> felder = felderParsen(nachricht.rohtext());
        assertThat(felder).containsKeys("20", "26E", "30", "31E", "32B", "44C", "46B");
    }
}
