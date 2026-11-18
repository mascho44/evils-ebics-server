package de.ostms.evils.ebics.domain;
import jakarta.persistence.*;
@Entity
@Table(name="payment_submissions")
public class PaymentSubmission {
    @Id public String id;
    @Column(nullable=false) public String digest;
    @Column(nullable=false) public Long orderId;
    protected PaymentSubmission() {}
    public PaymentSubmission(String id,String digest,Long orderId){this.id=id;this.digest=digest;this.orderId=orderId;}
    public String getId(){return id;}
    public Long getOrderId(){return orderId;}
}
