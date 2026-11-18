package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable original bytes for an explicitly published, single-payment VEU order. */
@Entity
public class VeuOrder {
    @Id private Long paymentId;
    @Column(nullable=false,unique=true) private String orderId;
    @Column(nullable=false) private String partnerId;
    @Column(nullable=false) private String originator;
    @Column(nullable=false,columnDefinition="text") private String paymentXml;
    private Instant createdAt;
    protected VeuOrder() {}
    public VeuOrder(Long paymentId,String orderId,String partnerId,String originator,String paymentXml){
        this.paymentId=paymentId;this.orderId=orderId;this.partnerId=partnerId;this.originator=originator;this.paymentXml=paymentXml;this.createdAt=Instant.now();
    }
    public Long getPaymentId(){return paymentId;}
    public String getOrderId(){return orderId;}
    public String getPartnerId(){return partnerId;}
    public String getOriginator(){return originator;}
    public String getPaymentXml(){return paymentXml;}
    public Instant getCreatedAt(){return createdAt;}
}
