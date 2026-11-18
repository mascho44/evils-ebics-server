package de.ostms.evils.ebics.service;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class PaymentHistoryService {
    @org.springframework.beans.factory.annotation.Autowired private de.ostms.evils.ebics.events.EventOutboxWriter outbox;
    private final PaymentEventRepository events;
    private final PaymentOrderRepository orders;
    public PaymentHistoryService(PaymentEventRepository events,PaymentOrderRepository orders){this.events=events;this.orders=orders;}
    @Transactional public void record(Long id,String kind,String detail){save(new PaymentEvent(UUID.randomUUID().toString(),id,kind,detail));}
    public List<PaymentEvent> forOrder(Long id){return events.findByPaymentOrderIdOrderByOccurredAtAsc(id);}
    @Transactional public void bookingFailed(Long id,String reason){
        String detail=reason==null?"Buchung fehlgeschlagen":reason.substring(0,Math.min(900,reason.length()));
        once("BOOKING-FAIL-"+id+"-"+java.time.LocalDate.now(),id,"BOOKING_FAILED",detail);
    }
    @Transactional public void uploaded(List<PaymentOrder> batch){
        var first=batch.getFirst();
        once("UPLOAD-"+first.getEbicsOrderId(),first.getId(),"FILE_UPLOAD","Datei empfangen und fachlich geprüft; "+batch.size()+" Zahlungen");
        finish(first.getSubmissionId());
    }
    @Transactional public void finish(String submissionId){
        if(submissionId==null)return;
        var batch=orders.findBySubmissionIdOrderById(submissionId);
        if(batch.isEmpty())return;
        var first=batch.getFirst();
        if(!events.existsById("UPLOAD-"+first.getEbicsOrderId()))return;
        if(batch.stream().noneMatch(o->o.getStatus()==PaymentOrderStatus.PENDING_SIGNATURES))
            once("FINAL-"+first.getEbicsOrderId(),first.getId(),"ORDER_HAC_FINAL_POS","EBICS-Testauftrag freigegeben; Terminzahlungen werden erst bei Fälligkeit gebucht");
        else once("PENDING-"+first.getEbicsOrderId(),first.getId(),"VEU_FORWARDING","Weitere Freigabe erforderlich");
    }
    private void once(String key,Long id,String kind,String detail){if(!events.existsById(key))save(new PaymentEvent(key,id,kind,detail));}
    private void save(PaymentEvent event){events.save(event);outbox.enqueue(event);}
}
