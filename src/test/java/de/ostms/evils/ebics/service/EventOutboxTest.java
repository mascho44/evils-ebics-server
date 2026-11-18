package de.ostms.evils.ebics.service;

import tools.jackson.databind.ObjectMapper;
import de.ostms.evils.ebics.domain.PaymentEvent;
import de.ostms.evils.ebics.events.*;
import de.ostms.evils.ebics.persistence.PaymentEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EventOutboxTest {
    @Autowired PaymentHistoryService history;
    @Autowired PaymentEventRepository events;
    @Autowired EventOutboxRepository outbox;
    @Autowired EventOutboxWriter writer;
    @Autowired EventOutboxDelivery delivery;
    @Autowired EventOutboxDispatcher dispatcher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Test void businessEventAndOutboxRollbackTogether(){
        long beforeEvents=events.count(),beforeOutbox=outbox.count();
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{history.record(999L,"TEST_ROLLBACK","must not escape");throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(events.count()).isEqualTo(beforeEvents);assertThat(outbox.count()).isEqualTo(beforeOutbox);
    }
    @Test void stableIdsAndMinimalContractAreDeliveredOnlyAfterAcceptance()throws Exception {
        var payment=new PaymentEvent(UUID.randomUUID().toString(),999L,"BOOKED","secret-account-details-not-for-broker");
        new TransactionTemplate(transactions).executeWithoutResult(tx->{writer.enqueue(payment);writer.enqueue(payment);});
        var row=outbox.findById(payment.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("PENDING");assertThat(row.getEventJson()).doesNotContain("secret-account-details-not-for-broker");
        var event=mapper.readValue(row.getEventJson(),IntegrationEvent.class);
        assertThat(event.id()).isEqualTo(payment.getId());assertThat(event.schemaVersion()).isEqualTo(1);assertThat(event.type()).isEqualTo("payment.booked");
        dispatcher.dispatch();assertThat(outbox.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        AtomicInteger sends=new AtomicInteger();EventPublisher publisher=e->{assertThat(e).isEqualTo(event);sends.incrementAndGet();};
        delivery.deliver(payment.getId(),publisher);delivery.deliver(payment.getId(),publisher);
        assertThat(sends.get()).isEqualTo(1);assertThat(outbox.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo("DELIVERED");
    }
    @Test void failedPublishBacksOffAndEventuallyRemainsDeadForInspection()throws Exception{
        var event=new IntegrationEvent(UUID.randomUUID().toString(),1,"evils-ebics-server","payment.created","payment","999",Instant.now(),new IntegrationEvent.PaymentData(999L,"CREATED"));
        var row=outbox.save(new EventOutbox(event.id(),mapper.writeValueAsString(event),Instant.EPOCH));
        AtomicInteger sends=new AtomicInteger();EventPublisher fail=e->{sends.incrementAndGet();throw new IllegalStateException("secret broker URL");};
        delivery.deliver(row.getId(),fail);delivery.deliver(row.getId(),fail);
        row=outbox.findById(row.getId()).orElseThrow();assertThat(sends.get()).isEqualTo(1);assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());assertThat(row.getLastError()).isEqualTo("IllegalStateException");
        for(int i=1;i<9;i++)row.failed(Instant.EPOCH,"test",10);outbox.save(row);
        delivery.deliver(row.getId(),fail);row=outbox.findById(row.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("DEAD");assertThat(row.getAttempts()).isEqualTo(10);
        delivery.deliver(row.getId(),fail);assertThat(sends.get()).isEqualTo(2);
    }
}
