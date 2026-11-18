package de.ostms.evils.ebics.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/** Immutable payment draft; construction performs validation before preview or export. */
public record SepaPayment(String debtorName, String debtorIban, String creditorName,
                          String creditorIban, BigDecimal amount, String purpose,
                          LocalDate executionDate, String endToEndId) {
    public SepaPayment {
        debtorName = text(debtorName, "Auftraggeber", 70);
        creditorName = text(creditorName, "Empfänger", 70);
        debtorIban = iban(debtorIban);
        creditorIban = iban(creditorIban);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("999999999.99")) > 0)
            throw new IllegalArgumentException("Betrag muss zwischen 0,01 und 999.999.999,99 EUR liegen.");
        try { amount = amount.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Betrag darf höchstens zwei Nachkommastellen haben."); }
        purpose = text(purpose, "Verwendungszweck", 140);
        endToEndId = text(endToEndId, "End-to-End-ID", 35);
        if (executionDate == null || executionDate.isBefore(LocalDate.now()))
            throw new IllegalArgumentException("Ausführung darf nicht in der Vergangenheit liegen.");
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max)
            throw new IllegalArgumentException(label + ": 1 bis " + max + " Zeichen erforderlich.");
        value = value.strip();
        if (value.codePoints().anyMatch(c -> c < 32 || c == 0xFFFE || c == 0xFFFF))
            throw new IllegalArgumentException(label + " enthält ungültige Zeichen.");
        return value;
    }

    public static String iban(String raw) {
        String value = raw == null ? "" : raw.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}"))
            throw new IllegalArgumentException("Ungültiges IBAN-Format.");
        String rearranged = value.substring(4) + value.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int n = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = (remainder * (n < 10 ? 10 : 100) + n) % 97;
        }
        if (remainder != 1) throw new IllegalArgumentException("IBAN-Prüfsumme ist ungültig.");
        return value;
    }

    public String toXml(String messageId, OffsetDateTime created) {
        text(messageId, "Nachrichten-ID", 35);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn>"
            + "<GrpHdr><MsgId>" + xml(messageId) + "</MsgId><CreDtTm>" + created + "</CreDtTm><NbOfTxs>1</NbOfTxs><CtrlSum>" + amount.toPlainString()
            + "</CtrlSum><InitgPty><Nm>" + xml(debtorName) + "</Nm></InitgPty></GrpHdr>"
            + "<PmtInf><PmtInfId>" + xml(messageId) + "</PmtInfId><PmtMtd>TRF</PmtMtd><NbOfTxs>1</NbOfTxs><CtrlSum>" + amount.toPlainString()
            + "</CtrlSum><PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl></PmtTpInf><ReqdExctnDt><Dt>" + executionDate + "</Dt></ReqdExctnDt>"
            + "<Dbtr><Nm>" + xml(debtorName) + "</Nm></Dbtr><DbtrAcct><Id><IBAN>" + debtorIban + "</IBAN></Id></DbtrAcct>"
            + "<DbtrAgt><FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId></DbtrAgt><ChrgBr>SLEV</ChrgBr>"
            + "<CdtTrfTxInf><PmtId><EndToEndId>" + xml(endToEndId) + "</EndToEndId></PmtId><Amt><InstdAmt Ccy=\"EUR\">" + amount.toPlainString()
            + "</InstdAmt></Amt><Cdtr><Nm>" + xml(creditorName) + "</Nm></Cdtr><CdtrAcct><Id><IBAN>" + creditorIban
            + "</IBAN></Id></CdtrAcct><RmtInf><Ustrd>" + xml(purpose) + "</Ustrd></RmtInf></CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
    }

    public static String newId() { return "PAY" + UUID.randomUUID().toString().replace("-", ""); }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
