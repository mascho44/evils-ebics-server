package de.ostms.evils.ebics.events;

import tools.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional RabbitMQ adapter. It is absent unless explicitly enabled. */
@Component
@ConditionalOnProperty(name = "evils.events.rabbit.enabled", havingValue = "true")
public class RabbitMqEventPublisher implements EventPublisher {
    private final RabbitTemplate rabbit; private final ObjectMapper mapper;
    private final String exchange; private final String routingPrefix;
    public RabbitMqEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
                                  @Value("${evils.events.rabbit.exchange:evils.events}") String exchange,
                                  @Value("${evils.events.rabbit.routing-prefix:payment.}") String routingPrefix) {
        this.rabbit=rabbit; this.mapper=mapper; this.exchange=exchange; this.routingPrefix=routingPrefix;
        if (!(rabbit.getConnectionFactory() instanceof org.springframework.amqp.rabbit.connection.CachingConnectionFactory factory)) throw new IllegalStateException("RabbitMQ requires a caching connection factory");
        factory.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        rabbit.setMandatory(true);
    }
    @Override public void publish(IntegrationEvent event) throws Exception {
        byte[] body=mapper.writeValueAsBytes(event);
        var correlation = new org.springframework.amqp.rabbit.connection.CorrelationData(java.util.UUID.randomUUID().toString());
        rabbit.convertAndSend(exchange, routingPrefix+event.data().kind().toLowerCase(java.util.Locale.ROOT), body, message -> {
            message.getMessageProperties().setContentType("application/json");
            message.getMessageProperties().setMessageId(event.id().toString());
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setHeader("schema-version", event.schemaVersion());
            return message;
        }, correlation);
        var confirm = correlation.getFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("Broker rejected or could not route event");
    }
}
