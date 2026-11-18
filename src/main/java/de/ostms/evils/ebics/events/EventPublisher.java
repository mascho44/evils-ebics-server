package de.ostms.evils.ebics.events;

/** Backend adapter. Success means durable acceptance by the receiving backend.
 * Delivery is at least once: consumers must deduplicate using event.id(). */
@FunctionalInterface
public interface EventPublisher {
    void publish(IntegrationEvent event) throws Exception;
}
