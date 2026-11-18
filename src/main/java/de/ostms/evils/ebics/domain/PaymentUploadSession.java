package de.ostms.evils.ebics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Server-side state for a standard multi-phase BTU upload. No payment data is retained here. */
@Entity
@Table(name = "payment_upload_sessions")
public class PaymentUploadSession {
    @Id private String transactionId;
    private String partnerId;
    private String userId;
    private String ebicsOrderId;
    private String serviceScope;
    private String serviceOption;
    private String acceptedDigest;
    public String getAcceptedDigest(){return acceptedDigest;} public void setAcceptedDigest(String digest){acceptedDigest=digest;}
    public String getServiceScope(){return serviceScope;} public String getServiceOption(){return serviceOption;}
    public void setServiceParameters(String scope,String option){serviceScope=scope;serviceOption=option;}
    @Column(length = 8192)
    private String encryptedTransactionKey;
    private LocalDateTime createdAt;
    protected PaymentUploadSession() { }
    public PaymentUploadSession(String transactionId, String partnerId, String userId, String encryptedTransactionKey) {
        this.transactionId=transactionId; this.partnerId=partnerId; this.userId=userId; this.encryptedTransactionKey=encryptedTransactionKey; this.createdAt=LocalDateTime.now();
    }
    public String getTransactionId(){return transactionId;}
    public String getEbicsOrderId(){return ebicsOrderId;}
    public void setEbicsOrderId(String value){ebicsOrderId=value;}
    public String getPartnerId(){return partnerId;}
    public String getUserId(){return userId;}
    public String getEncryptedTransactionKey(){return encryptedTransactionKey;}
    public LocalDateTime getCreatedAt(){return createdAt;}
}
