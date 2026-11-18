package de.ostms.evils.ebics.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IbanGeneratorTest {

    /** ISO 7064 mod 97-10: Laenderkennung ans Ende der BBAN verschoben, Buchstaben in Ziffern
     * umgewandelt (A=10..Z=35), das Ganze modulo 97 muss 1 ergeben - unabhaengige Nachrechnung der
     * Pruefziffer, nicht dieselbe Formel wie in IbanGenerator selbst. */
    private static boolean istGueltigeIban(String iban) {
        String umgestellt = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numerisch = new StringBuilder();
        for (char c : umgestellt.toCharArray()) {
            // Bewusst KEIN "Character.isLetter(c) ? (c - 'A' + 10) : c" - der Java-Ternary
            // widened dabei BEIDE Zweige auf int (gemeinsamer Typ von int und char), wodurch
            // append() fuer Ziffern faelschlich ihren ASCII-Code statt der Ziffer selbst anhaengt.
            if (Character.isLetter(c)) {
                numerisch.append(c - 'A' + 10);
            } else {
                numerisch.append(c);
            }
        }
        return new BigInteger(numerisch.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    @Test
    void zufaellig_liefert_mod97_gueltige_deutsche_iban() {
        for (int i = 0; i < 50; i++) {
            String iban = IbanGenerator.zufaellig();
            assertThat(iban).startsWith("DE").hasSize(22);
            assertThat(istGueltigeIban(iban)).as("IBAN %s sollte gueltig sein", iban).isTrue();
        }
    }

    @Test
    void vonSeed_ist_deterministisch_und_gueltig() {
        String erste = IbanGenerator.vonSeed("mein-seed");
        String zweite = IbanGenerator.vonSeed("mein-seed");
        assertThat(erste).isEqualTo(zweite);
        assertThat(istGueltigeIban(erste)).isTrue();

        assertThat(IbanGenerator.vonSeed("anderer-seed")).isNotEqualTo(erste);
    }

    @Test
    void zufaelligUndUnbenutzt_ueberspringt_bereits_vergebene_ibans() {
        String vergeben = IbanGenerator.zufaellig();
        String neue = IbanGenerator.zufaelligUndUnbenutzt(iban -> !iban.equals(vergeben));
        assertThat(neue).isNotEqualTo(vergeben);
        assertThat(istGueltigeIban(neue)).isTrue();
    }

    @Test
    void zufaelligUndUnbenutzt_bricht_ab_statt_endlos_zu_laufen() {
        assertThatThrownBy(() -> IbanGenerator.zufaelligUndUnbenutzt(iban -> false))
                .isInstanceOf(IllegalStateException.class);
    }
}
