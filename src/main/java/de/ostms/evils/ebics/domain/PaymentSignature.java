package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity(name = "payment_signatures")
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"paymentOrderId", "userId"}))
public class PaymentSignature {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long paymentOrderId; private String userId; private String signatureClass; private LocalDateTime signedAt;
    protected PaymentSignature() { }
    public PaymentSignature(Long paymentOrderId, String userId, String signatureClass) { this.paymentOrderId=paymentOrderId; this.userId=userId; this.signatureClass=signatureClass; this.signedAt=LocalDateTime.now(); }
    public Long getId(){return id;} public Long getPaymentOrderId(){return paymentOrderId;} public String getUserId(){return userId;} public String getSignatureClass(){return signatureClass;} public LocalDateTime getSignedAt(){return signedAt;}
}
