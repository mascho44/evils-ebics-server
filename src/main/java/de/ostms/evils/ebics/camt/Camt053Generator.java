package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.Statement;
import de.ostms.evils.ebics.domain.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Renders account statements as ISO 20022 camt.053.001.08 XML (the bank-to-customer statement), with the
 * account currency and opening and closing balance taken from the ledger. {@link #generateAll} puts
 * several statements into one document. The output is validated against the bundled schema in the tests.
 */
@Component
public class Camt053Generator {

    public String generate(Statement statement) {
        StringBuilder xml = new StringBuilder();

        xml.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
                  <BkToCstmrStmt>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%sT00:00:00</CreDtTm>
                    </GrpHdr>
                    <Stmt>
                      <Id>%s</Id>
                      <ElctrncSeqNb>1</ElctrncSeqNb>
                      <CreDtTm>%sT23:59:59</CreDtTm>
                      <FrToDt>
                        <FrDtTm>%sT00:00:00</FrDtTm>
                        <ToDtTm>%sT23:59:59</ToDtTm>
                      </FrToDt>
                      <Acct>
                        <Id><IBAN>%s</IBAN></Id>
                        <Ccy>%s</Ccy>
                      </Acct>
                      <Bal>
                        <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
                        <Amt Ccy="%s">%s</Amt>
                        <CdtDbtInd>%s</CdtDbtInd>
                        <Dt><Dt>%s</Dt></Dt>
                      </Bal>
                """.formatted(
                escape(reference(statement.statementId())),
                statement.to(),
                escape(reference(statement.statementId())),
                statement.to(),
                statement.from(),
                statement.to(),
                escape(statement.iban()),
                escape(statement.currency()),
                escape(statement.currency()),
                money(statement.openingBalance()),
                indicator(statement.openingBalance()),
                statement.from()
        ));

        xml.append("""
                      <Bal>
                        <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
                        <Amt Ccy="%s">%s</Amt>
                        <CdtDbtInd>%s</CdtDbtInd>
                        <Dt><Dt>%s</Dt></Dt>
                      </Bal>
                """.formatted(
                escape(statement.currency()), money(statement.closingBalance()),
                indicator(statement.closingBalance()),
                statement.to()
        ));

        for (Transaction tx : statement.transactions()) {
        xml.append("""
                      <Ntry>
                        <NtryRef>%s</NtryRef>
                        <Amt Ccy="%s">%s</Amt>
                        <CdtDbtInd>%s</CdtDbtInd>
                        <Sts><Cd>BOOK</Cd></Sts>
                        <BookgDt><Dt>%s</Dt></BookgDt>
                        <ValDt><Dt>%s</Dt></ValDt>
                        <AcctSvcrRef>%s</AcctSvcrRef>
                        <BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>%s</Cd><SubFmlyCd>ESCT</SubFmlyCd></Fmly></Domn><Prtry><Cd>166</Cd><Issr>DK</Issr></Prtry></BkTxCd>
                        <NtryDtls>
                          <TxDtls>
                            <Refs>
                              <EndToEndId>%s</EndToEndId>
                            </Refs>
                            <Amt Ccy="%s">%s</Amt>
                            <CdtDbtInd>%s</CdtDbtInd>
                            <BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>%s</Cd><SubFmlyCd>ESCT</SubFmlyCd></Fmly></Domn><Prtry><Cd>NTRF+166</Cd><Issr>DK</Issr></Prtry></BkTxCd>
                            <RltdPties>
                              <Dbtr>
                                <Pty><Nm>%s</Nm></Pty>
                              </Dbtr>
                              <Cdtr>
                                <Pty><Nm>%s</Nm></Pty>
                              </Cdtr>
                            </RltdPties>
                            <RmtInf>
                              <Ustrd>%s</Ustrd>
                            </RmtInf>
                          </TxDtls>
                        </NtryDtls>
                        <AddtlNtryInf>SEPA %s</AddtlNtryInf>
                      </Ntry>
                    """.formatted(
                    escape(reference(tx.transactionId())),
                    escape(tx.currency()),
                    money(tx.amount()),
                    tx.credit() ? "CRDT" : "DBIT",
                    tx.bookingDate(),
                    tx.valueDate(),
                    escape(reference(tx.transactionId())),
                    tx.credit() ? "RCDT" : "ICDT",
                    escape(tx.endToEndId()==null||tx.endToEndId().isBlank()?"NOTPROVIDED":tx.endToEndId()),
                    escape(tx.currency()),
                    money(tx.amount()),
                    tx.credit() ? "CRDT" : "DBIT",
                    tx.credit() ? "RCDT" : "ICDT",
                    tx.credit() ? escape(tx.counterpartyName()) : "EVILS Demo GmbH",
                    tx.credit() ? "EVILS Demo GmbH" : escape(tx.counterpartyName()),
                    escape(tx.purpose()),
                    tx.credit() ? "GUTSCHRIFT" : "LASTSCHRIFT"
            ));
        }

        xml.append("""
                    </Stmt>
                  </BkToCstmrStmt>
                </Document>
                """);

        return xml.toString();
    }

    /**
     * Produces a single standards-compliant camt.053 document with one statement block for
     * every account. EBICS BTD requests need not carry an account selector; in that case the
     * partner receives all of its permitted accounts in the requested date range.
     */
    public String generateAll(List<Statement> statements) {
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("At least one statement is required");
        }

        String firstDocument = generate(statements.getFirst());
        int firstStatement = firstDocument.indexOf("<Stmt>");
        int firstStatementEnd = firstDocument.indexOf("</Stmt>", firstStatement) + "</Stmt>".length();
        if (firstStatement < 0 || firstStatementEnd < "</Stmt>".length()) {
            throw new IllegalStateException("Generated CAMT document has no statement block");
        }

        StringBuilder combined = new StringBuilder(firstDocument.substring(0, firstStatement));
        for (Statement statement : statements) {
            String document = generate(statement);
            int statementStart = document.indexOf("<Stmt>");
            int statementEnd = document.indexOf("</Stmt>", statementStart) + "</Stmt>".length();
            combined.append(document, statementStart, statementEnd).append('\n');
        }
        combined.append(firstDocument.substring(firstStatementEnd));
        return combined.toString();
    }

    private String money(BigDecimal value) {
        return value.abs().setScale(2).toPlainString();
    }
    private static String reference(String value){
        if(value.length()<=35)return value;
        try{return "EV-"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,32);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private String indicator(BigDecimal value) {
        return value.signum() >= 0 ? "CRDT" : "DBIT";
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
