package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Idempotency marker for the daily test-statement generator. */
@Entity(name = "daily_statement_runs")
public class DailyStatementRun {
    @Id private String runId;
    private String iban;
    private LocalDate runDate;
    private LocalDateTime createdAt;

    protected DailyStatementRun() { }
    public DailyStatementRun(String runId, String iban, LocalDate runDate) {
        this.runId = runId; this.iban = iban; this.runDate = runDate; this.createdAt = LocalDateTime.now();
    }
    public String getRunId() { return runId; }
    public String getIban() { return iban; }
    public LocalDate getRunDate() { return runDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
