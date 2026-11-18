package de.ostms.evils.ebics.events;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
@Service
public class EventOutboxDispatcher {
    private final boolean enabled;
    private final ObjectProvider<EventPublisher> publishers;
    private final EventOutboxRepository outbox;
    private final EventOutboxDelivery delivery;
    public EventOutboxDispatcher(@Value("${evils.events.dispatch-enabled:false}") boolean enabled,ObjectProvider<EventPublisher> publishers,EventOutboxRepository outbox,EventOutboxDelivery delivery){
        this.enabled=enabled;this.publishers=publishers;this.outbox=outbox;this.delivery=delivery;
    }
    @Scheduled(fixedDelayString="${evils.events.poll-ms:5000}")
    public void dispatch(){
        if(!enabled)return;
        var publisher=publishers.getIfAvailable();
        if(publisher==null)return; // No implicit network destination and no dummy successful deliveries.
        for(var row:outbox.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc("PENDING",Instant.now())){
            if(Thread.currentThread().isInterrupted())return;
            delivery.deliver(row.getId(),publisher);
        }
    }
}
