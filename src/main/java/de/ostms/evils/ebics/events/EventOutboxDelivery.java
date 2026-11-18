package de.ostms.evils.ebics.events;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
@Service
public class EventOutboxDelivery {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final EventOutboxRepository outbox;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    public EventOutboxDelivery(EventOutboxRepository outbox,ObjectMapper mapper,@Value("${evils.events.max-attempts:10}") int maxAttempts){
        if(maxAttempts<1||maxAttempts>100)throw new IllegalArgumentException("Event max-attempts must be 1..100");
        this.outbox=outbox;this.mapper=mapper;this.maxAttempts=maxAttempts;
    }
    /** Lock prevents simultaneous dispatch by multiple instances. A crash after broker acceptance
     * can still redeliver: the backend MUST deduplicate by event ID. Adapters need bounded timeouts. */
    @Transactional
    public void deliver(String id,EventPublisher publisher){
        var row=outbox.findLocked(id).orElse(null);Instant now=Instant.now();
        if(row==null||!row.due(now))return;
        try{
            publisher.publish(mapper.readValue(row.getEventJson(),IntegrationEvent.class));
            row.delivered(Instant.now());
        }catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            // Exception messages can contain broker URLs, credentials or payloads.
            row.failed(Instant.now(),e.getClass().getSimpleName(),maxAttempts);
        }
    }

    @Transactional
    public boolean replay(String id,String administrator) {
        if(administrator==null||administrator.isBlank()) throw new IllegalArgumentException("Administrator required");
        var row=outbox.findLocked(id).orElse(null);
        if(row==null) return false;
        entityManager.persist(new EventReplayAudit(row,administrator));
        row.replay(Instant.now());
        return true;
    }
}
