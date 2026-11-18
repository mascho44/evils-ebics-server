package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity(name = "accounts")
public class Account {
    @Id private String iban;
    /** Which tenant (Bank.hostId) this account belongs to - always the same as its Customer's
     * hostId (see BankService.createAccount). */
    private String hostId;
    private String bic; private String partnerId; private String accountHolder;
    private BigDecimal openingBalance; private LocalDate openingBalanceDate;
    private String currency; private boolean active;
    protected Account() { }
    public Account(String hostId, String iban, String bic, String partnerId, String accountHolder, BigDecimal openingBalance, LocalDate openingBalanceDate, String currency, boolean active) { this.hostId=hostId; this.iban=iban; this.bic=bic; this.partnerId=partnerId; this.accountHolder=accountHolder; this.openingBalance=openingBalance; this.openingBalanceDate=openingBalanceDate; this.currency=currency; this.active=active; }
    public String getHostId(){return hostId;}
    public String getIban(){return iban;} public String getBic(){return bic;} public String getPartnerId(){return partnerId;} public String getAccountHolder(){return accountHolder;} public BigDecimal getOpeningBalance(){return openingBalance;} public LocalDate getOpeningBalanceDate(){return openingBalanceDate;} public String getCurrency(){return currency;} public boolean isActive(){return active;}
    public BigDecimal balance(){return openingBalance;}
    public String iban(){return iban;} public String bic(){return bic;} public String partnerId(){return partnerId;} public String accountHolder(){return accountHolder;} public String currency(){return currency;} public boolean active(){return active;}
}
