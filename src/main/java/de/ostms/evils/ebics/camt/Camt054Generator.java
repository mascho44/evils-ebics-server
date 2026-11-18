package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders booking notifications as camt.054.001.08 XML. A simplified structure: one notification per
 * statement with its entries.
 */
@Component
public class Camt054Generator {

    public String generate(Statement statement) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">
                  <BkToCstmrDbtCdtNtfctn>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%sT00:00:00</CreDtTm>
                    </GrpHdr>
                    <Ntfctn>
                      <Id>%s</Id>
                      <Acct><Id><IBAN>%s</IBAN></Id><Ccy>%s</Ccy></Acct>
                """.formatted(
                escape(statement.statementId()),
                statement.to(),
                escape(statement.statementId()),
                escape(statement.iban()),
                escape(statement.currency())
        ));

        for (Transaction tx : statement.transactions()) {
            xml.append("""
                      <Ntry>
                        <Amt Ccy="%s">%s</Amt>
                        <CdtDbtInd>%s</CdtDbtInd>
                        <BookgDt><Dt>%s</Dt></BookgDt>
                        <NtryDtls>
                          <TxDtls>
                            <Refs><EndToEndId>%s</EndToEndId></Refs>
                            <RmtInf><Ustrd>%s</Ustrd></RmtInf>
                          </TxDtls>
                        </NtryDtls>
                      </Ntry>
                """.formatted(
                    escape(tx.currency()),
                    tx.amount().setScale(2).toPlainString(),
                    tx.credit() ? "CRDT" : "DBIT",
                    tx.bookingDate(),
                    escape(tx.endToEndId()),
                    escape(tx.purpose())
            ));
        }

        xml.append("""
                    </Ntfctn>
                  </BkToCstmrDbtCdtNtfctn>
                </Document>
                """);

        return xml.toString();
    }

    /** Bundles notifications for all accounts into one camt.054 document for a BTD download. */
    public String generateAll(List<Statement> statements) {
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("At least one statement is required");
        }
        String firstDocument = generate(statements.getFirst());
        int firstNotification = firstDocument.indexOf("<Ntfctn>");
        int firstNotificationEnd = firstDocument.indexOf("</Ntfctn>", firstNotification) + "</Ntfctn>".length();
        if (firstNotification < 0 || firstNotificationEnd < "</Ntfctn>".length()) {
            throw new IllegalStateException("Generated CAMT document has no notification block");
        }
        StringBuilder combined = new StringBuilder(firstDocument.substring(0, firstNotification));
        for (Statement statement : statements) {
            String document = generate(statement);
            int notificationStart = document.indexOf("<Ntfctn>");
            int notificationEnd = document.indexOf("</Ntfctn>", notificationStart) + "</Ntfctn>".length();
            combined.append(document, notificationStart, notificationEnd).append('\n');
        }
        combined.append(firstDocument.substring(firstNotificationEnd));
        return combined.toString();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
