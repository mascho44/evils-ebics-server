package de.ostms.evils.ebics.swift;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Mt760GeneratorTest {

    private final Mt760Generator generator = new Mt760Generator();

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
    void erzeugt_die_ueblichen_urdg_garantie_felder() {
        SwiftNachricht nachricht = generator.generieren("Applicant GmbH", "Beneficiary Ltd");

        assertThat(nachricht.messageType()).isEqualTo("MT760");
        Map<String, String> felder = felderParsen(nachricht.rohtext());
        assertThat(felder).containsKeys("27", "20", "22A", "22D", "30", "40C", "32B", "50", "59", "77C");
        assertThat(felder.get("40C")).isEqualTo("URDG");
        assertThat(felder.get("50")).isEqualTo("Applicant GmbH");
        assertThat(felder.get("59")).isEqualTo("Beneficiary Ltd");
        assertThat(felder.get("20")).isEqualTo(nachricht.referenz());
    }

    @Test
    void referenz_beginnt_mit_gtee_und_aktuellem_jahr() {
        SwiftNachricht nachricht = generator.generieren("A", "B");

        assertThat(nachricht.referenz()).startsWith("GTEE" + LocalDate.now().getYear());
    }
}
