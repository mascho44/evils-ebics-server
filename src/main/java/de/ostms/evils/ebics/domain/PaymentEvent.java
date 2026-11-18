package de.ostms.evils.ebics.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class PaymentEvent {
    @Id private String id;
    private Long paymentOrderId;
    private String kind;
    @Column(length=1000) private String detail;
    private Instant occurredAt;
    protected PaymentEvent(){}
    public PaymentEvent(String id,Long orderId,String kind,String detail){this.id=id;this.paymentOrderId=orderId;this.kind=kind;this.detail=detail;this.occurredAt=Instant.now();}
    public String getId(){return id;} public Long getPaymentOrderId(){return paymentOrderId;} public String getKind(){return kind;} public String getDetail(){return detail;} public Instant getOccurredAt(){return occurredAt;}
}
