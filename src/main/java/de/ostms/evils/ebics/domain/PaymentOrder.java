package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity(name = "payment_orders")
public class PaymentOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String iban; private BigDecimal amount; private String currency;
    private String beneficiaryName; private String beneficiaryIban; private String beneficiaryBic;
    private String purpose; private String endToEndId; private LocalDate executionDate;
    private String originalMessageId;
    private String originalMessageName;
    private String originalPaymentInformationId;
    private String originalInstructionId;
    private String ebicsOrderId;
    private String submissionId;
    private String submittedBy;
    private String serviceScope;
    private String serviceOption;
    public String getServiceScope(){return serviceScope;} public String getServiceOption(){return serviceOption;}
    public void setServiceParameters(String scope,String option){serviceScope=scope;serviceOption=option;}
    @Enumerated(EnumType.STRING) private PaymentOrderStatus status;
    private LocalDateTime createdAt; private LocalDateTime approvedAt; private LocalDateTime statusReportRetrievedAt;
    protected PaymentOrder() { }
    public PaymentOrder(String iban, BigDecimal amount, String currency, String beneficiaryName, String beneficiaryIban, String beneficiaryBic, String purpose, String endToEndId, LocalDate executionDate) { this.iban=iban; this.amount=amount; this.currency=currency; this.beneficiaryName=beneficiaryName; this.beneficiaryIban=beneficiaryIban; this.beneficiaryBic=beneficiaryBic; this.purpose=purpose; this.endToEndId=endToEndId; this.executionDate=executionDate; this.status=PaymentOrderStatus.PENDING_SIGNATURES; this.createdAt=LocalDateTime.now(); }
    public Long getId(){return id;} public String getIban(){return iban;} public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;} public String getBeneficiaryName(){return beneficiaryName;} public String getBeneficiaryIban(){return beneficiaryIban;} public String getBeneficiaryBic(){return beneficiaryBic;} public String getPurpose(){return purpose;} public String getEndToEndId(){return endToEndId;} public LocalDate getExecutionDate(){return executionDate;} public PaymentOrderStatus getStatus(){return status;} public LocalDateTime getCreatedAt(){return createdAt;} public LocalDateTime getApprovedAt(){return approvedAt;} public LocalDateTime getStatusReportRetrievedAt(){return statusReportRetrievedAt;} public String getOriginalMessageId(){return originalMessageId;} public String getLedgerTransactionId(){return status==PaymentOrderStatus.APPROVED&&id!=null?"PAY-"+id:null;}
    public void setOriginalMessageId(String originalMessageId) { this.originalMessageId=originalMessageId; }
    public String getOriginalMessageName(){return originalMessageName;}
    public String getOriginalPaymentInformationId(){return originalPaymentInformationId;}
    public String getOriginalInstructionId(){return originalInstructionId;}
    public String getEbicsOrderId(){return ebicsOrderId;}
    public String getSubmissionId(){return submissionId;}
    public String getSubmittedBy(){return submittedBy;}
    public void setUploadReferences(String messageName,String paymentInformationId,String instructionId,String ebicsOrderId,String submissionId,String submittedBy){
        this.originalMessageName=messageName;this.originalPaymentInformationId=paymentInformationId;this.originalInstructionId=instructionId;
        this.ebicsOrderId=ebicsOrderId;this.submissionId=submissionId;this.submittedBy=submittedBy;
    }
    public void approve() { this.status=PaymentOrderStatus.APPROVED; this.approvedAt=LocalDateTime.now(); }
    public void schedule() { this.status=PaymentOrderStatus.SCHEDULED; }
    public void markStatusReportRetrieved() { this.statusReportRetrievedAt=LocalDateTime.now(); }
}
