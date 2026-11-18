package de.ostms.evils.ebics.events;
import java.time.Instant;
public record IntegrationEvent(String id,int schemaVersion,String source,String type,
                               String aggregateType,String aggregateId,Instant occurredAt,PaymentData data) {
    /** No names, account numbers, amounts, XML, signatures or keys in the initial contract. */
    public record PaymentData(Long paymentId,String kind) {}
}
