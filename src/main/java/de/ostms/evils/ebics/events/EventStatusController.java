package de.ostms.evils.ebics.events;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/admin/api/events")
public class EventStatusController {
    private final EventOutboxRepository outbox;
    private final ObjectProvider<EventPublisher> publisher;
    private final EventOutboxDelivery delivery;
    private final boolean enabled;
    public EventStatusController(EventOutboxRepository outbox,ObjectProvider<EventPublisher> publisher,EventOutboxDelivery delivery,@Value("${evils.events.dispatch-enabled:false}") boolean enabled){this.outbox=outbox;this.publisher=publisher;this.delivery=delivery;this.enabled=enabled;}
    public record Status(boolean dispatchEnabled,boolean adapterConfigured,long pending,long delivered,long dead){}
    public record Entry(String id,String status,int attempts,java.time.Instant nextAttemptAt,java.time.Instant deliveredAt,String lastError) {}
    @GetMapping public java.util.List<Entry> entries(@RequestParam(defaultValue="DEAD") String status) {
        if(!java.util.Set.of("PENDING","DELIVERED","DEAD").contains(status)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid status");
        return outbox.findByStatus(status,org.springframework.data.domain.PageRequest.of(0,100,org.springframework.data.domain.Sort.by("nextAttemptAt").ascending().and(org.springframework.data.domain.Sort.by("id"))))
            .stream().map(e->new Entry(e.getId(),e.getStatus(),e.getAttempts(),e.getNextAttemptAt(),e.getDeliveredAt(),e.getLastError())).toList();
    }
    @GetMapping("/status") public Status status(){return new Status(enabled,publisher.getIfAvailable()!=null,outbox.countByStatus("PENDING"),outbox.countByStatus("DELIVERED"),outbox.countByStatus("DEAD"));}
    @PostMapping("/{id}/replay")
    public Status replay(@PathVariable String id,java.security.Principal principal) {
        try {
            if(!delivery.replay(id,principal.getName())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Event not found");
        } catch(IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Only DEAD events can be replayed");
        }
        return status();
    }
}
