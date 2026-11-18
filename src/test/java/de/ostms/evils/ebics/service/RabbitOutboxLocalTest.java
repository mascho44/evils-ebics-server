package de.ostms.evils.ebics.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.ostms.evils.ebics.domain.Account;
import de.ostms.evils.ebics.events.*;
import de.ostms.evils.ebics.persistence.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: isolated H2 database and the local reference broker only. */
@EnabledIfSystemProperty(named = "evils.localRabbitTest", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rabbit-outbox-local;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=5673",
    "spring.rabbitmq.username=evils-demo", "spring.rabbitmq.password=local-test-only",
    "spring.rabbitmq.virtual-host=/", "spring.rabbitmq.connection-timeout=5s",
    "evils.events.rabbit.enabled=true", "evils.events.rabbit.exchange=evils.events",
    "evils.events.dispatch-enabled=false", "evils.statements.daily.enabled=false"
})
class RabbitOutboxLocalTest {
    @Autowired PaymentOrderService payments;
    @Autowired PaymentHistoryService history;
    @Autowired AccountRepository accounts;
    @Autowired EventOutboxRepository outbox;
    @Autowired EventOutboxDelivery delivery;
    @Autowired RabbitMqEventPublisher publisher;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private JsonNode metrics() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8091/metrics"))
            .timeout(Duration.ofSeconds(3)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private void awaitMetrics(Predicate<JsonNode> expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            if (expected.test(metrics())) return;
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Consumer metrics did not reach expected state");
    }

    @Test void serverPaymentReachesBackendOnceEvenAfterRedelivery() throws Exception {
        var before = metrics();
        assertThat(before.path("healthy").asBoolean()).isTrue();
        String iban = "DE43123456789012345678";
        accounts.save(new Account("EVILSBANK", iban, "EVILDEFFXXX", "LOCAL", "Local outbox test",
            BigDecimal.ZERO, LocalDate.now(), "EUR", true));
        var payment = payments.create(iban, new BigDecimal("10.00"), "Local test recipient",
            "DE79500800000878741400", "TESTDEFFXXX", "Local backend test", "LOCAL-OUTBOX", LocalDate.now());
        var event = history.forOrder(payment.getId()).stream()
            .filter(e -> "CREATED".equals(e.getKind())).findFirst().orElseThrow();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        delivery.deliver(event.getId(), publisher);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("DELIVERED");
        awaitMetrics(m -> m.path("processed").asLong() == before.path("processed").asLong() + 1);
        // Simulate the documented crash window after broker acceptance but before DB commit.
        publisher.publish(mapper.readValue(outbox.findById(event.getId()).orElseThrow().getEventJson(), IntegrationEvent.class));
        awaitMetrics(m -> m.path("duplicates").asLong() == before.path("duplicates").asLong() + 1);
        assertThat(metrics().path("processed").asLong()).isEqualTo(before.path("processed").asLong() + 1);
        System.out.println("LOCAL_OUTBOX_VERIFIED event=" + event.getId() + " payment=" + payment.getId());
    }
}
