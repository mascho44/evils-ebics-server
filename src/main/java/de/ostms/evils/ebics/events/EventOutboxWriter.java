package de.ostms.evils.ebics.events;
import tools.jackson.databind.ObjectMapper;
import de.ostms.evils.ebics.domain.PaymentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Locale;
@Service
public class EventOutboxWriter {
    private final EventOutboxRepository outbox;
    private final ObjectMapper mapper;
    public EventOutboxWriter(EventOutboxRepository outbox,ObjectMapper mapper){this.outbox=outbox;this.mapper=mapper;}
    /** Never enqueue outside the transaction that creates the business event. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(PaymentEvent payment){
        if(outbox.existsById(payment.getId()))return;
        var event=new IntegrationEvent(payment.getId(),1,"evils-ebics-server",
            "payment."+payment.getKind().toLowerCase(Locale.ROOT),"payment",String.valueOf(payment.getPaymentOrderId()),
            payment.getOccurredAt(),new IntegrationEvent.PaymentData(payment.getPaymentOrderId(),payment.getKind()));
        try{outbox.save(new EventOutbox(event.id(),mapper.writeValueAsString(event),event.occurredAt()));}
        catch(tools.jackson.core.JacksonException e){throw new IllegalStateException("Cannot serialize integration event",e);}
    }
}
