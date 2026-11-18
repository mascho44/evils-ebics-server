package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.Statement;
import org.springframework.stereotype.Component;

import java.util.List;

/** camt.052 intraday account report using the same live ledger as camt.053. */
@Component
public class Camt052Generator {
    private final Camt053Generator statements;

    public Camt052Generator(Camt053Generator statements) { this.statements = statements; }

    public String generate(Statement statement) {
        return statements.generate(statement)
                .replace("camt.053.001.08", "camt.052.001.08")
                .replace("<BkToCstmrStmt>", "<BkToCstmrAcctRpt>")
                .replace("</BkToCstmrStmt>", "</BkToCstmrAcctRpt>")
                .replace("<Stmt>", "<Rpt>")
                .replace("</Stmt>", "</Rpt>")
                .replace("                      <ElctrncSeqNb>1</ElctrncSeqNb>\n", "");
    }

    public String generateAll(List<Statement> statements) {
        if (statements == null || statements.isEmpty()) throw new IllegalArgumentException("At least one statement is required");
        String first = generate(statements.getFirst());
        int start = first.indexOf("<Rpt>"), end = first.indexOf("</Rpt>", start) + "</Rpt>".length();
        StringBuilder combined = new StringBuilder(first.substring(0, start));
        for (Statement statement : statements) {
            String report = generate(statement);
            int reportStart = report.indexOf("<Rpt>"), reportEnd = report.indexOf("</Rpt>", reportStart) + "</Rpt>".length();
            combined.append(report, reportStart, reportEnd).append('\n');
        }
        return combined.append(first.substring(end)).toString();
    }
}
