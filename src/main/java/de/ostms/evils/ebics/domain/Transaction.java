package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity(name = "ledger_entries")
public class Transaction {
    @Id private String transactionId;
    private String iban; private LocalDate bookingDate; private LocalDate valueDate; private BigDecimal amount; private String currency; private boolean credit;
    private String counterpartyName; private String counterpartyIban; private String counterpartyBic; private String purpose; private String endToEndId; private LocalDateTime createdAt;
    protected Transaction() { }
    public Transaction(String transactionId, String iban, LocalDate bookingDate, LocalDate valueDate, BigDecimal amount, String currency, boolean credit, String counterpartyName, String counterpartyIban, String counterpartyBic, String purpose, String endToEndId, LocalDateTime createdAt) { this.transactionId=transactionId; this.iban=iban; this.bookingDate=bookingDate; this.valueDate=valueDate; this.amount=amount; this.currency=currency; this.credit=credit; this.counterpartyName=counterpartyName; this.counterpartyIban=counterpartyIban; this.counterpartyBic=counterpartyBic; this.purpose=purpose; this.endToEndId=endToEndId; this.createdAt=createdAt; }
    public String getTransactionId(){return transactionId;} public String getIban(){return iban;} public LocalDate getBookingDate(){return bookingDate;} public LocalDate getValueDate(){return valueDate;} public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;} public boolean isCredit(){return credit;} public String getCounterpartyName(){return counterpartyName;} public String getCounterpartyIban(){return counterpartyIban;} public String getCounterpartyBic(){return counterpartyBic;} public String getPurpose(){return purpose;} public String getEndToEndId(){return endToEndId;} public LocalDateTime getCreatedAt(){return createdAt;}
    public String transactionId(){return transactionId;} public String iban(){return iban;} public LocalDate bookingDate(){return bookingDate;} public LocalDate valueDate(){return valueDate;} public BigDecimal amount(){return amount;} public String currency(){return currency;} public boolean credit(){return credit;} public String counterpartyName(){return counterpartyName;} public String counterpartyIban(){return counterpartyIban;} public String counterpartyBic(){return counterpartyBic;} public String purpose(){return purpose;} public String endToEndId(){return endToEndId;} public LocalDateTime createdAt(){return createdAt;}
}
